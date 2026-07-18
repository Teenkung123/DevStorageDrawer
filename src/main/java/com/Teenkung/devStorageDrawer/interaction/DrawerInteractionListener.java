package com.teenkung.devstoragedrawer.interaction;

import com.teenkung.devstoragedrawer.api.DrawerChangeCause;
import com.teenkung.devstoragedrawer.block.DrawerBlockAccess;
import com.teenkung.devstoragedrawer.block.DrawerRuntime;
import com.teenkung.devstoragedrawer.block.DrawerRuntimeContext;
import com.teenkung.devstoragedrawer.config.DrawerTierDefinition;
import com.teenkung.devstoragedrawer.domain.DrawerInvariantViolationException;
import com.teenkung.devstoragedrawer.domain.DrawerState;
import com.teenkung.devstoragedrawer.domain.DrawerStorageTransaction;
import com.teenkung.devstoragedrawer.hopper.DrawerHopperBridge;
import com.teenkung.devstoragedrawer.persistence.DrawerStateReadResult;
import com.teenkung.devstoragedrawer.receipt.DrawerWithdrawalCoordinator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** Player-facing single-item drawer interaction; no barrel GUI is ever opened for tagged barrels. */
public final class DrawerInteractionListener implements Listener {

    private final DrawerRuntimeContext context;
    private final DrawerHopperBridge hoppers;
    private final DrawerWithdrawalCoordinator withdrawals;
    private final DrawerRuntime.DrawerDisplayTargetResolver displayTargetResolver;
    private final Map<UUID, BulkDepositArm> bulkDepositArms = new ConcurrentHashMap<>();
    private final Map<UUID, PendingBlockWithdrawal> pendingBlockWithdrawals = new ConcurrentHashMap<>();

    public DrawerInteractionListener(
            final DrawerRuntimeContext context,
            final DrawerHopperBridge hoppers,
            final DrawerWithdrawalCoordinator withdrawals,
            final DrawerRuntime.DrawerDisplayTargetResolver displayTargetResolver
    ) {
        this.context = Objects.requireNonNull(context, "context");
        this.hoppers = Objects.requireNonNull(hoppers, "hoppers");
        this.withdrawals = Objects.requireNonNull(withdrawals, "withdrawals");
        this.displayTargetResolver = Objects.requireNonNull(displayTargetResolver, "displayTargetResolver");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteract(final PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        final Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        final Barrel barrel = this.context.blocks().barrel(clicked).orElse(null);
        if (barrel == null || !this.context.repository().isDrawer(barrel)) {
            return;
        }
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            // Do not cancel the attack. Releasing it produces BlockDamageAbortEvent and withdraws;
            // completing it produces BlockBreakEvent and discards the pending withdrawal.
            if (!canUse(event.getPlayer())) {
                return;
            }
            if (this.context.settings().interaction().requireFacingFace() && !isFacingFace(barrel, event.getBlockFace())) {
                return;
            }
            if (!canWithdrawWith(barrel, heldItem(event.getPlayer(), event.getHand()))) {
                return;
            }
            armBlockWithdrawal(event.getPlayer(), barrel, event.getPlayer().isSneaking());
            return;
        }
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            deny(event, null);
            return;
        }
        if (!canUse(event.getPlayer())) {
            deny(event, "general.no-permission");
            return;
        }
        if (this.context.settings().interaction().requireFacingFace() && !isFacingFace(barrel, event.getBlockFace())) {
            // Vanilla does not open a container while the player is sneaking. Let a sneaking
            // player with an item use the side normally so blocks can be placed against drawers.
            if (event.getPlayer().isSneaking() && isUsableItem(heldItem(event.getPlayer(), event.getHand()))) {
                return;
            }
            deny(event, "drawer.interaction-face-only");
            return;
        }
        deny(event, null);
        rightClick(event.getPlayer(), barrel, event.getHand());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDisplayUse(final PlayerInteractAtEntityEvent event) {
        final Barrel barrel = this.displayTargetResolver.resolve(event.getRightClicked()).orElse(null);
        if (barrel == null || !this.context.repository().isDrawer(barrel)) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            return;
        }
        if (!canUse(event.getPlayer())) {
            message(event.getPlayer(), "general.no-permission");
            return;
        }
        rightClick(event.getPlayer(), barrel, event.getHand());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDisplayAttack(final EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        final Barrel barrel = this.displayTargetResolver.resolve(event.getEntity()).orElse(null);
        if (barrel == null || !this.context.repository().isDrawer(barrel)) {
            return;
        }
        event.setCancelled(true);
        if (!canUse(player)) {
            message(player, "general.no-permission");
            return;
        }
        if (!canWithdrawWith(barrel, player.getInventory().getItemInMainHand())) {
            return;
        }
        withdraw(player, barrel, player.isSneaking());
    }

