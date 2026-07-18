package com.teenkung.devstoragedrawer.block;

import com.teenkung.devstoragedrawer.api.DrawerChangeCause;
import com.teenkung.devstoragedrawer.domain.DrawerInvariantViolationException;
import com.teenkung.devstoragedrawer.domain.DrawerJournalKind;
import com.teenkung.devstoragedrawer.domain.DrawerJournalPhase;
import com.teenkung.devstoragedrawer.domain.DrawerProxyJournal;
import com.teenkung.devstoragedrawer.domain.DrawerState;
import com.teenkung.devstoragedrawer.domain.DrawerStorageTransaction;
import com.teenkung.devstoragedrawer.hopper.DrawerHopperBridge;
import com.teenkung.devstoragedrawer.hopper.DrawerProxyInventory;
import com.teenkung.devstoragedrawer.persistence.DrawerStateReadResult;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Optional, synchronous bridge for sell tools from other plugins. Calls must originate on the
 * drawer's owning region thread; the bridge journals every removal before changing proxy stock.
 */
public final class DrawerSellWandBridge {

    private final DrawerRuntimeContext context;
    private final DrawerHopperBridge hoppers;

    DrawerSellWandBridge(final DrawerRuntimeContext context, final DrawerHopperBridge hoppers) {
        this.context = Objects.requireNonNull(context, "context");
        this.hoppers = Objects.requireNonNull(hoppers, "hoppers");
    }

    public boolean isDrawer(final Block block) {
        return this.context.blocks().isDrawer(block);
    }

    /** Applies the same drawer permission and facing-face gate as a normal drawer interaction. */
    public boolean canSellFrom(final Player player, final Block block, final BlockFace clickedFace) {
        if (player == null || !player.hasPermission("devstoragedrawer.use")) {
            return false;
        }
        final Barrel barrel = this.context.blocks().barrel(block).orElse(null);
        if (barrel == null || !this.context.repository().isDrawer(barrel)) {
            return false;
        }
        return !this.context.settings().interaction().requireFacingFace()
                || barrel.getBlockData() instanceof Directional directional && directional.getFacing() == clickedFace;
    }

    /** Returns the logical drawer contents, including hidden stock, without changing the drawer. */
    public Snapshot snapshot(final Barrel barrel) {
        final ReadyDrawer ready = readyDrawer(barrel);
        if (ready == null || !ready.state().hasTemplate()) {
            return Snapshot.empty();
        }
        return new Snapshot(ready.state().requireTemplate(), ready.state().totalForPhysical(ready.stock().count()));
    }

    /**
     * Removes all logical stock for a completed external sale. If this returns success, the caller
     * owns the returned amount and must either sell it or call {@link #restore(Barrel, ItemStack, long)}.
     */
    public Withdrawal withdrawAll(final Barrel barrel) {
        return withdraw(barrel, Long.MAX_VALUE, DrawerChangeCause.EXTERNAL_SALE);
    }

