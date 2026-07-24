package com.Teenkung.devStorageDrawer.api;

import java.util.List;
import java.util.Objects;

/** Status-bearing result for {@link DevStorageDrawerApi#tiers()}. */
public record DrawerTierQueryResult(DrawerTierQueryStatus status, List<DrawerTierInfo> tiers) {

    public DrawerTierQueryResult {
        status = Objects.requireNonNull(status, "status");
        tiers = List.copyOf(Objects.requireNonNull(tiers, "tiers"));
        if (status != DrawerTierQueryStatus.FOUND && !tiers.isEmpty()) {
            throw new IllegalArgumentException("Only a successful tier query may contain tier metadata");
        }
    }

    public static DrawerTierQueryResult found(final List<DrawerTierInfo> tiers) {
        return new DrawerTierQueryResult(DrawerTierQueryStatus.FOUND, tiers);
    }

    public static DrawerTierQueryResult of(final DrawerTierQueryStatus status) {
        return new DrawerTierQueryResult(status, List.of());
    }
}
