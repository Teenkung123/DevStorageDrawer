package com.Teenkung.devStorageDrawer.scheduler;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/** Region-aware scheduler boundary that runs on both Paper and Folia. */
public final class FoliaExecution {
    private final Plugin plugin;

    public FoliaExecution(final Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public void executeAt(final Location location, final Runnable action) {
        final Location checked = checkedLocation(location);
        if (!invokeRegion("execute", new Class<?>[]{Plugin.class, Location.class, Runnable.class},
                plugin, checked, Objects.requireNonNull(action, "action"))) {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }

    public void runLaterAt(final Location location, final long delayTicks, final Consumer<Object> action) {
        if (delayTicks < 1) {
            throw new IllegalArgumentException("delayTicks must be positive");
        }
        final Location checked = checkedLocation(location);
        if (!invokeRegion("runDelayed", new Class<?>[]{Plugin.class, Location.class, Consumer.class, long.class},
                plugin, checked, Objects.requireNonNull(action, "action"), delayTicks)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> action.accept(null), delayTicks);
        }
    }

    public void runGlobal(final Consumer<Object> action) {
        if (!invokeScheduler("getGlobalRegionScheduler", "run", new Class<?>[]{Plugin.class, Consumer.class},
                plugin, Objects.requireNonNull(action, "action"))) {
            Bukkit.getScheduler().runTask(plugin, () -> action.accept(null));
        }
    }

    public void runAsync(final Consumer<Object> action) {
        if (!invokeScheduler("getAsyncScheduler", "runNow", new Class<?>[]{Plugin.class, Consumer.class},
                plugin, Objects.requireNonNull(action, "action"))) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> action.accept(null));
        }
    }

    public void runOnEntity(final Entity entity, final Consumer<Object> action, final Runnable retired) {
        final Entity checked = Objects.requireNonNull(entity, "entity");
        final Consumer<Object> checkedAction = Objects.requireNonNull(action, "action");
        final Runnable checkedRetired = Objects.requireNonNull(retired, "retired");
        try {
            final Object scheduler = checked.getClass().getMethod("getScheduler").invoke(checked);
            scheduler.getClass().getMethod("run", Plugin.class, Consumer.class, Runnable.class)
                    .invoke(scheduler, plugin, checkedAction, checkedRetired);
        } catch (ReflectiveOperationException ignored) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (checked.isValid()) {
                    checkedAction.accept(null);
                } else {
                    checkedRetired.run();
                }
            });
        }
    }

    public boolean isOwnedByCurrentRegion(final Location location) {
        try {
            return (boolean) Bukkit.class.getMethod("isOwnedByCurrentRegion", Location.class)
                    .invoke(null, checkedLocation(location));
        } catch (ReflectiveOperationException ignored) {
            return true;
        }
    }

    private static boolean invokeRegion(final String method, final Class<?>[] types, final Object... args) {
        return invokeScheduler("getRegionScheduler", method, types, args);
    }

    private static boolean invokeScheduler(final String accessor, final String method,
                                           final Class<?>[] types, final Object... args) {
        try {
            final Object scheduler = Bukkit.class.getMethod(accessor).invoke(null);
            scheduler.getClass().getMethod(method, types).invoke(scheduler, args);
            return true;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static Location checkedLocation(final Location location) {
        final Location checked = Objects.requireNonNull(location, "location");
        if (checked.getWorld() == null) {
            throw new IllegalArgumentException("location must have a world");
        }
        return checked;
    }
}
