package com.Teenkung.devStorageDrawer.api;

import java.util.Objects;
import java.util.Optional;

/** Result of {@link DevStorageDrawerApi#query(DrawerLocation)}. */
public record DrawerQueryResult(DrawerQueryStatus status, Optional<DrawerSnapshot> snapshot) {

    public DrawerQueryResult {
        status = Objects.requireNonNull(status, "status");
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        if ((status == DrawerQueryStatus.FOUND) != snapshot.isPresent()) {
            throw new IllegalArgumentException("Only a found query result may contain a snapshot");
        }
    }

    public static DrawerQueryResult found(final DrawerSnapshot snapshot) {
        return new DrawerQueryResult(DrawerQueryStatus.FOUND, Optional.of(Objects.requireNonNull(snapshot, "snapshot")));
    }

    public static DrawerQueryResult of(final DrawerQueryStatus status) {
        return new DrawerQueryResult(status, Optional.empty());
    }
}
