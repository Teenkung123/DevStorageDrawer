package com.teenkung.devstoragedrawer.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DrawerComparatorLevelTest {

    @Test
    void proxyTargetMakesVanillaBarrelEmitEveryLogicalLevel() {
        final long capacity = 64L * 64L;
        for (long total = 1L; total <= capacity; total++) {
            final int expected = DrawerComparatorLevel.level(total, capacity);
            final long proxy = DrawerComparatorLevel.physicalProxyTarget(total, capacity, 64);
            final int vanilla = 1 + (int) Math.floor(14D * proxy / (27D * 64D));
            assertTrue(proxy <= total, "Proxy stock must always be backed by real stock");
            assertEquals(expected, vanilla, "Vanilla signal mismatch at total " + total);
        }
    }

    @Test
    void fullDrawerUsesAllSlotsWhileLegacySmallCapacityNeverInventsItems() {
        assertEquals(27L * 64L, DrawerComparatorLevel.physicalProxyTarget(2_048L, 2_048L, 64));
        assertEquals(64L, DrawerComparatorLevel.physicalProxyTarget(64L, 64L, 64));
    }
}
