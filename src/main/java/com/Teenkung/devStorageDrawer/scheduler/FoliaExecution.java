package com.teenkung.devstoragedrawer.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Objects;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Small boundary around Paper's region-aware schedulers.
 *
 * <p>World and block work must use {@link #executeAt(Location, Runnable)} or
 * {@link #runLaterAt(Location, long, Consumer)}. Entity mutations must use
 * {@link #runOnEntity(Entity, Consumer, Runnable)}. This class intentionally
 * does not expose Bukkit's legacy scheduler.</p>
 */
public final class FoliaExecution {
    private final Plugin plugin;

    public FoliaExecution(final Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public void executeAt(final Location location, final Runnable action) {
        Bukkit.getRegionScheduler().execute(plugin, checkedLocation(location), Objects.requireNonNull(action, "action"));
    }

    public void runLaterAt(
            final Location location,
            final long delayTicks,
            final Consumer<ScheduledTask> action
    ) {
        if (delayTicks < 1) {
            throw new IllegalArgumentException("delayTicks must be positive");
        }
        Bukkit.getRegionScheduler().runDelayed(
                plugin,
                checkedLocation(location),
                Objects.requireNonNull(action, "action"),
                delayTicks
        );
    }

    public void runGlobal(final Consumer<ScheduledTask> action) {
        Bukkit.getGlobalRegionScheduler().run(plugin, Objects.requireNonNull(action, "action"));
    }

    public void runAsync(final Consumer<ScheduledTask> action) {
        Bukkit.getAsyncScheduler().runNow(plugin, Objects.requireNonNull(action, "action"));
    }

    public void runOnEntity(
            final Entity entity,
            final Consumer<ScheduledTask> action,
            final Runnable retired
    ) {
        Objects.requireNonNull(entity, "entity").getScheduler().run(
                plugin,
                Objects.requireNonNull(action, "action"),
                Objects.requireNonNull(retired, "retired")
        );
    }

    private static Location checkedLocation(final Location location) {
        final Location checked = Objects.requireNonNull(location, "location");
        if (checked.getWorld() == null) {
            throw new IllegalArgumentException("location must have a world");
        }
        return checked;
    }
}
