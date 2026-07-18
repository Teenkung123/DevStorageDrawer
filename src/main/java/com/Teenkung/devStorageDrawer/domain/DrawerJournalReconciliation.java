package com.teenkung.devstoragedrawer.domain;

import java.util.Objects;

/** Result of resolving a durable proxy-rebalance intent against the live barrel inventory. */
public record DrawerJournalReconciliation(Resolution resolution, DrawerState state, long observedPhysicalCount) {

    public DrawerJournalReconciliation {
        Objects.requireNonNull(resolution, "resolution");
        Objects.requireNonNull(state, "state");
        DrawerCapacity.requireNonNegative(observedPhysicalCount, "observed physical proxy count");
    }

    public boolean requiresManualRecovery() {
        return this.resolution == Resolution.IMPOSSIBLE_PHYSICAL_COUNT;
    }

    public enum Resolution {
        NO_PENDING_JOURNAL,
        ROLLED_BACK,
        COMMITTED,
        PARTIALLY_APPLIED,
        IMPOSSIBLE_PHYSICAL_COUNT
    }
}
