package com.Teenkung.devStorageDrawer.receipt;

import com.Teenkung.devStorageDrawer.api.DrawerChangeCause;
import com.Teenkung.devStorageDrawer.block.DrawerBlockAccess;
import com.Teenkung.devStorageDrawer.block.DrawerRuntimeContext;
import com.Teenkung.devStorageDrawer.domain.DrawerItemIdentity;
import com.Teenkung.devStorageDrawer.domain.DrawerJournalKind;
import com.Teenkung.devStorageDrawer.domain.DrawerJournalReconciliation;
import com.Teenkung.devStorageDrawer.domain.DrawerJournalPhase;
import com.Teenkung.devStorageDrawer.domain.DrawerProxyJournal;
import com.Teenkung.devStorageDrawer.domain.DrawerState;
import com.Teenkung.devStorageDrawer.domain.DrawerStorageTransaction;
import com.Teenkung.devStorageDrawer.domain.DrawerWithdrawalPlan;
import com.Teenkung.devStorageDrawer.hopper.DrawerProxyInventory;
import com.Teenkung.devStorageDrawer.persistence.DrawerStateReadResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Coordinates drawer journals, the fsync-backed receipt ledger, and idempotent player delivery.
 * Bukkit state is touched only on region/entity schedulers; ledger mutation is async-only.
 */
public final class DrawerWithdrawalCoordinator implements Listener {
    private static final NamespacedKey CLAIMED_RECEIPTS = new NamespacedKey(
            "devstoragedrawer",
            "claimed_withdrawal_receipts"
    );

    private final DrawerRuntimeContext context;
    private final WithdrawalReceiptStore store;
    private final Set<UUID> inFlightOperations = ConcurrentHashMap.newKeySet();
    private Consumer<Barrel> rebalanceRequester = barrel -> { };
    private boolean requesterConfigured;

    public DrawerWithdrawalCoordinator(
            final DrawerRuntimeContext context,
            final WithdrawalReceiptStore store
    ) {
        this.context = Objects.requireNonNull(context, "context");
        this.store = Objects.requireNonNull(store, "store");
    }

    public void setRebalanceRequester(final Consumer<Barrel> requester) {
        if (requesterConfigured) {
            throw new IllegalStateException("Withdrawal rebalance requester is already configured");
        }
        this.rebalanceRequester = Objects.requireNonNull(requester, "requester");
        this.requesterConfigured = true;
    }

    /** Starts a durable withdrawal while called on the drawer's owning region. */
    public boolean begin(
            final Player player,
            final Barrel barrel,
            final DrawerStorageTransaction transaction
    ) {
        return begin(
                player,
                barrel,
                transaction,
                DrawerWithdrawalPlan.forTransaction(transaction, player.getUniqueId(), System.currentTimeMillis())
        );
    }

    /** Returns already-inserted over-capacity items through the same durable receipt protocol. */
    public boolean beginOverflowRecovery(
            final Player player,
            final Barrel barrel,
            final DrawerStorageTransaction transaction
    ) {
        return begin(
                player,
                barrel,
                transaction,
                DrawerWithdrawalPlan.forOverflowRecovery(transaction, player.getUniqueId(), System.currentTimeMillis())
        );
    }

    private boolean begin(
            final Player player,
            final Barrel barrel,
            final DrawerStorageTransaction transaction,
            final DrawerWithdrawalPlan plan
    ) {
        if (!context.repository().save(barrel, plan.journaledState())) {
            return false;
        }
        inFlightOperations.add(plan.journal().operationId());

        final Location location = barrel.getLocation();
        final WithdrawalReceipt receipt = new WithdrawalReceipt(
                plan.journal().operationId(),
                player.getUniqueId(),
                barrel.getWorld().getUID(),
                barrel.getX(),
                barrel.getY(),
                barrel.getZ(),
                transaction.itemTemplate().orElseThrow().serializeAsBytes(),
                transaction.acceptedAmount(),
                WithdrawalReceiptStatus.PREPARED,
                plan.journal().createdAtEpochMillis()
        );
        context.execution().runAsync(task -> {
            try {
                store.prepare(receipt);
                context.execution().executeAt(location, () -> applyPrepared(player, location, transaction, plan));
            } catch (final IOException exception) {
                context.logger().severe("Could not persist withdrawal receipt " + receipt.operationId() + ": "
                        + exception.getMessage());
                context.execution().executeAt(location, () -> rollbackPrepared(location, plan.journal(), player));
            }
        });
        return true;
    }

