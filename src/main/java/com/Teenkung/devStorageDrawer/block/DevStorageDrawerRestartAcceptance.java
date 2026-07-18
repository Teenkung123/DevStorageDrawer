package com.teenkung.devstoragedrawer.block;

import com.teenkung.devstoragedrawer.config.DrawerTierDefinition;
import com.teenkung.devstoragedrawer.display.DrawerVisualRenderer;
import com.teenkung.devstoragedrawer.domain.DrawerDisplayLink;
import com.teenkung.devstoragedrawer.domain.DrawerRebalancePlan;
import com.teenkung.devstoragedrawer.domain.DrawerState;
import com.teenkung.devstoragedrawer.hopper.DrawerProxyInventory;
import com.teenkung.devstoragedrawer.persistence.DrawerStateReadResult;
import com.teenkung.devstoragedrawer.persistence.DrawerStateRepository;
import java.util.Comparator;
import java.util.Locale;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Two-boot development probe for persistence of a partially applied proxy journal. */
public final class DevStorageDrawerRestartAcceptance {

    public static final String PHASE_PROPERTY = "devstoragedrawer.restartAcceptance";
    public static final String PREPARED_MARKER = "DEVSTORAGEDRAWER_RESTART:PREPARED";
    public static final String PASS_MARKER = "DEVSTORAGEDRAWER_RESTART:PASS";
    public static final String FAIL_MARKER = "DEVSTORAGEDRAWER_RESTART:FAIL";
    private static final long EXPECTED_TOTAL = 200L;

    private final JavaPlugin plugin;
    private final DrawerRuntimeContext context;
    private final DrawerStateRepository repository;
    private final DrawerVisualRenderer visuals;
    private Location location;
    private Chunk chunk;

    public DevStorageDrawerRestartAcceptance(
            final JavaPlugin plugin,
            final DrawerRuntime runtime,
            final DrawerStateRepository repository,
            final DrawerVisualRenderer visuals
    ) {
        this.plugin = plugin;
        this.context = runtime.acceptanceContext();
        this.repository = repository;
        this.visuals = visuals;
    }

    public void start(final String requestedPhase) {
        final String phase = requestedPhase.toLowerCase(Locale.ROOT);
        context.execution().runGlobal(task -> {
            final World world = Bukkit.getWorlds().stream().findFirst().orElseThrow();
            final Location spawn = world.getSpawnLocation();
            final int y = Math.min(world.getMaxHeight() - 8, Math.max(world.getMinHeight() + 8, spawn.getBlockY() + 8));
            location = new Location(world, spawn.getBlockX() + 4, y, spawn.getBlockZ() + 4);
            context.execution().executeAt(location, () -> runPhase(phase));
        });
    }

    private void runPhase(final String phase) {
        try {
            chunk = location.getChunk();
            chunk.addPluginChunkTicket(plugin);
            switch (phase) {
                case "prepare" -> prepareInterruptedRewrite();
                case "verify" -> context.execution().runLaterAt(location, 100L, task -> verifyRecoveredRewrite());
                default -> throw new IllegalArgumentException("Restart acceptance phase must be prepare or verify");
            }
        } catch (final Throwable throwable) {
            fail(throwable);
        }
    }

    private void prepareInterruptedRewrite() {
        final DrawerTierDefinition definition = context.settings().tiers().values().stream()
                .min(Comparator.comparingLong(value -> value.tier().stackCapacity()))
                .orElseThrow();
        final ItemStack stone = new ItemStack(Material.STONE, 1);
        final long capacity = definition.tier().capacityFor(stone);
        if (capacity < EXPECTED_TOTAL) {
            throw new IllegalStateException("Acceptance tier cannot hold the restart test stock");
        }

        final Block block = location.getBlock();
        block.setType(Material.BARREL, false);
        final DrawerState state = DrawerState.restored(
                DrawerState.CURRENT_SCHEMA_VERSION,
                definition.tier().id(),
                stone,
                EXPECTED_TOTAL,
                capacity,
                null,
                DrawerDisplayLink.none()
        );
        final Barrel barrel = (Barrel) block.getState();
        barrel.getInventory().clear();
        require(repository.save(barrel, state), "Could not persist restart acceptance drawer");
        final DrawerRebalancePlan plan = context.storage().prepareRebalance(state, 0L, 26);
        require(plan.requiresRebalance(), "Restart acceptance did not produce a proxy journal");
        require(repository.save((Barrel) block.getState(), plan.journaledState()), "Could not persist prepared proxy journal");

        final long partialTarget = Math.max(1L, plan.physicalTarget() / 2L);
        require(
                DrawerProxyInventory.rewrite((Barrel) block.getState(), stone, partialTarget, 26),
                "Could not partially rewrite the physical mirror"
        );
        plugin.getLogger().info(PREPARED_MARKER + " total=" + EXPECTED_TOTAL + " physical=" + partialTarget);
        shutdownLater();
    }

    private void verifyRecoveredRewrite() {
        try {
            final Block block = location.getBlock();
            require(block.getState() instanceof Barrel, "Restart acceptance drawer was not persisted");
            final Barrel barrel = (Barrel) block.getState();
            final DrawerStateReadResult read = repository.read(barrel);
            require(read instanceof DrawerStateReadResult.Valid, "Restart acceptance drawer state is invalid");
            final DrawerState state = ((DrawerStateReadResult.Valid) read).state();
            final DrawerBlockAccess.PhysicalStock stock = context.blocks().inspect(barrel, state);
            require(stock.matchesTemplate(), "Recovered mirror no longer matches its item template");
            require(!state.hasPendingProxyJournal(), "Prepared proxy journal survived restart recovery");
            require(
                    state.totalForPhysical(stock.count()) == EXPECTED_TOTAL,
                    "Restart recovery changed total stock to " + state.totalForPhysical(stock.count())
            );
            visuals.remove(barrel, state);
            block.setType(Material.AIR, false);
            chunk.removePluginChunkTicket(plugin);
            plugin.getLogger().info(PASS_MARKER + " total=" + EXPECTED_TOTAL + " physical=" + stock.count());
            shutdownLater();
        } catch (final Throwable throwable) {
            fail(throwable);
        }
    }

    private void fail(final Throwable throwable) {
        plugin.getLogger().log(Level.SEVERE, FAIL_MARKER + " " + throwable.getMessage(), throwable);
        shutdownLater();
    }

    private void shutdownLater() {
        context.execution().runLaterAt(location, 20L, task -> context.execution().runGlobal(ignored -> Bukkit.shutdown()));
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
