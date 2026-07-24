package com.Teenkung.devStorageDrawer.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled("Paper ItemStack construction needs a live Paper registry; covered by server integration tests")
class DrawerItemIdentityTest {

    @Test
    void ignoresAmountButNotItemIdentity() {
        final ItemStack template = DrawerItemIdentity.templateOf(new ItemStack(Material.DIAMOND, 1));

        assertTrue(DrawerItemIdentity.matches(template, new ItemStack(Material.DIAMOND, 64)));
        assertFalse(DrawerItemIdentity.matches(template, new ItemStack(Material.EMERALD, 1)));
    }
}