    /** A released quick attack is a withdrawal; a held attack continues toward BlockBreakEvent. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDamageAbort(final BlockDamageAbortEvent event) {
        final Player player = event.getPlayer();
        final PendingBlockWithdrawal pending = this.pendingBlockWithdrawals.remove(player.getUniqueId());
        if (pending == null || !pending.matches(event.getBlock()) || pending.expired()) {
            return;
        }
        final Barrel barrel = this.context.blocks().barrel(event.getBlock()).orElse(null);
        if (barrel != null && this.context.repository().isDrawer(barrel)) {
            withdraw(player, barrel, pending.bulk());
        }
    }

    /** A completed break must never leave a delayed withdrawal able to rewrite the barrel. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockBreakIntent(final BlockBreakEvent event) {
        final PendingBlockWithdrawal pending = this.pendingBlockWithdrawals.get(event.getPlayer().getUniqueId());
        if (pending != null && pending.matches(event.getBlock())) {
            this.pendingBlockWithdrawals.remove(event.getPlayer().getUniqueId(), pending);
        }
    }

    @EventHandler
    public void onPlayerQuit(final PlayerQuitEvent event) {
        final UUID playerId = event.getPlayer().getUniqueId();
        this.pendingBlockWithdrawals.remove(playerId);
        this.bulkDepositArms.remove(playerId);
    }

    /** Covers plugin-driven opens as well as normal clicks; drawers never expose a player GUI. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(final InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)
                || !(event.getInventory().getHolder(false) instanceof Barrel barrel)
                || !this.context.repository().isDrawer(barrel)) {
            return;
        }
        event.setCancelled(true);
        message(player, "drawer.no-gui");
    }

    private void rightClick(final Player player, final Barrel barrel, final EquipmentSlot hand) {
        final ItemStack held = heldItem(player, hand);
        final ReadyDrawer ready = readyDrawer(barrel);
        if (ready == null) {
            if (!withdrawals.isWithdrawalInProgress(barrel)) {
                messageInvalidDrawer(player, barrel);
            }
            return;
        }
        final Barrel readyBarrel = ready.barrel();
        DrawerState state = ready.state();
        final DrawerBlockAccess.PhysicalStock stock = ready.stock();
        try {
            if (isBulkDepositArmed(player, readyBarrel, state, held)) {
                depositMatchingInventory(player, readyBarrel, state, stock.count(), true);
                this.bulkDepositArms.remove(player.getUniqueId());
                return;
            }
            if (!state.hasTemplate()) {
                if (!isUsableItem(held)) {
                    return;
                }
                depositHeld(player, readyBarrel, state, stock.count(), hand, held);
                return;
            }
            if (isUsableItem(held)) {
                if (state.matchesTemplate(held)) {
                    depositHeld(player, readyBarrel, state, stock.count(), hand, held);
                }
                return;
            }
            depositMatchingInventory(player, readyBarrel, state, stock.count(), player.isSneaking());
        } catch (final DrawerInvariantViolationException | IllegalArgumentException exception) {
            message(player, "general.configuration-error");
            this.context.logger().warning("Rejected drawer deposit at " + readyBarrel.getLocation() + ": " + exception.getMessage());
        }
    }

    private void depositHeld(
            final Player player,
            final Barrel barrel,
            DrawerState state,
            final long physicalCount,
            final EquipmentSlot hand,
            final ItemStack held
    ) {
        final DrawerTierDefinition tier = this.context.tier(state.tierId()).orElse(null);
        if (tier == null) {
            messageInvalidDrawer(player, barrel);
            return;
        }
        if (!state.hasTemplate()) {
            state = state.initializeCapacityForFirstItem(tier.tier(), held);
        }
        final DrawerStorageTransaction transaction = this.context.storage().deposit(
                state, physicalCount, held, held.getAmount()
        );
        if (!transaction.accepted()) {
            reportRejected(player, transaction);
            return;
        }
        // Drawers intentionally consume creative items too; this is a storage action, not a preview.
        consumeHeld(player, hand, (int) transaction.acceptedAmount());
        if (!this.context.repository().save(barrel, transaction.stateAfter())) {
            restoreHeld(player, hand, held, (int) transaction.acceptedAmount());
            message(player, "general.configuration-error");
            return;
        }
        this.context.notifyCommitted(
                barrel, transaction.stateAfter(), transaction.physicalAfter(), DrawerChangeCause.PLAYER_DEPOSIT
        );
        this.hoppers.queueRebalance(barrel);
        message(player, "drawer.deposited", Map.of("amount", transaction.acceptedAmount()));
        armBulkDeposit(player, barrel, transaction.stateAfter());
    }

    private void depositMatchingInventory(
            final Player player,
            final Barrel barrel,
            DrawerState state,
            final long physicalCount,
            final boolean allMatchingStacks
    ) {
        if (!state.hasTemplate()) {
            message(player, "drawer.no-matching-items");
            return;
        }
        long acceptedTotal = 0L;
        final ItemStack template = state.requireTemplate();
        final PlayerInventory inventory = player.getInventory();
        final Map<Integer, Integer> removals = new LinkedHashMap<>();
        final Map<Integer, ItemStack> originals = new LinkedHashMap<>();
        for (int slot = 0; slot < inventory.getStorageContents().length; slot++) {
            final ItemStack candidate = inventory.getItem(slot);
            if (!state.matchesTemplate(candidate)) {
                continue;
            }
            final int originalAmount = candidate.getAmount();
            final DrawerStorageTransaction transaction = this.context.storage().deposit(
                    state,
                    physicalCount,
                    candidate,
                    originalAmount
            );
            if (!transaction.accepted()) {
                break;
            }
            removals.put(slot, (int) transaction.acceptedAmount());
            state = transaction.stateAfter();
            acceptedTotal += transaction.acceptedAmount();
            if (!allMatchingStacks || transaction.acceptedAmount() < originalAmount) {
                break;
            }
        }
        if (acceptedTotal == 0L) {
            message(player, "drawer.no-matching-items");
            return;
        }
        for (final Map.Entry<Integer, Integer> removal : removals.entrySet()) {
            final ItemStack candidate = inventory.getItem(removal.getKey());
            if (candidate == null || !candidate.isSimilar(template) || candidate.getAmount() < removal.getValue()) {
                this.context.logger().warning("Player inventory changed during drawer deposit for " + player.getName());
                message(player, "general.configuration-error");
                return;
            }
            originals.put(removal.getKey(), candidate.clone());
        }
        for (final Map.Entry<Integer, Integer> removal : removals.entrySet()) {
            final ItemStack candidate = inventory.getItem(removal.getKey());
            candidate.setAmount(candidate.getAmount() - removal.getValue());
            inventory.setItem(removal.getKey(), candidate.getAmount() <= 0 ? null : candidate);
        }
        if (!this.context.repository().save(barrel, state)) {
            restoreInventorySlots(inventory, originals);
            message(player, "general.configuration-error");
            return;
        }
        this.context.notifyCommitted(barrel, state, physicalCount, DrawerChangeCause.PLAYER_DEPOSIT);
        this.hoppers.queueRebalance(barrel);
        message(player, "drawer.deposited", Map.of("amount", acceptedTotal, "item", template.getType().translationKey()));
    }

    private void withdraw(final Player player, final Barrel barrel, final boolean bulk) {
        final ReadyDrawer ready = readyDrawer(barrel);
        if (ready == null) {
            if (!withdrawals.isWithdrawalInProgress(barrel)) {
                messageInvalidDrawer(player, barrel);
            }
            return;
        }
        final Barrel readyBarrel = ready.barrel();
        final DrawerState state = ready.state();
        final DrawerBlockAccess.PhysicalStock stock = ready.stock();
        try {
            final long total = state.totalForPhysical(stock.count());
            if (total == 0L) {
                message(player, "drawer.empty");
                return;
            }
            final ItemStack template = state.requireTemplate();
            final long wanted = Math.min(
                    bulk ? Math.min(total, (long) template.getMaxStackSize()) : 1L,
                    availableSpace(player.getInventory(), template)
            );
            if (wanted <= 0L) {
                message(player, "drawer.inventory-full");
                return;
            }
            final DrawerStorageTransaction transaction = this.context.storage().withdraw(state, stock.count(), wanted);
            if (!transaction.accepted()) {
                reportRejected(player, transaction);
                return;
            }
            if (!this.withdrawals.begin(player, readyBarrel, transaction)) {
                message(player, "general.configuration-error");
                return;
            }
        } catch (final DrawerInvariantViolationException | IllegalArgumentException exception) {
            message(player, "general.configuration-error");
            this.context.logger().warning("Rejected drawer withdrawal at " + readyBarrel.getLocation() + ": " + exception.getMessage());
        }
    }

    private void armBlockWithdrawal(final Player player, final Barrel barrel, final boolean bulk) {
        this.pendingBlockWithdrawals.put(
                player.getUniqueId(),
                new PendingBlockWithdrawal(barrel.getLocation(), bulk, System.currentTimeMillis() + 5_000L)
        );
    }

    /** Performs one synchronous region-owned recovery before treating a drawer as invalid. */
    private ReadyDrawer readyDrawer(final Barrel original) {
        Barrel current = original;
        for (int attempt = 0; attempt < 2; attempt++) {
            final DrawerStateReadResult read = this.context.repository().read(current);
            if (read instanceof DrawerStateReadResult.Valid valid) {
                final DrawerBlockAccess.PhysicalStock stock = this.context.blocks().inspect(current, valid.state());
                if (stock.matchesTemplate() && !valid.state().hasPendingProxyJournal()) {
                    return new ReadyDrawer(current, valid.state(), stock);
                }
            }
            if (attempt == 0) {
                this.hoppers.recoverAndRebalance(current);
                current = this.context.blocks().barrel(original.getBlock()).orElse(null);
                if (current == null || !this.context.repository().isDrawer(current)) {
                    return null;
                }
            }
        }
        return null;
    }