    /** Returns whether this server is still advancing the drawer's durable withdrawal protocol. */
    public boolean isWithdrawalInProgress(final Barrel barrel) {
        final DrawerStateReadResult read = context.repository().read(barrel);
        return read instanceof DrawerStateReadResult.Valid valid
                && valid.state().proxyJournal()
                .filter(journal -> isOwnerWithdrawal(journal.kind()))
                .map(journal -> inFlightOperations.contains(journal.operationId()))
                .orElse(false);
    }

    /** Routes a player-withdrawal journal through its receipt protocol during chunk repair. */
    public void recover(
            final Barrel barrel,
            final DrawerState state,
            final long observedPhysical,
            final Runnable resume
    ) {
        final DrawerProxyJournal journal = state.proxyJournal().orElse(null);
        if (journal == null || !isOwnerWithdrawal(journal.kind())) {
            throw new IllegalArgumentException("Withdrawal recovery requires a player-withdrawal journal");
        }
        // A live operation has already persisted its PREPARED journal and is waiting for its
        // receipt fsync. Treating that journal as a crash here would remove the receipt while the
        // original operation is still about to commit it.
        if (inFlightOperations.contains(journal.operationId())) {
            return;
        }
        if (journal.kind() == DrawerJournalKind.OVERFLOW_RECOVERY
                && journal.phase() == DrawerJournalPhase.PREPARED) {
            resumePreparedOverflow(barrel, state, journal, resume);
            return;
        }
        final DrawerJournalReconciliation reconciliation = context.storage().reconcile(state, observedPhysical);
        if (reconciliation.requiresManualRecovery()) {
            context.logger().warning("Quarantined invalid withdrawal journal " + journal.operationId()
                    + " at " + barrel.getLocation());
            return;
        }
        if (reconciliation.resolution() == DrawerJournalReconciliation.Resolution.ROLLED_BACK) {
            persistRollback(barrel.getLocation(), journal, reconciliation.state(), resume);
            return;
        }

        final WithdrawalReceipt receipt = store.find(journal.operationId()).orElse(null);
        if (receipt == null) {
            restoreMissingAppliedReceipt(barrel, state, journal, resume);
            return;
        }
        if (!receipt.ownerId().equals(journal.ownerId())) {
            context.logger().severe("Quarantined committed withdrawal " + journal.operationId()
                    + " because its durable receipt is missing or has the wrong owner");
            return;
        }
        persistCommit(barrel.getLocation(), journal, resume);
    }

    /** Resumes an overflow receipt without ever rolling its temporary expanded capacity into normal use. */
    private void resumePreparedOverflow(
            final Barrel barrel,
            final DrawerState state,
            final DrawerProxyJournal journal,
            final Runnable resume
    ) {
        final long count;
        try {
            count = Math.subtractExact(journal.totalBefore(), journal.totalAfter());
            if (count <= 0L || journal.physicalBefore() - journal.physicalTarget() != count) {
                throw new ArithmeticException("overflow journal counts do not match");
            }
        } catch (final ArithmeticException exception) {
            context.logger().severe("Quarantined overflow recovery " + journal.operationId()
                    + " because its counts are invalid");
            return;
        }
        if (!state.hasTemplate() || !inFlightOperations.add(journal.operationId())) {
            return;
        }

        final WithdrawalReceipt existing = store.find(journal.operationId()).orElse(null);
        if (existing != null && !existing.ownerId().equals(journal.ownerId())) {
            finishOperation(journal.operationId());
            context.logger().severe("Quarantined overflow recovery " + journal.operationId()
                    + " because its durable receipt has the wrong owner");
            return;
        }

        final Location location = barrel.getLocation();
        if (existing != null) {
            applyPreparedOverflow(location, journal, resume);
            return;
        }
        final WithdrawalReceipt replacement = receipt(barrel, state.requireTemplate(), journal, count);
        context.execution().runAsync(task -> {
            try {
                store.prepare(replacement);
                context.execution().executeAt(location, () -> applyPreparedOverflow(location, journal, resume));
            } catch (final IOException exception) {
                finishOperation(journal.operationId());
                context.logger().severe("Could not persist overflow recovery receipt " + journal.operationId()
                        + ": " + exception.getMessage());
            }
        });
    }

