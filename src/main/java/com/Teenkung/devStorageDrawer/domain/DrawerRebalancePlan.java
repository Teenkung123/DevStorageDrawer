package com.Teenkung.devStorageDrawer.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Two-phase plan for rewriting a barrel's proxy slots. Persist {@link #journaledState()} first,
 * change the real inventory to {@link #physicalTarget()}, then persist {@link #committedState()}.
 */
public final class DrawerRebalancePlan {

    private final DrawerState journaledState;
    private final DrawerState committedState;
    private final long physicalBefore;
    private final long physicalTarget;
    private final DrawerProxyJournal journal;

    private DrawerRebalancePlan(
            final DrawerState journaledState,
            final DrawerState committedState,
            final long physicalBefore,
            final long physicalTarget,
            final DrawerProxyJournal journal
    ) {
        this.journaledState = Objects.requireNonNull(journaledState, "journaledState");
        this.committedState = Objects.requireNonNull(committedState, "committedState");
        DrawerCapacity.requireNonNegative(physicalBefore, "rebalance physical-before count");
        DrawerCapacity.requireNonNegative(physicalTarget, "rebalance physical-target count");
        this.physicalBefore = physicalBefore;
        this.physicalTarget = physicalTarget;
        this.journal = journal;

        this.journaledState.totalForPhysical(physicalBefore);
        this.committedState.totalForPhysical(physicalTarget);
        if (journal == null) {
            if (physicalBefore != physicalTarget || journaledState.hasPendingProxyJournal() || committedState.hasPendingProxyJournal()) {
                throw new DrawerInvariantViolationException("A changing rebalance requires a durable journal");
            }
        } else {
            if (!journaledState.hasPendingProxyJournal() || committedState.hasPendingProxyJournal()) {
                throw new DrawerInvariantViolationException("Journal state sequence is invalid");
            }
            if (journal.totalBefore() != journaledState.totalForPhysical(physicalBefore)
                    || journal.totalAfter() != committedState.totalForPhysical(physicalTarget)) {
                throw new DrawerInvariantViolationException("Journal total does not match rebalance states");
            }
        }
    }

    static DrawerRebalancePlan noChange(final DrawerState state, final long physicalCount) {
        return new DrawerRebalancePlan(state, state, physicalCount, physicalCount, null);
    }

    static DrawerRebalancePlan changing(
            final DrawerState journaledState,
            final DrawerState committedState,
            final long physicalBefore,
            final long physicalTarget,
            final DrawerProxyJournal journal
    ) {
        return new DrawerRebalancePlan(journaledState, committedState, physicalBefore, physicalTarget, journal);
    }

    /** Creates a crash-safe journal for a layout-only rewrite whose physical count is unchanged. */
    public static DrawerRebalancePlan repack(
            final DrawerState state,
            final long physicalCount,
            final long createdAtEpochMillis
    ) {
        Objects.requireNonNull(state, "state");
        if (!state.usesReservedMirror()) {
            throw new DrawerInvariantViolationException("Only reserved-mirror drawers can be repacked");
        }
        final long total = state.totalForPhysical(physicalCount);
        final DrawerProxyJournal journal = new DrawerProxyJournal(
                UUID.randomUUID(), total, total, physicalCount, physicalCount, createdAtEpochMillis
        );
        final DrawerState journaled = state.withProxyJournal(journal);
        final DrawerState committed = state.withStoredTotalAndExpectedMirrorCount(total, physicalCount)
                .withoutProxyJournal();
        return changing(journaled, committed, physicalCount, physicalCount, journal);
    }

    public boolean requiresRebalance() {
        return this.journal != null;
    }

    public DrawerState journaledState() {
        return this.journaledState;
    }

    public DrawerState committedState() {
        return this.committedState;
    }

    public long physicalBefore() {
        return this.physicalBefore;
    }

    public long physicalTarget() {
        return this.physicalTarget;
    }

    public Optional<DrawerProxyJournal> journal() {
        return Optional.ofNullable(this.journal);
    }
}