    /** Shared durable withdrawal path for trusted integrations, always called on the drawer region. */
    Withdrawal withdraw(final Barrel barrel, final long requestedAmount, final DrawerChangeCause cause) {
        if (requestedAmount <= 0L) {
            return Withdrawal.failed();
        }
        Objects.requireNonNull(cause, "cause");
        final ReadyDrawer ready = readyDrawer(barrel);
        if (ready == null || !ready.state().hasTemplate()) {
            return Withdrawal.failed();
        }

        try {
            final long total = ready.state().totalForPhysical(ready.stock().count());
            final long requested = Math.min(total, requestedAmount);
            final DrawerStorageTransaction transaction = this.context.storage().withdraw(
                    ready.state(),
                    ready.stock().count(),
                    requested
            );
            if (!transaction.accepted()) {
                return Withdrawal.failed();
            }

            final ItemStack template = transaction.itemTemplate().orElseThrow();
            final DrawerProxyJournal journal = new DrawerProxyJournal(
                    UUID.randomUUID(),
                    DrawerJournalKind.EXTERNAL_SALE,
                    DrawerJournalPhase.PREPARED,
                    null,
                    transaction.stateBefore().totalForPhysical(transaction.physicalBefore()),
                    transaction.stateAfter().totalForPhysical(transaction.physicalAfter()),
                    transaction.physicalBefore(),
                    transaction.physicalAfter(),
                    System.currentTimeMillis()
            );
            final DrawerState preparedState = transaction.stateBefore().withProxyJournal(journal);
            final DrawerState appliedBase = transaction.stateAfter().hasTemplate()
                    ? transaction.stateAfter()
                    : transaction.stateAfter().withTemplate(template);
            final DrawerState appliedState = appliedBase.withProxyJournal(journal.withPhase(DrawerJournalPhase.APPLIED));

            if (!this.context.repository().save(ready.barrel(), preparedState)) {
                return Withdrawal.failed();
            }

            final long physicalTaken = transaction.physicalBefore() - transaction.physicalAfter();
            if (physicalTaken > 0L && !DrawerProxyInventory.remove(ready.barrel(), template, physicalTaken)) {
                rollbackPrepared(ready.barrel(), transaction.stateBefore());
                return Withdrawal.failed();
            }

            final Barrel afterRemoval = this.context.blocks().barrel(ready.barrel().getBlock()).orElse(null);
            if (afterRemoval != null
                    && this.context.repository().save(afterRemoval, appliedState)
                    && commit(afterRemoval, transaction.stateAfter(), transaction.physicalAfter(), cause)) {
                return Withdrawal.success(template, transaction.acceptedAmount());
            }

            if (recoverExpectedTotal(ready.barrel(), transaction.stateAfter().totalForPhysical(transaction.physicalAfter()))) {
                return Withdrawal.success(template, transaction.acceptedAmount());
            }
            restorePreparedWithdrawal(ready.barrel(), template, transaction, journal);
        } catch (final DrawerInvariantViolationException | IllegalArgumentException exception) {
            this.context.logger().warning("Rejected external drawer sale at " + barrel.getLocation() + ": " + exception.getMessage());
        }
        return Withdrawal.failed();
    }

    /** Restores an amount that an external seller did not consume. */
    public boolean restore(final Barrel barrel, final ItemStack template, final long amount) {
        if (template == null || template.getType().isAir() || amount <= 0L) {
            return false;
        }

        final ReadyDrawer ready = readyDrawer(barrel);
        if (ready == null) {
            return false;
        }

        try {
            DrawerState state = ready.state();
            if (state.hasTemplate() && !state.matchesTemplate(template)) {
                return false;
            }
            if (!state.hasTemplate() && ready.stock().count() != 0L) {
                return false;
            }

            final long currentTotal = state.totalForPhysical(ready.stock().count());
            final long restoredTotal = Math.addExact(currentTotal, amount);
            if (restoredTotal > state.capacitySnapshot()) {
                return false;
            }

            if (!state.hasTemplate()) {
                state = state.withTemplate(template);
            }
            final DrawerState restored = state.usesReservedMirror()
                    ? state.withStoredTotalAndExpectedMirrorCount(restoredTotal, ready.stock().count())
                    : state.withHiddenCount(restoredTotal - ready.stock().count());
            if (!this.context.repository().save(ready.barrel(), restored)) {
                return false;
            }
            this.context.notifyCommitted(
                    ready.barrel(), restored, ready.stock().count(), DrawerChangeCause.EXTERNAL_SALE
            );
            this.hoppers.queueRebalance(ready.barrel());
            return true;
        } catch (final ArithmeticException | DrawerInvariantViolationException | IllegalArgumentException exception) {
            this.context.logger().warning("Could not restore external drawer sale at " + barrel.getLocation() + ": "
                    + exception.getMessage());
            return false;
        }
    }

    private boolean commit(
            final Barrel barrel,
            final DrawerState committedState,
            final long physicalAfter,
            final DrawerChangeCause cause
    ) {
        final Barrel live = this.context.blocks().barrel(barrel.getBlock()).orElse(null);
        if (live == null || !this.context.repository().save(live, committedState)) {
            return false;
        }
        try {
            this.context.notifyCommitted(live, committedState, physicalAfter, cause);
            this.hoppers.queueRebalance(live);
        } catch (final RuntimeException exception) {
            this.context.logger().warning("External drawer withdrawal committed at " + barrel.getLocation()
                    + " but post-commit refresh failed: " + exception.getMessage());
        }
        return true;
    }

