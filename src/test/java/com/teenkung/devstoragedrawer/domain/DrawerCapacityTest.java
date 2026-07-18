package com.teenkung.devstoragedrawer.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class DrawerCapacityTest {

    @Test
    @Disabled("Paper ItemStack construction needs a live Paper registry; covered by server integration tests")
    void derivesCapacityFromTheItemNativeStackSize() {
        final DrawerTier tier = new DrawerTier("tier_1", "Tier 1", 32L);

        assertEquals(2_048L, tier.capacityFor(new ItemStack(Material.STONE, 1)));
    }

    @Test
    void rejectsCapacityOverflowInsteadOfWrapping() {
        assertThrows(
                DrawerInvariantViolationException.class,
                () -> DrawerCapacity.checkedAdd(Long.MAX_VALUE, 1L, "test total")
        );
    }

    @Test
    void appliesUnreconciledMirrorInputAndOutputToTheStoredTotal() {
        assertEquals(95L, DrawerCapacity.totalAfterMirrorDelta(90L, 10L, 15L, 100L));
        assertEquals(85L, DrawerCapacity.totalAfterMirrorDelta(90L, 10L, 5L, 100L));
    }

    @Test
    void rejectsMirrorInputThatWouldExceedCapacity() {
        assertThrows(
                DrawerInvariantViolationException.class,
                () -> DrawerCapacity.totalAfterMirrorDelta(100L, 10L, 11L, 100L)
        );
    }
}
