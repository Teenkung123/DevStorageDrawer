package com.teenkung.devstoragedrawer.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled("Paper ItemStack construction needs a live Paper registry; covered by server integration tests")
class SingleItemDrawerStorageTest {

    private final SingleItemDrawerStorage storage = new SingleItemDrawerStorage(() -> 123L);

    @Test
    void depositsIntoHiddenStockThenWithdrawsWithoutTouchingProxyStock() {
        final DrawerState empty = DrawerState.empty("tier_1", 128L);
        final ItemStack stone = new ItemStack(Material.STONE, 64);

        final DrawerStorageTransaction deposit = this.storage.deposit(empty, 0L, stone, 64L);
        assertTrue(deposit.accepted());
        assertEquals(64L, deposit.stateAfter().hiddenCount());
        assertEquals(0L, deposit.physicalAfter());

        final DrawerStorageTransaction withdraw = this.storage.withdraw(deposit.stateAfter(), 0L, 10L);
        assertTrue(withdraw.accepted());
        assertEquals(54L, withdraw.stateAfter().hiddenCount());
        assertEquals(10L, withdraw.acceptedAmount());
        assertEquals(Material.STONE, withdraw.createTransferredStack(10).getType());
    }

    @Test
    void proxyInsertionAndExtractionAreCountConserving() {
        final DrawerState empty = DrawerState.empty("tier_1", 128L);
        final ItemStack stone = new ItemStack(Material.STONE, 64);

        final DrawerStorageTransaction insert = this.storage.acceptProxyInsert(empty, 0L, stone, 32L);
        assertEquals(32L, insert.physicalAfter());
        assertEquals(0L, insert.stateAfter().hiddenCount());

        final DrawerStorageTransaction extract = this.storage.permitProxyExtract(
                insert.stateAfter(),
                insert.physicalAfter(),
                new ItemStack(Material.STONE, 5),
                5L
        );
        assertEquals(27L, extract.physicalAfter());
        assertEquals(5L, extract.acceptedAmount());
    }

    @Test
    void rebalanceJournalRecoversPartialInventoryRewriteWithoutChangingTotal() {
        final ItemStack stone = new ItemStack(Material.STONE, 1);
        final DrawerState state = DrawerState.restored(
                DrawerState.CURRENT_SCHEMA_VERSION,
                "tier_1",
                stone,
                200L,
                1_000L,
                null,
                DrawerDisplayLink.none()
        );

        final DrawerRebalancePlan plan = this.storage.prepareRebalance(state, 10L, 2);
        assertTrue(plan.requiresRebalance());
        assertEquals(128L, plan.physicalTarget());
        assertTrue(plan.journaledState().hasPendingProxyJournal());
        assertFalse(plan.committedState().hasPendingProxyJournal());
        assertEquals(82L, plan.committedState().hiddenCount());

        final DrawerJournalReconciliation recovered = this.storage.reconcile(plan.journaledState(), 75L);
        assertEquals(DrawerJournalReconciliation.Resolution.PARTIALLY_APPLIED, recovered.resolution());
        assertEquals(135L, recovered.state().hiddenCount());
        assertFalse(recovered.state().hasPendingProxyJournal());
        assertEquals(210L, recovered.state().totalForPhysical(75L));
    }

    @Test
    void refusesToRecoverWhenObservedProxyStockWouldDuplicateItems() {
        final DrawerState state = DrawerState.restored(
                DrawerState.CURRENT_SCHEMA_VERSION,
                "tier_1",
                new ItemStack(Material.STONE, 1),
                10L,
                100L,
                new DrawerProxyJournal(UUID.randomUUID(), 20L, 10L, 20L, 123L),
                DrawerDisplayLink.none()
        );

        final DrawerJournalReconciliation result = this.storage.reconcile(state, 21L);
        assertEquals(DrawerJournalReconciliation.Resolution.IMPOSSIBLE_PHYSICAL_COUNT, result.resolution());
        assertTrue(result.requiresManualRecovery());
        assertTrue(result.state().hasPendingProxyJournal());
    }
}
