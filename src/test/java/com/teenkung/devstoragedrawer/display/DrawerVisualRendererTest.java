package com.teenkung.devstoragedrawer.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.teenkung.devstoragedrawer.domain.DrawerInvariantViolationException;
import com.teenkung.devstoragedrawer.domain.DrawerState;
import org.junit.jupiter.api.Test;

class DrawerVisualRendererTest {

    @Test
    void reservedMirrorRendersAuthoritativeTotalDuringTransientInput() {
        final DrawerState state = DrawerState.empty("tier_1", 64L);

        assertThrows(DrawerInvariantViolationException.class, () -> state.totalForPhysical(1L));
        assertEquals(0L, DrawerVisualRenderer.displayedTotal(state, 1L));
    }

    @Test
    void legacyDrawerStillIncludesItsPhysicalStock() {
        final DrawerState state = DrawerState.empty(1, "tier_1", 64L);

        assertEquals(0L, DrawerVisualRenderer.displayedTotal(state, 0L));
        assertThrows(
                DrawerInvariantViolationException.class,
                () -> DrawerVisualRenderer.displayedTotal(state, 1L)
        );
    }
}
