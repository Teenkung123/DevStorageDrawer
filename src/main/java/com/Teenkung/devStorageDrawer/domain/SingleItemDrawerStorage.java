package com.Teenkung.devStorageDrawer.domain;

import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.bukkit.inventory.ItemStack;

/**
 * V1 storage rules. Its central invariant is that hidden stock plus accepted physical proxy stock
 * is always a non-negative value no greater than the drawer's persisted capacity snapshot.
 */
public final class SingleItemDrawerStorage implements DrawerStorageStrategy {

    private final LongSupplier clock;
    private volatile boolean logicalComparatorProxy;

    public SingleItemDrawerStorage() {
        this(false);
    }

    public SingleItemDrawerStorage(final boolean logicalComparatorProxy) {
        this(System::currentTimeMillis, logicalComparatorProxy);
    }

    SingleItemDrawerStorage(final LongSupplier clock) {
        this(clock, false);
    }

    SingleItemDrawerStorage(final LongSupplier clock, final boolean logicalComparatorProxy) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logicalComparatorProxy = logicalComparatorProxy;
    }

    public void setLogicalComparatorProxy(final boolean enabled) {
        this.logicalComparatorProxy = enabled;
    }

    @Override
    public DrawerStorageTransaction deposit(
            final DrawerState state,
            final long physicalProxyCount,
            final ItemStack offered,
            final long requestedAmount
    ) {
        requireReady(state, physicalProxyCount);
        if (requestedAmount <= 0L || !DrawerItemIdentity.isStorageCandidate(offered)) {
            return DrawerStorageTransaction.rejected(
                    DrawerOperation.PLAYER_DEPOSIT,
                    DrawerTransactionStatus.INVALID_REQUEST,
                    state,
                    physicalProxyCount,
                    nonNegativeRequest(requestedAmount)
            );
        }
        if (state.hasTemplate() && !state.matchesTemplate(offered)) {
            return DrawerStorageTransaction.rejected(
                    DrawerOperation.PLAYER_DEPOSIT,
                    DrawerTransactionStatus.ITEM_MISMATCH,
                    state,
                    physicalProxyCount,
                    requestedAmount
            );
        }

        final long offeredAmount = Math.min(requestedAmount, (long) offered.getAmount());
        final long accepted = Math.min(offeredAmount, state.availableCapacityForPhysical(physicalProxyCount));
        if (accepted == 0L) {
            return DrawerStorageTransaction.rejected(
                    DrawerOperation.PLAYER_DEPOSIT,
                    DrawerTransactionStatus.FULL,
                    state,
                    physicalProxyCount,
                    requestedAmount
            );
        }

        final DrawerState templated = state.hasTemplate() ? state : state.withTemplate(offered);
        final long nextTotal = DrawerCapacity.checkedAdd(
                state.totalForPhysical(physicalProxyCount),
                accepted,
                "Drawer stored total"
        );
        final DrawerState nextState = state.usesReservedMirror()
                ? templated.withStoredTotalAndExpectedMirrorCount(nextTotal, physicalProxyCount)
                : templated.withHiddenCount(nextTotal - physicalProxyCount);
        return DrawerStorageTransaction.applied(
                DrawerOperation.PLAYER_DEPOSIT,
                state,
                nextState,
                physicalProxyCount,
                physicalProxyCount,
                requestedAmount,
                accepted,
                offered
        );
    }

    @Override
    public DrawerStorageTransaction withdraw(
            final DrawerState state,
            final long physicalProxyCount,
            final long requestedAmount
    ) {
        requireReady(state, physicalProxyCount);
        if (requestedAmount <= 0L) {
            return DrawerStorageTransaction.rejected(
                    DrawerOperation.PLAYER_WITHDRAW,
                    DrawerTransactionStatus.INVALID_REQUEST,
                    state,
                    physicalProxyCount,
                    nonNegativeRequest(requestedAmount)
            );
        }
        final long total = state.totalForPhysical(physicalProxyCount);
        if (total == 0L) {
            return DrawerStorageTransaction.rejected(
                    DrawerOperation.PLAYER_WITHDRAW,
                    DrawerTransactionStatus.EMPTY,
                    state,
                    physicalProxyCount,
                    requestedAmount
            );
        }

        final long accepted = Math.min(requestedAmount, total);
        final long nextTotal = DrawerCapacity.checkedSubtract(total, accepted, "Drawer stored total");
        final long nextPhysical = state.usesReservedMirror()
                ? Math.min(physicalProxyCount, nextTotal)
                : DrawerCapacity.checkedSubtract(physicalProxyCount,
                        Math.max(0L, accepted - state.hiddenCount()), "Physical proxy count");
        DrawerState nextState = state.usesReservedMirror()
                ? state.withStoredTotalAndExpectedMirrorCount(nextTotal, nextPhysical)
                : state.withHiddenCount(nextTotal - nextPhysical);
        if (nextTotal == 0L && nextPhysical == 0L) {
            nextState = nextState.withoutTemplate();
        }

        return DrawerStorageTransaction.applied(
                DrawerOperation.PLAYER_WITHDRAW,
                state,
                nextState,
                physicalProxyCount,
                nextPhysical,
                requestedAmount,
                accepted,
                state.requireTemplate()
        );
    }

    @Override
    public DrawerStorageTransaction acceptProxyInsert(
            final DrawerState state,
            final long physicalProxyCount,
            final ItemStack offered,
            final long requestedAmount
    ) {
        requireReady(state, physicalProxyCount);
        if (requestedAmount <= 0L || !DrawerItemIdentity.isStorageCandidate(offered)) {
            return DrawerStorageTransaction.rejected(
                    DrawerOperation.PROXY_INSERT,
                    DrawerTransactionStatus.INVALID_REQUEST,
                    state,
                    physicalProxyCount,
                    nonNegativeRequest(requestedAmount)
            );
        }
        if (state.hasTemplate() && !state.matchesTemplate(offered)) {
            return DrawerStorageTransaction.rejected(
                    DrawerOperation.PROXY_INSERT,
                    DrawerTransactionStatus.ITEM_MISMATCH,
                    state,
                    physicalProxyCount,
                    requestedAmount
            );
        }

        final long offeredAmount = Math.min(requestedAmount, (long) offered.getAmount());
        final long accepted = Math.min(offeredAmount, state.availableCapacityForPhysical(physicalProxyCount));
        if (accepted == 0L) {
            return DrawerStorageTransaction.rejected(
                    DrawerOperation.PROXY_INSERT,
                    DrawerTransactionStatus.FULL,
                    state,
                    physicalProxyCount,
                    requestedAmount
            );
        }

        DrawerState nextState = state.hasTemplate() ? state : state.withTemplate(offered);
        final long nextPhysical = DrawerCapacity.checkedAdd(physicalProxyCount, accepted, "Physical proxy count");
        if (state.usesReservedMirror()) {
            nextState = nextState.withStoredTotalAndExpectedMirrorCount(
                    DrawerCapacity.checkedAdd(
                            state.totalForPhysical(physicalProxyCount),
                            accepted,
                            "Drawer stored total"
                    ),
                    nextPhysical
            );
        }
        return DrawerStorageTransaction.applied(
                DrawerOperation.PROXY_INSERT,
                state,
                nextState,
                physicalProxyCount,
                nextPhysical,
                requestedAmount,
                accepted,
                offered
        );
    }

    @Override
    public DrawerStorageTransaction permitProxyExtract(
            final DrawerState state,
            final long physicalProxyCount,
            final ItemStack offered,
            final long requestedAmount
    ) {
        requireReady(state, physicalProxyCount);
        if (requestedAmount <= 0L || !DrawerItemIdentity.isStorageCandidate(offered)) {
            return DrawerStorageTransaction.rejected(
                    DrawerOperation.PROXY_EXTRACT,
                    DrawerTransactionStatus.INVALID_REQUEST,
                    state,
                    physicalProxyCount,
                    nonNegativeRequest(requestedAmount)
            );
        }
        if (!state.hasTemplate() || !state.matchesTemplate(offered)) {
            return DrawerStorageTransaction.rejected(
                    DrawerOperation.PROXY_EXTRACT,
                    DrawerTransactionStatus.ITEM_MISMATCH,
                    state,
                    physicalProxyCount,
                    requestedAmount
            );
        }

        final long allowed = Math.min(Math.min(requestedAmount, (long) offered.getAmount()), physicalProxyCount);
        if (allowed == 0L) {
            return DrawerStorageTransaction.rejected(
                    DrawerOperation.PROXY_EXTRACT,
                    DrawerTransactionStatus.EMPTY,
                    state,
                    physicalProxyCount,
                    requestedAmount
            );
        }

        final long nextPhysical = DrawerCapacity.checkedSubtract(physicalProxyCount, allowed, "Physical proxy count");
        DrawerState nextState = state;
        if (state.usesReservedMirror()) {
            nextState = state.withStoredTotalAndExpectedMirrorCount(
                    DrawerCapacity.checkedSubtract(
                            state.totalForPhysical(physicalProxyCount),
                            allowed,
                            "Drawer stored total"
                    ),
                    nextPhysical
            );
        }
        if (nextPhysical == 0L && nextState.storedTotal() == 0L) {
            nextState = nextState.withoutTemplate();
        }
        return DrawerStorageTransaction.applied(
                DrawerOperation.PROXY_EXTRACT,
                state,
                nextState,
                physicalProxyCount,
                nextPhysical,
                requestedAmount,
                allowed,
                state.requireTemplate()
        );
    }

    @Override
    public DrawerRebalancePlan prepareRebalance(
            final DrawerState state,
            final long physicalProxyCount,
            final int outputProxySlots
    ) {
        requireReady(state, physicalProxyCount);
        if (outputProxySlots < 0) {
            throw new DrawerValidationException("Output proxy slot count cannot be negative");
        }

        final long total = state.totalForPhysical(physicalProxyCount);
        if (total == 0L) {
            return DrawerRebalancePlan.noChange(state, physicalProxyCount);
        }
        final long proxyCapacity;
        try {
            proxyCapacity = Math.multiplyExact(
                    (long) outputProxySlots,
                    (long) DrawerItemIdentity.nativeMaxStackSize(state.requireTemplate())
            );
        } catch (final ArithmeticException exception) {
            throw new DrawerValidationException("Configured output proxy capacity overflows a signed long", exception);
        }
        final int nativeMaxStackSize = DrawerItemIdentity.nativeMaxStackSize(state.requireTemplate());
        final long mirrorCapacity = DrawerCapacity.checkedAdd(
                proxyCapacity,
                nativeMaxStackSize,
                "Physical mirror capacity"
        );
        long physicalTarget = this.logicalComparatorProxy
                ? DrawerComparatorLevel.physicalProxyTarget(total, state.capacitySnapshot(), nativeMaxStackSize)
                : Math.min(total, proxyCapacity);
        if (state.usesReservedMirror() && this.logicalComparatorProxy) {
            // Keep one real native stack in the barrel. This supports any valid hopper batch up to
            // the item's stack limit; a smaller drawer exposes only its real remainder so an
            // oversized request is naturally clamped instead of inventing stock.
            physicalTarget = Math.max(physicalTarget,
                    Math.min(total, Math.min((long) nativeMaxStackSize, mirrorCapacity)));
        }
        // A full 27-slot stackable mirror prevents an eventless hopper from inserting a different
        // item into an empty slot. These are real reserved items, never synthetic filler.
        if (state.usesReservedMirror() && this.logicalComparatorProxy && outputProxySlots == 26
                && nativeMaxStackSize > 1 && total >= 27L) {
            physicalTarget = Math.max(physicalTarget, 27L);
        }
        if (state.usesReservedMirror()) {
            physicalTarget = DrawerCapacity.capacityProtectedMirrorTarget(
                    total,
                    state.capacitySnapshot(),
                    mirrorCapacity,
                    physicalTarget
            );
        }
        if (physicalTarget > mirrorCapacity) {
            throw new DrawerValidationException("Logical comparator proxy target exceeds the barrel mirror capacity");
        }
        if (physicalTarget == physicalProxyCount) {
            return DrawerRebalancePlan.noChange(state, physicalProxyCount);
        }

        final long hiddenTarget = total - physicalTarget;
        final DrawerProxyJournal journal = new DrawerProxyJournal(
                UUID.randomUUID(),
                total,
                total,
                physicalProxyCount,
                physicalTarget,
                this.clock.getAsLong()
        );
        final DrawerState journaled = state.withProxyJournal(journal);
        DrawerState committed = state.usesReservedMirror()
                ? state.withStoredTotalAndExpectedMirrorCount(total, physicalTarget).withoutProxyJournal()
                : state.withHiddenCount(hiddenTarget).withoutProxyJournal();
        if (total == 0L && physicalTarget == 0L) {
            committed = committed.withoutTemplate();
        }
        return DrawerRebalancePlan.changing(journaled, committed, physicalProxyCount, physicalTarget, journal);
    }

    @Override
    public DrawerJournalReconciliation reconcile(final DrawerState state, final long observedPhysicalProxyCount) {
        Objects.requireNonNull(state, "state");
        DrawerCapacity.requireNonNegative(observedPhysicalProxyCount, "observed physical proxy count");

        final var pendingJournal = state.proxyJournal();
        if (pendingJournal.isEmpty()) {
            if (state.usesReservedMirror()) {
                final long reconciledTotal = state.totalForPhysical(observedPhysicalProxyCount);
                DrawerState reconciled = state.withStoredTotalAndExpectedMirrorCount(
                        reconciledTotal,
                        observedPhysicalProxyCount
                );
                if (reconciledTotal == 0L) {
                    reconciled = reconciled.withoutTemplate();
                }
                return new DrawerJournalReconciliation(
                        DrawerJournalReconciliation.Resolution.NO_PENDING_JOURNAL,
                        reconciled,
                        observedPhysicalProxyCount
                );
            }
            state.totalForPhysical(observedPhysicalProxyCount);
            return new DrawerJournalReconciliation(
                    DrawerJournalReconciliation.Resolution.NO_PENDING_JOURNAL,
                    state,
                    observedPhysicalProxyCount
            );
        }

        final DrawerProxyJournal journal = pendingJournal.get();
        if (observedPhysicalProxyCount > journal.totalBefore()) {
            return new DrawerJournalReconciliation(
                    DrawerJournalReconciliation.Resolution.IMPOSSIBLE_PHYSICAL_COUNT,
                    state,
                    observedPhysicalProxyCount
            );
        }

        final long recoveredTotal;
        final DrawerJournalReconciliation.Resolution resolution;
        if (journal.phase() == DrawerJournalPhase.APPLIED) {
            if (observedPhysicalProxyCount > journal.totalAfter()) {
                return new DrawerJournalReconciliation(
                        DrawerJournalReconciliation.Resolution.IMPOSSIBLE_PHYSICAL_COUNT,
                        state,
                        observedPhysicalProxyCount
                );
            }
            recoveredTotal = journal.totalAfter();
            resolution = observedPhysicalProxyCount == journal.physicalTarget()
                    ? DrawerJournalReconciliation.Resolution.COMMITTED
                    : DrawerJournalReconciliation.Resolution.PARTIALLY_APPLIED;
        } else if (observedPhysicalProxyCount == journal.physicalBefore()) {
            recoveredTotal = journal.totalBefore();
            resolution = DrawerJournalReconciliation.Resolution.ROLLED_BACK;
        } else {
            // A physical change may have completed just before a crash, while the matching
            // external player transfer cannot be atomically observed. Preserve the actual proxy
            // stock and use the post-transfer total so recovery can never recreate removed items.
            recoveredTotal = Math.max(journal.totalAfter(), observedPhysicalProxyCount);
            resolution = observedPhysicalProxyCount == journal.physicalTarget()
                    ? DrawerJournalReconciliation.Resolution.COMMITTED
                    : DrawerJournalReconciliation.Resolution.PARTIALLY_APPLIED;
        }
        final long hiddenRecovered = recoveredTotal - observedPhysicalProxyCount;
        DrawerState recovered = state.usesReservedMirror()
                ? state.withStoredTotalAndExpectedMirrorCount(recoveredTotal, observedPhysicalProxyCount)
                        .withoutProxyJournal()
                : state.withHiddenCount(hiddenRecovered).withoutProxyJournal();
        if (recoveredTotal == 0L && observedPhysicalProxyCount == 0L) {
            recovered = recovered.withoutTemplate();
        }
        return new DrawerJournalReconciliation(resolution, recovered, observedPhysicalProxyCount);
    }

    private static void requireReady(final DrawerState state, final long physicalProxyCount) {
        Objects.requireNonNull(state, "state");
        if (state.hasPendingProxyJournal()) {
            throw new DrawerInvariantViolationException("Reconcile the pending proxy journal before accepting another transfer");
        }
        state.totalForPhysical(physicalProxyCount);
    }

    private static long nonNegativeRequest(final long requestedAmount) {
        return Math.max(0L, requestedAmount);
    }
}
