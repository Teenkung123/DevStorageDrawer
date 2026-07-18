package com.teenkung.devstoragedrawer.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DrawerApiValueTest {

    @Test
    void emptySnapshotProvidesStableLogicalCapacity() {
        final DrawerLocation location = new DrawerLocation(UUID.randomUUID(), 12, 80, -24);
        final DrawerSnapshot snapshot = new DrawerSnapshot(location, "basic", null, 0L, 2_048L, 0L);

        assertEquals(location, snapshot.location());
        assertEquals("basic", snapshot.tierId());
        assertTrue(snapshot.template().isEmpty());
        assertTrue(snapshot.isEmpty());
        assertFalse(snapshot.isFull());
        assertEquals(2_048L, snapshot.availableCapacity());
    }

    @Test
    void queryResultsOnlyExposeSnapshotsForFoundDrawers() {
        final DrawerSnapshot snapshot = new DrawerSnapshot(
                new DrawerLocation(UUID.randomUUID(), 0, 64, 0), "basic", null, 0L, 64L, 0L
        );

        assertTrue(DrawerQueryResult.found(snapshot).snapshot().isPresent());
        assertTrue(DrawerQueryResult.of(DrawerQueryStatus.NOT_DRAWER).snapshot().isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> new DrawerQueryResult(DrawerQueryStatus.NOT_DRAWER, Optional.of(snapshot))
        );
    }

    @Test
    void transferResultRejectsInconsistentAcceptedAmounts() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DrawerTransferResult(DrawerTransferStatus.FULL, 4L, 1L, Optional.empty(), List.of())
        );

        final DrawerTransferResult result = new DrawerTransferResult(
                DrawerTransferStatus.APPLIED, 4L, 3L, Optional.empty(), List.of()
        );
        assertEquals(1L, result.rejectedAmount());
    }

    @Test
    void tierQueryKeepsUnavailableStateDistinctFromMetadata() {
        assertTrue(DrawerTierQueryResult.of(DrawerTierQueryStatus.PLUGIN_DISABLED).tiers().isEmpty());
        assertEquals(DrawerTierQueryStatus.FOUND, DrawerTierQueryResult.found(List.of()).status());
        assertThrows(
                IllegalArgumentException.class,
                () -> new DrawerTierQueryResult(DrawerTierQueryStatus.UNAVAILABLE, List.of(
                        new DrawerTierInfo("basic", 32L, Optional.empty())
                ))
        );
    }
}
