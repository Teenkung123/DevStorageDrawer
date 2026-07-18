package com.teenkung.devstoragedrawer.block;

import com.teenkung.devstoragedrawer.api.DrawerChangeCause;
import com.teenkung.devstoragedrawer.config.DrawerTierDefinition;
import com.teenkung.devstoragedrawer.domain.DrawerInvariantViolationException;
import com.teenkung.devstoragedrawer.domain.DrawerItemIdentity;
import com.teenkung.devstoragedrawer.domain.DrawerJournalKind;
import com.teenkung.devstoragedrawer.domain.DrawerJournalReconciliation;
import com.teenkung.devstoragedrawer.domain.DrawerState;
import com.teenkung.devstoragedrawer.hopper.DrawerHopperBridge;
import com.teenkung.devstoragedrawer.persistence.DrawerStateReadResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;

/** Placement, safe breaking, explosion/piston protection, and loaded-barrel repair. */
public final class DrawerBlockListener implements Listener {

    private final DrawerRuntimeContext context;
    private final DrawerHopperBridge hoppers;

    public DrawerBlockListener(final DrawerRuntimeContext context, final DrawerHopperBridge hoppers) {
        this.context = Objects.requireNonNull(context, "context");
        this.hoppers = Objects.requireNonNull(hoppers, "hoppers");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(final BlockPlaceEvent event) {
        final Barrel barrel = this.context.blocks().barrel(event.getBlockPlaced()).orElse(null);
        if (barrel == null) {
            return;
        }
        final DrawerTierDefinition tier = this.context.blocks().tierForPlacedItem(event.getItemInHand()).orElse(null);
        if (tier == null) {
            return;
        }
        if (tier.tier().optionalPlacementPermission().filter(permission -> !event.getPlayer().hasPermission(permission)).isPresent()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(this.context.messages().component("general.no-permission"));
            return;
        }
        final DrawerState state = DrawerState.empty(tier.tier().id(), tier.tier().stackCapacity());
        if (!this.context.repository().save(barrel, state)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(this.context.messages().component("general.configuration-error"));
            return;
        }
        this.context.notifyCommitted(barrel, state, 0L, DrawerChangeCause.PLACED);
        this.hoppers.watch(barrel);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent event) {
        final Barrel barrel = this.context.blocks().barrel(event.getBlock()).orElse(null);
        if (barrel == null || !this.context.repository().isDrawer(barrel)) {
            return;
        }
        // A queued hopper pass must not mutate the tile entity while the break flow removes it.
        this.hoppers.unwatch(barrel);
        final DrawerStateReadResult read = this.context.repository().read(barrel);
        if (!(read instanceof DrawerStateReadResult.Valid valid)) {
            allowFallbackBreak(event, barrel, "its persisted drawer state is invalid");
            return;
        }
        DrawerState state = valid.state();
        final DrawerBlockAccess.PhysicalStock stock = this.context.blocks().inspect(barrel, state);
        if (!stock.matchesTemplate()) {
            allowFallbackBreak(event, barrel, "its proxy inventory does not match the drawer item");
            return;
        }
        if (state.hasPendingProxyJournal()) {
            if (state.proxyJournal().filter(journal -> journal.kind() == DrawerJournalKind.PLAYER_WITHDRAWAL).isPresent()) {
                allowFallbackBreak(event, barrel, "a player withdrawal is still being recovered");
                return;
            }
            try {
                final DrawerJournalReconciliation recovery = this.context.storage().reconcile(state, stock.count());
                if (recovery.requiresManualRecovery()) {
                    allowFallbackBreak(event, barrel, "its hopper recovery journal has an impossible physical item count");
                    return;
                }
                state = recovery.state();
            } catch (final DrawerInvariantViolationException exception) {
                allowFallbackBreak(event, barrel, "its hopper recovery journal cannot be resolved: " + exception.getMessage());
                return;
            }
        }
        final DrawerTierDefinition tier = this.context.tier(state.tierId()).orElse(null);
        if (tier == null) {
            allowFallbackBreak(event, barrel, "its configured tier no longer exists");
            return;
        }
        final long total;
        try {
            total = state.totalForPhysical(stock.count());
        } catch (final DrawerInvariantViolationException exception) {
            allowFallbackBreak(event, barrel, "its stored item total is invalid");
            return;
        }
        if (total > 0L && (!this.context.settings().parcels().enabled() || !this.context.settings().parcels().dropOnBreak())) {
            denyUnsafeBreak(event, barrel, "contents parcels are disabled");
            return;
        }

        // A valid drawer owns its break completely. Leaving a non-empty barrel to vanilla exposes
        // the physical proxy inventory to container/drop listeners and can cause a stale tile
        // entity update to be sent back to the client.
        event.setCancelled(true);
        event.setDropItems(false);
        event.setExpToDrop(0);
        // Do not update the barrel PDC during BlockBreakEvent; cleanup only removes entities.
        this.context.removeRendersAt(barrel);
        final Location blockLocation = barrel.getLocation();
        final Location dropLocation = blockLocation.clone().add(0.5D, 0.5D, 0.5D);
        barrel.getInventory().clear();
        barrel.getBlock().setType(Material.AIR, false);
        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            barrel.getWorld().dropItemNaturally(dropLocation, tier.createItem());
            if (total > 0L) {
                this.context.parcelBreakHandler().dropContents(event.getPlayer(), dropLocation, state.requireTemplate(), total);
            }
        }
        resyncBrokenBlockForPlayer(event.getPlayer(), blockLocation);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplosion(final BlockExplodeEvent event) {
        if (!this.context.settings().redstone().protectFromExplosions()) {
            return;
        }
        event.blockList().removeIf(this.context.blocks()::isDrawer);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplosion(final EntityExplodeEvent event) {
        if (!this.context.settings().redstone().protectFromExplosions()) {
            return;
        }
        event.blockList().removeIf(this.context.blocks()::isDrawer);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(final BlockPistonExtendEvent event) {
        if (this.context.settings().redstone().preventPistonMovement()
                && event.getBlocks().stream().anyMatch(this.context.blocks()::isDrawer)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(final BlockPistonRetractEvent event) {
        if (this.context.settings().redstone().preventPistonMovement()
                && event.getBlocks().stream().anyMatch(this.context.blocks()::isDrawer)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onChunkLoad(final ChunkLoadEvent event) {
        if (!this.context.settings().visuals().repairOnChunkLoad()
                && !this.context.settings().automation().recoverJournalOnLoad()) {
            return;
        }
        queueChunkRepair(event.getChunk());
    }

    @EventHandler
    public void onChunkUnload(final ChunkUnloadEvent event) {
        this.hoppers.unwatch(event.getChunk());
    }

    /** Queues every currently loaded chunk from the global scheduler, then inspects each on its owning region. */
    public void repairLoadedDrawers() {
        this.context.execution().runGlobal(ignored -> {
            final List<ChunkKey> loadedChunks = new ArrayList<>();
            for (final World world : Bukkit.getWorlds()) {
                for (final Chunk chunk : world.getLoadedChunks()) {
                    loadedChunks.add(new ChunkKey(world, chunk.getX(), chunk.getZ()));
                }
            }
            for (final ChunkKey chunk : loadedChunks) {
                final Location location = new Location(chunk.world(), chunk.x() << 4, chunk.world().getMinHeight(), chunk.z() << 4);
                this.context.execution().executeAt(location, () -> {
                    if (chunk.world().isChunkLoaded(chunk.x(), chunk.z())) {
                        queueChunkRepair(chunk.world().getChunkAt(chunk.x(), chunk.z()));
                    }
                });
            }
        });
    }

    private void queueChunkRepair(final Chunk chunk) {
        for (final BlockState state : chunk.getTileEntities()) {
            if (!(state instanceof Barrel barrel) || !this.context.repository().isDrawer(barrel)) {
                continue;
            }
            final Location location = barrel.getLocation();
            this.context.execution().executeAt(location, () -> {
                final Barrel live = this.context.blocks().barrel(location.getBlock()).orElse(null);
                if (live != null) {
                    this.hoppers.watch(live);
                    this.hoppers.recoverAndRebalance(live);
                }
            });
        }
    }

    /**
     * Corrupt or mismatched drawers release only their physical inventory. When their raw tier id
     * is still configured, keep the tiered drawer item so placing the recovered drop never turns
     * it into an ordinary barrel. Hidden stock is intentionally not recreated because it cannot
     * be proven from invalid state.
     */
    private void allowFallbackBreak(final BlockBreakEvent event, final Barrel barrel, final String reason) {
        this.hoppers.unwatch(barrel);
        final DrawerTierDefinition tier = this.context.blocks().tierForDrawer(barrel).orElse(null);
        if (tier == null) {
            this.context.removeRendersAt(barrel);
            this.context.logger().warning("Allowing vanilla break for drawer at " + barrel.getLocation() + " because " + reason
                    + "; its tier is no longer configured, so only its physical barrel inventory can be recovered safely");
            return;
        }

        event.setCancelled(true);
        event.setDropItems(false);
        event.setExpToDrop(0);
        this.context.removeRendersAt(barrel);
        final Location blockLocation = barrel.getLocation();
        final Location dropLocation = blockLocation.clone().add(0.5D, 0.5D, 0.5D);
        final List<ItemStack> physicalContents = new ArrayList<>();
        for (final ItemStack item : barrel.getInventory().getContents()) {
            if (DrawerItemIdentity.isStorageCandidate(item)) {
                physicalContents.add(item.clone());
            }
        }
        barrel.getInventory().clear();
        barrel.getBlock().setType(Material.AIR, false);
        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            barrel.getWorld().dropItemNaturally(dropLocation, tier.createItem());
            for (final ItemStack item : physicalContents) {
                barrel.getWorld().dropItemNaturally(dropLocation, item);
            }
        }
        this.context.logger().warning("Recovered drawer at " + blockLocation + " because " + reason
                + "; returned its tiered drawer item and physical inventory only");
        resyncBrokenBlockForPlayer(event.getPlayer(), blockLocation);
    }

    /** Prevents a break that would otherwise discard hidden contents when parcel recovery is disabled. */
    private void denyUnsafeBreak(final BlockBreakEvent event, final Barrel barrel, final String reason) {
        event.setCancelled(true);
        this.hoppers.watch(barrel);
        this.context.logger().warning("Cancelled break for drawer at " + barrel.getLocation() + " because " + reason
                + "; enable parcels.drop-on-break before breaking a non-empty drawer");
    }

    /** Re-sends the authoritative post-break block state to clear stale client tile entities. */
    private void resyncBrokenBlockForPlayer(final Player player, final Location drawerLocation) {
        final Location location = drawerLocation.clone();
        this.context.execution().runLaterAt(location, 1L, ignored -> {
            final BlockData actual = location.getBlock().getBlockData().clone();
            this.context.execution().runOnEntity(
                    player,
                    ignoredTask -> player.sendBlockChange(location, actual),
                    () -> { }
            );
        });
    }

    private record ChunkKey(World world, int x, int z) {
    }
}
