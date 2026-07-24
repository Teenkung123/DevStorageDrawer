package com.Teenkung.devStorageDrawer.redstone;

import com.Teenkung.devStorageDrawer.block.DrawerRuntimeContext;
import com.Teenkung.devStorageDrawer.domain.DrawerComparatorLevel;
import com.Teenkung.devStorageDrawer.domain.DrawerState;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.event.Listener;

/** Logical comparator output based on all hidden and physical stock, not merely the barrel mirror. */
public final class DrawerComparatorService implements Listener {

    private static final Set<BlockFace> HORIZONTAL_FACES = Set.of(
            BlockFace.NORTH,
            BlockFace.EAST,
            BlockFace.SOUTH,
            BlockFace.WEST
    );
    private final DrawerRuntimeContext context;
    private final Set<DrawerKey> queuedPulses = ConcurrentHashMap.newKeySet();

    public DrawerComparatorService(final DrawerRuntimeContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    public void onDrawerChanged(final DrawerRuntimeContext.DrawerMutation mutation) {
        if (!this.context.settings().redstone().logicalComparatorOutput()) {
            return;
        }
        final Location location = mutation.barrel().getLocation();
        final DrawerKey key = DrawerKey.of(location);
        if (!this.queuedPulses.add(key)) {
            return;
        }
        this.context.execution().runLaterAt(
                location,
                this.context.settings().redstone().comparatorRecalculateDelayTicks(),
                task -> {
                    this.queuedPulses.remove(key);
                    final Block block = location.getBlock();
                    if (this.context.blocks().isDrawer(block)) {
                        tickRearComparators(block);
                    }
                }
        );
    }

    private static void tickRearComparators(final Block drawer) {
        for (final BlockFace face : HORIZONTAL_FACES) {
            final Block comparator = drawer.getRelative(face);
            if (comparator.getType() != Material.COMPARATOR
                    || !(comparator.getBlockData() instanceof Directional directional)
                    || !comparator.getRelative(directional.getFacing()).equals(drawer)) {
                continue;
            }
            // Paper exposes the normal block tick through public API. The physical mirror is
            // shaped to the logical fullness, so vanilla performs the analogue calculation.
            comparator.tick();
        }
    }

    /** Vanilla-style fullness mapping: zero is 0, every non-empty drawer is at least 1, full is 15. */
    public static int level(final DrawerState state, final long physicalProxyCount) {
        return level(state.totalForPhysical(physicalProxyCount), state.capacitySnapshot());
    }

    /** Pure count mapping retained for unit tests and external diagnostics. */
    public static int level(final long total, final long capacity) {
        return DrawerComparatorLevel.level(total, capacity);
    }

    private record DrawerKey(UUID worldId, int x, int y, int z) {
        static DrawerKey of(final Location location) {
            return new DrawerKey(Objects.requireNonNull(location.getWorld(), "world").getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }
    }
}
