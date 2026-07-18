package com.teenkung.devstoragedrawer.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class DrawerProxyJournalTest {

    @Test
    void legacyConstructorKeepsSameTotalSemantics() {
        final DrawerProxyJournal journal = new DrawerProxyJournal(UUID.randomUUID(), 128L, 64L, 96L, 10L);

        assertEquals(128L, journal.totalBefore());
        assertEquals(128L, journal.totalAfter());
        assertEquals(128L, journal.totalCount());
    }

    @Test
    void withdrawalJournalCanRepresentADecreasingTotal() {
        final DrawerProxyJournal journal = new DrawerProxyJournal(
                UUID.randomUUID(),
                128L,
                64L,
                64L,
                0L,
                10L
        );

        assertEquals(128L, journal.totalBefore());
        assertEquals(64L, journal.totalAfter());
        assertEquals(0L, journal.physicalTarget());
    }

    @Test
    void rejectsPhysicalCountsBeyondTheirCorrespondingTotals() {
        assertThrows(
                DrawerValidationException.class,
                () -> new DrawerProxyJournal(UUID.randomUUID(), 10L, 5L, 10L, 6L, 10L)
        );
    }
}
