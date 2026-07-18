package com.teenkung.devstoragedrawer.api;

import java.util.Objects;
import java.util.Optional;

/** Public metadata for one configured drawer tier. */
public record DrawerTierInfo(String id, long stackCapacity, Optional<String> placementPermission) {

    public DrawerTierInfo {
        if (id == null || id.isBlank() || stackCapacity <= 0L) {
            throw new IllegalArgumentException("Drawer tier metadata is invalid");
        }
        placementPermission = Objects.requireNonNull(placementPermission, "placementPermission");
    }
}