    private void applyPreparedOverflow(
            final Location location,
            final DrawerProxyJournal journal,
            final Runnable resume
    ) {
        final Barrel barrel = liveJournaledBarrel(location, journal.operationId());
        if (barrel == null) {
            finishOperation(journal.operationId());
            return;
        }
        final DrawerStateReadResult read = context.repository().read(barrel);
        if (!(read instanceof DrawerStateReadResult.Valid valid)) {
            finishOperation(journal.operationId());
            return;
        }
        final DrawerBlockAccess.PhysicalStock stock = context.blocks().inspect(barrel, valid.state());
        if (!stock.matchesTemplate()) {
            finishOperation(journal.operationId());
            context.logger().severe("Quarantined overflow recovery " + journal.operationId()
                    + " because its physical item no longer matches");
            return;
        }

        if (stock.count() < journal.physicalTarget() || stock.count() > journal.physicalBefore()) {
            finishOperation(journal.operationId());
            context.logger().severe("Quarantined overflow recovery " + journal.operationId()
                    + " because its physical count changed to " + stock.count());
            return;
        }
        final long remainingRemoval = stock.count() - journal.physicalTarget();
        if (remainingRemoval > 0L && !DrawerProxyInventory.remove(
                barrel, valid.state().requireTemplate(), remainingRemoval
        )) {
            finishOperation(journal.operationId());
            return;
        }

        final DrawerState applied = valid.state()
                .withStoredTotalAndExpectedMirrorCount(journal.totalAfter(), journal.physicalTarget())
                .withProxyJournal(journal.withPhase(DrawerJournalPhase.APPLIED));
        final Barrel afterRemoval = context.blocks().barrel(location.getBlock()).orElse(null);
        if (afterRemoval == null || !context.repository().save(afterRemoval, applied)) {
            finishOperation(journal.operationId());
            context.logger().warning("Overflow recovery " + journal.operationId()
                    + " changed proxy stock before its APPLIED phase could be saved; repair will retry it");
            return;
        }
        persistCommit(location, journal, resume);
    }

    /**
     * Repairs receipts lost by the older rapid-click recovery path: an APPLIED journal proves the item
     * was removed, while the still-present journal proves the normal idempotent delivery never
     * completed. PREPARED journals intentionally continue through rollback instead.
     */
    private void restoreMissingAppliedReceipt(
            final Barrel barrel,
            final DrawerState state,
            final DrawerProxyJournal journal,
            final Runnable resume
    ) {
        if (journal.phase() != DrawerJournalPhase.APPLIED || !state.hasTemplate()) {
            context.logger().severe("Quarantined withdrawal " + journal.operationId()
                    + " because its required durable receipt is missing");
            return;
        }
        final long count;
        try {
            count = Math.subtractExact(journal.totalBefore(), journal.totalAfter());
        } catch (final ArithmeticException exception) {
            context.logger().severe("Quarantined withdrawal " + journal.operationId()
                    + " because its missing receipt count is invalid");
            return;
        }
        if (count <= 0L || !inFlightOperations.add(journal.operationId())) {
            return;
        }

        final Location location = barrel.getLocation();
        final WithdrawalReceipt replacement = new WithdrawalReceipt(
                journal.operationId(),
                journal.ownerId(),
                barrel.getWorld().getUID(),
                barrel.getX(),
                barrel.getY(),
                barrel.getZ(),
                state.requireTemplate().serializeAsBytes(),
                count,
                WithdrawalReceiptStatus.PREPARED,
                journal.createdAtEpochMillis()
        );
        context.logger().warning("Recreating missing receipt for applied withdrawal " + journal.operationId()
                + " at " + location);
        context.execution().runAsync(task -> {
            try {
                store.prepare(replacement);
                persistCommit(location, journal, resume);
            } catch (final IOException exception) {
                finishOperation(journal.operationId());
                context.logger().severe("Could not recreate missing withdrawal receipt "
                        + journal.operationId() + ": " + exception.getMessage());
            }
        });
    }

