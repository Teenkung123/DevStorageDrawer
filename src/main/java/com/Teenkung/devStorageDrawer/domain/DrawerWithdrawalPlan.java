package com.teenkung.devstoragedrawer.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Durable two-phase intent for a player withdrawal. A matching fsynced receipt is created before
 * physical stock changes, then delivery records an idempotency marker together with player data so
 * recovery can complete without either recreating removed stock or losing an acknowledged claim.
 */
public record DrawerWithdrawalPlan(
        DrawerState journaledState,
        DrawerState appliedJournaledState,
        DrawerState committedState,
        DrawerProxyJournal journal
) {

    public DrawerWithdrawalPlan {
        Objects.requireNonNull(journaledState, "journaledState");
        Objects.requireNonNull(appliedJournaledState, "appliedJournaledState");
        Objects.requireNonNull(committedState, "committedState");
        Objects.requireNonNull(journal, "journal");
        if (!journaledState.hasPendingProxyJournal()
                || !appliedJournaledState.hasPendingProxyJournal()
                || committedState.hasPendingProxyJournal()
                || journal.kind() != DrawerJournalKind.PLAYER_WITHDRAWAL
                || journal.phase() != DrawerJournalPhase.PREPARED
                || appliedJournaledState.proxyJournal().orElseThrow().phase() != DrawerJournalPhase.APPLIED) {
            throw new DrawerInvariantViolationException("Withdrawal journal state sequence is invalid");
        }
        if (journal.totalBefore() != journaledState.totalForPhysical(journal.physicalBefore())
                || journal.totalAfter() != committedState.totalForPhysical(journal.physicalTarget())) {
            throw new DrawerInvariantViolationException("Withdrawal journal totals do not match their states");
        }
    }

    public static DrawerWithdrawalPlan forTransaction(
            final DrawerStorageTransaction transaction,
            final UUID ownerId,
            final long createdAtEpochMillis
    ) {
        Objects.requireNonNull(transaction, "transaction");
        if (transaction.operation() != DrawerOperation.PLAYER_WITHDRAW || !transaction.accepted()) {
            throw new DrawerValidationException("A withdrawal plan requires an accepted player withdrawal transaction");
        }

        final long totalBefore = transaction.stateBefore().totalForPhysical(transaction.physicalBefore());
        final long totalAfter = transaction.stateAfter().totalForPhysical(transaction.physicalAfter());
        final DrawerProxyJournal journal = new DrawerProxyJournal(
                UUID.randomUUID(),
                DrawerJournalKind.PLAYER_WITHDRAWAL,
                DrawerJournalPhase.PREPARED,
                Objects.requireNonNull(ownerId, "ownerId"),
                totalBefore,
                totalAfter,
                transaction.physicalBefore(),
                transaction.physicalAfter(),
                createdAtEpochMillis
        );
        final DrawerState appliedState = transaction.stateAfter().hasTemplate()
                ? transaction.stateAfter()
                : transaction.stateAfter().withTemplate(transaction.itemTemplate().orElseThrow());
        return new DrawerWithdrawalPlan(
                transaction.stateBefore().withProxyJournal(journal),
                appliedState.withProxyJournal(journal.withPhase(DrawerJournalPhase.APPLIED)),
                transaction.stateAfter(),
                journal
        );
    }
}
