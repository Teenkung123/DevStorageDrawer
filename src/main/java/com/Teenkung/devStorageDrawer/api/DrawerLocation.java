package com.teenkung.devstoragedrawer.api;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.Location;

/** Immutable block coordinates that may safely outlive a Bukkit region callback. */
public record DrawerLocation(UUID worldId, int blockX, int blockY, int blockZ) {

    public DrawerLocation {
        Objects.requireNonNull(worldId, "worldId");
    }

    /** Captures block coordinates from a location currently valid on its owning region. */
    public static DrawerLocation from(final Location location) {
        final Location checked = Objects.requireNonNull(location, "location");
        if (checked.getWorld() == null) {
            throw new IllegalArgumentException("location must have a world");
        }
        return new DrawerLocation(
                checked.getWorld().getUID(),
                checked.getBlockX(),
                checked.getBlockY(),
                checked.getBlockZ()
        );
    }
}