    @EventHandler
    public void onPlayerJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        recoverPreparedFor(player);
        attemptDelivery(player);
    }

    private void applyPrepared(
            final Player player,
            final Location location,
            final DrawerStorageTransaction transaction,
            final DrawerWithdrawalPlan plan
    ) {
        final Barrel barrel = liveJournaledBarrel(location, plan.journal().operationId());
        if (barrel == null) {
            removeReceiptAsync(plan.journal().operationId());
            message(player, "general.configuration-error", Map.of());
            return;
        }
        final DrawerStateReadResult read = context.repository().read(barrel);
        if (!(read instanceof DrawerStateReadResult.Valid valid)) {
            finishOperation(plan.journal().operationId());
            message(player, "general.configuration-error", Map.of());
            return;
        }
        final DrawerBlockAccess.PhysicalStock stock = context.blocks().inspect(barrel, valid.state());
        if (!stock.matchesTemplate() || stock.count() != transaction.physicalBefore()) {
            rollbackPrepared(location, plan.journal(), player);
            return;
        }

        final long physicalTaken = transaction.physicalBefore() - transaction.physicalAfter();
        if (physicalTaken > 0L && !DrawerProxyInventory.remove(
                barrel,
                transaction.itemTemplate().orElseThrow(),
                physicalTaken
        )) {
            rollbackPrepared(location, plan.journal(), player);
            return;
        }
        // Reacquire after proxy removal; saving the older Barrel snapshot could otherwise restore
        // the inventory contents it held before DrawerProxyInventory.remove.
        final Barrel afterRemoval = context.blocks().barrel(location.getBlock()).orElse(null);
        final boolean appliedSaved = afterRemoval != null
                && context.repository().save(afterRemoval, plan.appliedJournaledState());
        if (!appliedSaved && physicalTaken == 0L) {
            rollbackPrepared(location, plan.journal(), player);
            return;
        }
        if (!appliedSaved) {
            context.logger().warning("Withdrawal " + plan.journal().operationId()
                    + " changed proxy stock before its APPLIED phase could be saved; recovery will complete it");
        }
        persistCommit(location, plan.journal(), () -> {
            final Barrel live = context.blocks().barrel(location.getBlock()).orElse(null);
            if (live != null) {
                rebalanceRequester.accept(live);
            }
            attemptDelivery(player);
        });
    }

    private void persistCommit(
            final Location location,
            final DrawerProxyJournal journal,
            final Runnable resume
    ) {
        context.execution().runAsync(task -> {
            try {
                store.markDeliverable(journal.operationId());
                context.execution().executeAt(location, () -> {
                    try {
                        final Barrel barrel = liveJournaledBarrel(location, journal.operationId());
                        if (barrel == null) {
                            return;
                        }
                        final DrawerStateReadResult read = context.repository().read(barrel);
                        if (!(read instanceof DrawerStateReadResult.Valid valid)) {
                            return;
                        }
                        final DrawerBlockAccess.PhysicalStock stock = context.blocks().inspect(barrel, valid.state());
                        if (!stock.matchesTemplate()) {
                            return;
                        }
                        final DrawerJournalReconciliation fresh = context.storage().reconcile(valid.state(), stock.count());
                        if (fresh.requiresManualRecovery()
                                || fresh.resolution() == DrawerJournalReconciliation.Resolution.ROLLED_BACK) {
                            context.logger().severe("Withdrawal " + journal.operationId()
                                    + " became inconsistent after its receipt was made deliverable");
                            return;
                        }
                        final DrawerState resolved = journal.kind() == DrawerJournalKind.OVERFLOW_RECOVERY
                                ? fresh.state().withCapacitySnapshot(journal.totalAfter())
                                : fresh.state();
                        if (context.repository().save(barrel, resolved)) {
                            context.notifyCommitted(barrel, resolved, stock.count(), DrawerChangeCause.PLAYER_WITHDRAW);
                            resume.run();
                            final Player owner = Bukkit.getPlayer(journal.ownerId());
                            if (owner != null) {
                                attemptDelivery(owner);
                            }
                        }
                    } finally {
                        finishOperation(journal.operationId());
                    }
                });
            } catch (final IOException exception) {
                finishOperation(journal.operationId());
                context.logger().severe("Could not make withdrawal receipt " + journal.operationId()
                        + " deliverable: " + exception.getMessage());
            }
        });
    }

    private void persistRollback(
            final Location location,
            final DrawerProxyJournal journal,
            final DrawerState rolledBackState,
            final Runnable resume
    ) {
        context.execution().runAsync(task -> {
            try {
                store.remove(journal.operationId());
                context.execution().executeAt(location, () -> {
                    try {
                        final Barrel barrel = liveJournaledBarrel(location, journal.operationId());
                        if (barrel != null && context.repository().save(barrel, rolledBackState)) {
                            final DrawerBlockAccess.PhysicalStock stock = context.blocks().inspect(barrel, rolledBackState);
                            context.notifyCommitted(barrel, rolledBackState, stock.count(), DrawerChangeCause.RECOVERY);
                            resume.run();
                        }
                    } finally {
                        finishOperation(journal.operationId());
                    }
                });
            } catch (final IOException exception) {
                finishOperation(journal.operationId());
                context.logger().severe("Could not roll back withdrawal receipt " + journal.operationId()
                        + ": " + exception.getMessage());
            }
        });
    }

    private void rollbackPrepared(
            final Location location,
            final DrawerProxyJournal journal,
            final Player player
    ) {
        if (journal.kind() == DrawerJournalKind.OVERFLOW_RECOVERY) {
            // Never expose the temporary expanded capacity as a completed rollback. Keep the
            // PREPARED journal in place so the next interaction, watcher pass, or repair command
            // resumes its owner-bound receipt without losing or accepting more items.
            finishOperation(journal.operationId());
            message(player, "general.configuration-error", Map.of());
            return;
        }
        final Barrel barrel = liveJournaledBarrel(location, journal.operationId());
        if (barrel == null) {
            removeReceiptAsync(journal.operationId());
            message(player, "general.configuration-error", Map.of());
            return;
        }
        final DrawerStateReadResult read = context.repository().read(barrel);
        if (!(read instanceof DrawerStateReadResult.Valid valid)) {
            finishOperation(journal.operationId());
            message(player, "general.configuration-error", Map.of());
            return;
        }
        final DrawerBlockAccess.PhysicalStock stock = context.blocks().inspect(barrel, valid.state());
        final DrawerJournalReconciliation reconciliation = context.storage().reconcile(valid.state(), stock.count());
        if (reconciliation.resolution() != DrawerJournalReconciliation.Resolution.ROLLED_BACK) {
            // This path needs normal recovery to finish the protocol, so it must no longer be
            // treated as the live operation that originally requested the rollback.
            finishOperation(journal.operationId());
            recover(barrel, valid.state(), stock.count(), () -> rebalanceRequester.accept(barrel));
            return;
        }
        persistRollback(location, journal, reconciliation.state(), () -> message(
                player,
                "general.configuration-error",
                Map.of()
        ));
    }

    private void recoverPreparedFor(final Player player) {
        for (final WithdrawalReceipt receipt : store.forOwner(player.getUniqueId())) {
            if (receipt.status() != WithdrawalReceiptStatus.PREPARED) {
                continue;
            }
            final World world = Bukkit.getWorld(receipt.worldId());
            if (world == null) {
                continue;
            }
            final Location location = new Location(world, receipt.blockX(), receipt.blockY(), receipt.blockZ());
            context.execution().executeAt(location, () -> {
                final Barrel barrel = liveJournaledBarrel(location, receipt.operationId());
                if (barrel == null) {
                    context.logger().severe("Kept prepared withdrawal receipt " + receipt.operationId()
                            + " because its journaled drawer is unavailable; run /drawers repair after restoring the block");
                    return;
                }
                final DrawerStateReadResult read = context.repository().read(barrel);
                if (!(read instanceof DrawerStateReadResult.Valid valid)) {
                    return;
                }
                final DrawerBlockAccess.PhysicalStock stock = context.blocks().inspect(barrel, valid.state());
                recover(barrel, valid.state(), stock.count(), () -> rebalanceRequester.accept(barrel));
            });
        }
    }

    private void attemptDelivery(final Player player) {
        context.execution().runOnEntity(player, task -> deliverOnEntityThread(player), () -> { });
    }

    private void deliverOnEntityThread(final Player player) {
        if (!player.isOnline()) {
            return;
        }
        final List<WithdrawalReceipt> deliverable = store.forOwner(player.getUniqueId()).stream()
                .filter(receipt -> receipt.status() == WithdrawalReceiptStatus.DELIVERABLE)
                .toList();
        if (deliverable.isEmpty()) {
            return;
        }

        final PersistentDataContainer pdc = player.getPersistentDataContainer();
        final String claimedBefore = pdc.get(CLAIMED_RECEIPTS, PersistentDataType.STRING);
        final Set<UUID> claimed = parseClaimed(claimedBefore);
        final ItemStack[] inventoryBefore = cloneContents(player.getInventory().getStorageContents());
        final List<UUID> acknowledgements = new ArrayList<>();
        long deliveredCount = 0L;
        try {
            for (final WithdrawalReceipt receipt : deliverable) {
                if (claimed.contains(receipt.operationId())) {
                    acknowledgements.add(receipt.operationId());
                    continue;
                }
                final ItemStack template = ItemStack.deserializeBytes(receipt.templateBytes());
                DrawerItemIdentity.requireStorageCandidate(template, "Withdrawal receipt template");
                if (availableSpace(player.getInventory(), template) < receipt.count()) {
                    continue;
                }
                giveExact(player.getInventory(), template, receipt.count());
                claimed.add(receipt.operationId());
                acknowledgements.add(receipt.operationId());
                deliveredCount += receipt.count();
            }
            if (acknowledgements.isEmpty()) {
                message(player, "drawer.withdrawal-pending", Map.of());
                return;
            }
            writeClaimed(pdc, claimed);
            // Inventory and the idempotency marker are persisted in the same playerdata save.
            player.saveData();
        } catch (final RuntimeException exception) {
            player.getInventory().setStorageContents(inventoryBefore);
            restoreClaimed(pdc, claimedBefore);
            context.logger().severe("Could not deliver withdrawal receipts to " + player.getName()
                    + ": " + exception.getMessage());
            return;
        }

        final long delivered = deliveredCount;
        context.execution().runAsync(task -> {
            try {
                store.removeAll(acknowledgements);
                context.execution().runOnEntity(player, ignored -> {
                    final Set<UUID> remainingClaims = parseClaimed(
                            player.getPersistentDataContainer().get(CLAIMED_RECEIPTS, PersistentDataType.STRING)
                    );
                    remainingClaims.removeAll(acknowledgements);
                    writeClaimed(player.getPersistentDataContainer(), remainingClaims);
                    if (delivered > 0L) {
                        message(player, "drawer.withdrawn", Map.of("amount", delivered));
                    }
                }, () -> { });
            } catch (final IOException exception) {
                context.logger().severe("Could not acknowledge delivered withdrawal receipts for "
                        + player.getName() + ": " + exception.getMessage());
            }
        });
    }

    private Barrel liveJournaledBarrel(final Location location, final UUID operationId) {
        final Barrel barrel = context.blocks().barrel(location.getBlock()).orElse(null);
        if (barrel == null || !context.repository().isDrawer(barrel)) {
            return null;
        }
        final DrawerStateReadResult read = context.repository().read(barrel);
        if (!(read instanceof DrawerStateReadResult.Valid valid)) {
            return null;
        }
        return valid.state().proxyJournal()
                .filter(journal -> journal.operationId().equals(operationId))
                .map(ignored -> barrel)
                .orElse(null);
    }

    private void removeReceiptAsync(final UUID operationId) {
        context.execution().runAsync(task -> {
            try {
                store.remove(operationId);
            } catch (final IOException exception) {
                context.logger().warning("Could not remove withdrawal receipt " + operationId + ": "
                        + exception.getMessage());
            } finally {
                finishOperation(operationId);
            }
        });
    }

    private void finishOperation(final UUID operationId) {
        inFlightOperations.remove(operationId);
    }

    private static WithdrawalReceipt receipt(
            final Barrel barrel,
            final ItemStack template,
            final DrawerProxyJournal journal,
            final long count
    ) {
        return new WithdrawalReceipt(
                journal.operationId(),
                journal.ownerId(),
                barrel.getWorld().getUID(),
                barrel.getX(),
                barrel.getY(),
                barrel.getZ(),
                template.serializeAsBytes(),
                count,
                WithdrawalReceiptStatus.PREPARED,
                journal.createdAtEpochMillis()
        );
    }

    private static boolean isOwnerWithdrawal(final DrawerJournalKind kind) {
        return kind == DrawerJournalKind.PLAYER_WITHDRAWAL || kind == DrawerJournalKind.OVERFLOW_RECOVERY;
    }

    private void message(final Player player, final String key, final Map<String, ?> placeholders) {
        context.execution().runOnEntity(
                player,
                task -> player.sendMessage(context.messages().component(key, placeholders)),
                () -> { }
        );
    }

    private static Set<UUID> parseClaimed(final String raw) {
        final Set<UUID> claimed = new HashSet<>();
        if (raw == null || raw.isBlank()) {
            return claimed;
        }
        for (final String token : raw.split(",")) {
            try {
                claimed.add(UUID.fromString(token));
            } catch (final IllegalArgumentException ignored) {
                // A malformed private marker is discarded on the next successful delivery.
            }
        }
        return claimed;
    }

    private static void writeClaimed(final PersistentDataContainer pdc, final Collection<UUID> claimed) {
        if (claimed.isEmpty()) {
            pdc.remove(CLAIMED_RECEIPTS);
        } else {
            pdc.set(CLAIMED_RECEIPTS, PersistentDataType.STRING, claimed.stream()
                    .map(UUID::toString)
                    .sorted()
                    .reduce((left, right) -> left + "," + right)
                    .orElseThrow());
        }
    }

    private static void restoreClaimed(final PersistentDataContainer pdc, final String raw) {
        if (raw == null) {
            pdc.remove(CLAIMED_RECEIPTS);
        } else {
            pdc.set(CLAIMED_RECEIPTS, PersistentDataType.STRING, raw);
        }
    }

    private static long availableSpace(final PlayerInventory inventory, final ItemStack template) {
        long available = 0L;
        final int maximum = template.getMaxStackSize();
        for (final ItemStack current : inventory.getStorageContents()) {
            if (current == null || current.getType().isAir()) {
                available += maximum;
            } else if (current.isSimilar(template)) {
                available += Math.max(0, maximum - current.getAmount());
            }
        }
        return available;
    }

    private static void giveExact(final PlayerInventory inventory, final ItemStack template, long amount) {
        final ItemStack[] contents = inventory.getStorageContents();
        final int maximum = template.getMaxStackSize();
        for (int slot = 0; slot < contents.length && amount > 0L; slot++) {
            final ItemStack current = contents[slot];
            if (current == null || current.getType().isAir()) {
                final int placed = (int) Math.min(amount, (long) maximum);
                final ItemStack stack = template.clone();
                stack.setAmount(placed);
                contents[slot] = stack;
                amount -= placed;
            } else if (current.isSimilar(template) && current.getAmount() < maximum) {
                final int placed = (int) Math.min(amount, (long) (maximum - current.getAmount()));
                current.setAmount(current.getAmount() + placed);
                amount -= placed;
            }
        }
        if (amount != 0L) {
            throw new IllegalStateException("Inventory capacity changed during withdrawal receipt delivery");
        }
        inventory.setStorageContents(contents);
    }

    private static ItemStack[] cloneContents(final ItemStack[] contents) {
        final ItemStack[] clone = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++) {
            clone[index] = contents[index] == null ? null : contents[index].clone();
        }
        return clone;
    }
}