    private void rollbackPrepared(final Barrel barrel, final DrawerState stateBefore) {
        this.context.blocks().barrel(barrel.getBlock())
                .ifPresent(live -> this.context.repository().save(live, stateBefore));
    }

    private boolean recoverExpectedTotal(final Barrel barrel, final long expectedTotal) {
        this.hoppers.recoverAndRebalance(barrel);
        final Barrel recovered = this.context.blocks().barrel(barrel.getBlock()).orElse(null);
        if (recovered == null) {
            return false;
        }
        final DrawerStateReadResult read = this.context.repository().read(recovered);
        if (!(read instanceof DrawerStateReadResult.Valid valid) || valid.state().hasPendingProxyJournal()) {
            return false;
        }
        final DrawerBlockAccess.PhysicalStock stock = this.context.blocks().inspect(recovered, valid.state());
        return stock.matchesTemplate() && valid.state().totalForPhysical(stock.count()) == expectedTotal;
    }

    /**
     * If the applied phase could not be persisted, restore the physical mirror only while the
     * prepared journal is still authoritative. A later reconciliation then rolls back safely.
     */
    private void restorePreparedWithdrawal(
            final Barrel barrel,
            final ItemStack template,
            final DrawerStorageTransaction transaction,
            final DrawerProxyJournal journal
    ) {
        final Barrel current = this.context.blocks().barrel(barrel.getBlock()).orElse(null);
        if (current == null) {
            return;
        }
        final DrawerStateReadResult read = this.context.repository().read(current);
        if (!(read instanceof DrawerStateReadResult.Valid valid)
                || valid.state().proxyJournal().filter(pending -> pending.operationId().equals(journal.operationId())
                && pending.phase() == DrawerJournalPhase.PREPARED).isEmpty()) {
            return;
        }

        if (!DrawerProxyInventory.rewrite(
                current,
                template,
                transaction.physicalBefore(),
                this.context.settings().automation().outputProxySlots()
        )) {
            return;
        }
        this.context.blocks().barrel(current.getBlock())
                .ifPresent(rewritten -> this.context.repository().save(rewritten, transaction.stateBefore()));
    }

    private ReadyDrawer readyDrawer(final Barrel barrel) {
        if (barrel == null || !Bukkit.isOwnedByCurrentRegion(barrel.getLocation())) {
            return null;
        }

        Barrel current = barrel;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (!this.context.repository().isDrawer(current)) {
                return null;
            }
            final DrawerStateReadResult read = this.context.repository().read(current);
            if (read instanceof DrawerStateReadResult.Valid valid) {
                final DrawerBlockAccess.PhysicalStock stock = this.context.blocks().inspect(current, valid.state());
                if (stock.matchesTemplate() && !valid.state().hasPendingProxyJournal()) {
                    return new ReadyDrawer(current, valid.state(), stock);
                }
            }
            if (attempt == 0) {
                this.hoppers.recoverAndRebalance(current);
                current = this.context.blocks().barrel(barrel.getBlock()).orElse(null);
                if (current == null) {
                    return null;
                }
            }
        }
        return null;
    }

    public record Snapshot(ItemStack template, long amount) {
        public Snapshot {
            template = template == null ? null : template.clone();
            if (amount < 0L) {
                throw new IllegalArgumentException("amount must not be negative");
            }
        }

        public static Snapshot empty() {
            return new Snapshot(null, 0L);
        }

        @Override
        public ItemStack template() {
            return template == null ? null : template.clone();
        }
    }

    public record Withdrawal(boolean success, ItemStack template, long amount) {
        public Withdrawal {
            template = template == null ? null : template.clone();
            if (amount < 0L) {
                throw new IllegalArgumentException("amount must not be negative");
            }
            if (success != (template != null && amount > 0L)) {
                throw new IllegalArgumentException("successful withdrawals require an item and positive amount");
            }
        }

        public static Withdrawal success(final ItemStack template, final long amount) {
            return new Withdrawal(true, template, amount);
        }

        public static Withdrawal failed() {
            return new Withdrawal(false, null, 0L);
        }

        @Override
        public ItemStack template() {
            return template == null ? null : template.clone();
        }
    }

    private record ReadyDrawer(Barrel barrel, DrawerState state, DrawerBlockAccess.PhysicalStock stock) {
    }
}