    private boolean isBulkDepositArmed(final Player player, final Barrel barrel, final DrawerState state, final ItemStack held) {
        final BulkDepositArm arm = this.bulkDepositArms.get(player.getUniqueId());
        return arm != null
                && arm.expiresAtMillis() >= System.currentTimeMillis()
                && arm.matches(barrel, state)
                && (!isUsableItem(held) || state.matchesTemplate(held));
    }

    private void armBulkDeposit(final Player player, final Barrel barrel, final DrawerState state) {
        if (!state.hasTemplate()) {
            return;
        }
        this.bulkDepositArms.put(
                player.getUniqueId(),
                new BulkDepositArm(barrel.getLocation(), state.requireTemplate(), System.currentTimeMillis()
                        + this.context.settings().interaction().bulkDepositWindowTicks() * 50L)
        );
    }

    private static boolean isFacingFace(final Barrel barrel, final BlockFace clickedFace) {
        return barrel.getBlockData() instanceof Directional directional && directional.getFacing() == clickedFace;
    }

    private boolean canUse(final Player player) {
        return player.hasPermission("devstoragedrawer.use");
    }

    private boolean canWithdrawWith(final Barrel barrel, final ItemStack held) {
        if (!isUsableItem(held)) {
            return true;
        }
        final DrawerStateReadResult read = this.context.repository().read(barrel);
        return read instanceof DrawerStateReadResult.Valid valid && valid.state().matchesTemplate(held);
    }

