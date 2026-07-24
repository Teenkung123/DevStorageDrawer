package com.Teenkung.devStorageDrawer.redstone;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DrawerComparatorServiceTest {

    @Test
    void mapsEmptyPartialAndFullLogicalStockToVanillaStyleLevels() {
        assertEquals(0, DrawerComparatorService.level(0L, 64L));
        assertEquals(1, DrawerComparatorService.level(1L, 64L));
        assertEquals(8, DrawerComparatorService.level(32L, 64L));
        assertEquals(15, DrawerComparatorService.level(64L, 64L));
    }
}
