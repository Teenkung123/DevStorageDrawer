package com.Teenkung.devStorageDrawer.domain;

import java.util.Optional;
import java.util.regex.Pattern;
import org.bukkit.inventory.ItemStack;

/** Immutable configured definition for one custom barrel tier. */
public record DrawerTier(String id, String displayName, long stackCapacity, String placementPermission) {

    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]*");

    public DrawerTier {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw new DrawerValidationException("Tier id must match " + ID_PATTERN.pattern());
        }
        if (displayName == null || displayName.isBlank()) {
            throw new DrawerValidationException("Tier " + id + " must have a display name");
        }
        if (stackCapacity <= 0L) {
            throw new DrawerValidationException("Tier " + id + " must hold at least one stack");
        }
        placementPermission = placementPermission == null || placementPermission.isBlank()
                ? null
                : placementPermission;
    }

    public DrawerTier(final String id, final String displayName, final long stackCapacity) {
        this(id, displayName, stackCapacity, null);
    }

    public long capacityFor(final ItemStack template) {
        return DrawerCapacity.fromStacks(this.stackCapacity, template);
    }

    public Optional<String> optionalPlacementPermission() {
        return Optional.ofNullable(this.placementPermission);
    }
}