    private void deny(final PlayerInteractEvent event, final String messageKey) {
        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        if (messageKey != null) {
            message(event.getPlayer(), messageKey);
        }
    }

    private void reportRejected(final Player player, final DrawerStorageTransaction transaction) {
        switch (transaction.status()) {
            case EMPTY -> message(player, "drawer.empty");
            case FULL -> message(player, "drawer.full");
            case ITEM_MISMATCH -> message(player, "drawer.wrong-item");
            default -> message(player, "general.configuration-error");
        }
    }

    private void message(final Player player, final String key) {
        player.sendMessage(this.context.messages().component(key));
    }

    private void message(final Player player, final String key, final Map<String, ?> placeholders) {
        player.sendMessage(this.context.messages().component(key, placeholders));
    }

    private void messageInvalidDrawer(final Player player, final Barrel barrel) {
        player.sendMessage(this.context.messages().component("drawer.invalid", Map.of("reason", invalidReason(barrel))));
    }

    private String invalidReason(final Barrel barrel) {
        final DrawerStateReadResult read = this.context.repository().read(barrel);
        if (read instanceof DrawerStateReadResult.Absent) {
            return "missing persisted drawer state";
        }
        if (read instanceof DrawerStateReadResult.Corrupt corrupt) {
            return shorten("corrupt persisted state: " + corrupt.reason());
        }
        final DrawerState state = ((DrawerStateReadResult.Valid) read).state();
        final DrawerBlockAccess.PhysicalStock stock = this.context.blocks().inspect(barrel, state);
        if (!stock.matchesTemplate()) {
            return "barrel mirror contains an item different from the drawer template";
        }
        if (state.hasPendingProxyJournal()) {
            return "a crash-recovery transfer is still pending";
        }
        return "drawer state changed while recovery was running";
    }

