package com.Teenkung.devStorageDrawer.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * A durable intent record written before redistributing real barrel proxy stacks. It has enough
 * information to recover a partial rebalance from the actual barrel inventory after a restart.
 */
public record DrawerProxyJournal(
        UUID operationId,
        DrawerJournalKind kind,
        DrawerJournalPhase phase,
        UUID ownerId,
        long totalBefore,
        long totalAfter,
        long physicalBefore,
        long physicalTarget,
        long createdAtEpochMillis
) {

    public DrawerProxyJournal {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(phase, "phase");
        if ((kind == DrawerJournalKind.PLAYER_WITHDRAWAL || kind == DrawerJournalKind.OVERFLOW_RECOVERY)
                && ownerId == null) {
            throw new DrawerValidationException("Owner-bound journals require an owner id");
        }
        if (kind == DrawerJournalKind.PROXY_REBALANCE && ownerId != null) {
            throw new DrawerValidationException("Proxy-rebalance journals cannot have an owner id");
        }
        DrawerCapacity.requireNonNegative(totalBefore, "journal total-before count");
        DrawerCapacity.requireNonNegative(totalAfter, "journal total-after count");
        DrawerCapacity.requireNonNegative(physicalBefore, "journal physical-before count");
        DrawerCapacity.requireNonNegative(physicalTarget, "journal physical-target count");
        if (physicalBefore > totalBefore || physicalTarget > totalAfter) {
            throw new DrawerValidationException("Journal physical counts cannot exceed their corresponding total count");
        }
        if (createdAtEpochMillis < 0L) {
            throw new DrawerValidationException("Journal timestamp must not be negative");
        }
    }

    /** Compatibility constructor for existing same-total proxy rebalance journals. */
    public DrawerProxyJournal(
            final UUID operationId,
            final long totalBefore,
            final long totalAfter,
            final long physicalBefore,
            final long physicalTarget,
            final long createdAtEpochMillis
    ) {
        this(
                operationId,
                DrawerJournalKind.PROXY_REBALANCE,
                DrawerJournalPhase.PREPARED,
                null,
                totalBefore,
                totalAfter,
                physicalBefore,
                physicalTarget,
                createdAtEpochMillis
        );
    }

    public DrawerProxyJournal(
            final UUID operationId,
            final long totalCount,
            final long physicalBefore,
            final long physicalTarget,
            final long createdAtEpochMillis
    ) {
        this(operationId, totalCount, totalCount, physicalBefore, physicalTarget, createdAtEpochMillis);
    }

    /** Legacy spelling retained for callers that only support same-total journals. */
    public long totalCount() {
        return this.totalBefore;
    }

    public DrawerProxyJournal withPhase(final DrawerJournalPhase replacement) {
        return new DrawerProxyJournal(
                this.operationId,
                this.kind,
                replacement,
                this.ownerId,
                this.totalBefore,
                this.totalAfter,
                this.physicalBefore,
                this.physicalTarget,
                this.createdAtEpochMillis
        );
    }
}
