package com.Teenkung.devStorageDrawer.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.Teenkung.devStorageDrawer.domain.DrawerDisplayLink;
import com.Teenkung.devStorageDrawer.domain.DrawerProxyJournal;
import com.Teenkung.devStorageDrawer.domain.DrawerState;
import com.Teenkung.devStorageDrawer.domain.DrawerValidationException;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class DrawerStateCodecTest {

    @Test
    @Disabled("Paper ItemStack construction needs a live Paper registry; covered by server integration tests")
    void roundTripsItemBytesJournalAndAllDisplayRoles() {
        final DrawerDisplayLink displays = new DrawerDisplayLink(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID()
        );
        final DrawerState state = DrawerState.restored(
                DrawerState.CURRENT_SCHEMA_VERSION,
                "tier_2",
                new ItemStack(Material.DIAMOND, 1),
                300L,
                4_096L,
                new DrawerProxyJournal(UUID.randomUUID(), 364L, 64L, 128L, 123L),
                displays
        );
        final DrawerStateCodec codec = new DrawerStateCodec();

        final DrawerState decoded = codec.decode(codec.encode(state));
        assertEquals(state.schemaVersion(), decoded.schemaVersion());
        assertEquals(state.tierId(), decoded.tierId());
        assertEquals(state.hiddenCount(), decoded.hiddenCount());
        assertEquals(state.capacitySnapshot(), decoded.capacitySnapshot());
        assertEquals(state.proxyJournal(), decoded.proxyJournal());
        assertEquals(displays, decoded.displayLink());
        assertTrue(decoded.matchesTemplate(new ItemStack(Material.DIAMOND, 64)));
    }

    @Test
    void rejectsCorruptTemplateBytes() {
        final DrawerStatePayload corrupt = new DrawerStatePayload(
                DrawerState.CURRENT_SCHEMA_VERSION,
                "tier_1",
                new byte[] {1, 2, 3},
                1L,
                64L,
                null,
                DrawerDisplayLink.none()
        );

        assertThrows(DrawerValidationException.class, () -> new DrawerStateCodec().decode(corrupt));
    }

    @Test
    void roundTripsAnEmptyDrawerWithoutARegistry() {
        final DrawerState empty = DrawerState.empty("tier_1", 64L);
        final DrawerStateCodec codec = new DrawerStateCodec();

        final DrawerState decoded = codec.decode(codec.encode(empty));
        assertEquals(empty, decoded);
    }
}