    private static String shorten(final String reason) {
        return reason.length() <= 160 ? reason : reason.substring(0, 157) + "...";
    }

    private static ItemStack heldItem(final Player player, final EquipmentSlot hand) {
        return hand == EquipmentSlot.OFF_HAND ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
    }

    private static boolean isUsableItem(final ItemStack item) {
        return item != null && item.getType() != Material.AIR && item.getAmount() > 0;
    }

    private static void consumeHeld(final Player player, final EquipmentSlot hand, final int amount) {
        final ItemStack item = heldItem(player, hand);
        item.setAmount(item.getAmount() - amount);
        if (item.getAmount() <= 0) {
            if (hand == EquipmentSlot.OFF_HAND) {
                player.getInventory().setItemInOffHand(null);
            } else {
                player.getInventory().setItemInMainHand(null);
            }
        }
    }

    private static void restoreHeld(final Player player, final EquipmentSlot hand, final ItemStack original, final int amount) {
        final ItemStack current = heldItem(player, hand);
        if (current == null || current.getType() == Material.AIR) {
            final ItemStack replacement = original.clone();
            replacement.setAmount(amount);
            if (hand == EquipmentSlot.OFF_HAND) {
                player.getInventory().setItemInOffHand(replacement);
            } else {
                player.getInventory().setItemInMainHand(replacement);
            }
        } else {
            current.setAmount(current.getAmount() + amount);
        }
    }

    private static long availableSpace(final PlayerInventory inventory, final ItemStack template) {
        final int max = Math.min(template.getMaxStackSize(), inventory.getMaxStackSize());
        long free = 0L;
        for (final ItemStack stack : inventory.getStorageContents()) {
            if (stack == null || stack.getType() == Material.AIR) {
                free += max;
            } else if (stack.isSimilar(template)) {
                free += Math.max(0, max - stack.getAmount());
            }
        }
        return free;
    }

    private static void restoreInventorySlots(final PlayerInventory inventory, final Map<Integer, ItemStack> originals) {
        for (final Map.Entry<Integer, ItemStack> original : originals.entrySet()) {
            inventory.setItem(original.getKey(), original.getValue().clone());
        }
    }

    private record ReadyDrawer(Barrel barrel, DrawerState state, DrawerBlockAccess.PhysicalStock stock) {
    }

    private record BulkDepositArm(Location location, ItemStack template, long expiresAtMillis) {
        BulkDepositArm {
            location = location.clone();
            template = template.clone();
        }

        boolean matches(final Barrel barrel, final DrawerState state) {
            return this.location.getWorld() != null
                    && this.location.getWorld().equals(barrel.getWorld())
                    && this.location.getBlockX() == barrel.getX()
                    && this.location.getBlockY() == barrel.getY()
                    && this.location.getBlockZ() == barrel.getZ()
                    && state.matchesTemplate(this.template);
        }
    }

    private record PendingBlockWithdrawal(Location location, boolean bulk, long expiresAtMillis) {
        private PendingBlockWithdrawal {
            location = location.clone();
        }

        private boolean matches(final Block block) {
            return location.getWorld() == block.getWorld()
                    && location.getBlockX() == block.getX()
                    && location.getBlockY() == block.getY()
                    && location.getBlockZ() == block.getZ();
        }

        private boolean expired() {
            return expiresAtMillis < System.currentTimeMillis();
        }
    }
}
