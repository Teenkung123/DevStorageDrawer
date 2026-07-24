package com.Teenkung.devStorageDrawer.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.Teenkung.devStorageDrawer.domain.DrawerInvariantViolationException;
import com.Teenkung.devStorageDrawer.domain.DrawerState;
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

    @Test
    void bedrockFallbackIsOnlyShownToBedrockPlayers() {
        assertTrue(DrawerVisualRenderer.shouldShowTo(true, "bedrock_item"));
        assertTrue(DrawerVisualRenderer.shouldShowTo(true, "bedrock_text"));
        assertFalse(DrawerVisualRenderer.shouldShowTo(false, "bedrock_item"));
        assertFalse(DrawerVisualRenderer.shouldShowTo(false, "bedrock_text"));
    }

    @Test
    void javaDisplaysAndUnknownRolesFailClosedForTheWrongClient() {
        assertTrue(DrawerVisualRenderer.shouldShowTo(false, "java_item"));
        assertTrue(DrawerVisualRenderer.shouldShowTo(false, "java_amount"));
        assertFalse(DrawerVisualRenderer.shouldShowTo(true, "java_item"));
        assertFalse(DrawerVisualRenderer.shouldShowTo(false, null));
        assertFalse(DrawerVisualRenderer.shouldShowTo(false, "unknown"));
    }
}
