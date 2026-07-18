package com.teenkung.devstoragedrawer.display;

import org.bukkit.NamespacedKey;

/** Entity PDC keys used to associate renderer-owned entities with a drawer block. */
final class DrawerDisplayKeys {
    static final NamespacedKey MARKER = new NamespacedKey("devstoragedrawer", "drawer_display");
    static final NamespacedKey ROLE = new NamespacedKey("devstoragedrawer", "drawer_display_role");
    static final NamespacedKey WORLD = new NamespacedKey("devstoragedrawer", "drawer_display_world");
    static final NamespacedKey X = new NamespacedKey("devstoragedrawer", "drawer_display_x");
    static final NamespacedKey Y = new NamespacedKey("devstoragedrawer", "drawer_display_y");
    static final NamespacedKey Z = new NamespacedKey("devstoragedrawer", "drawer_display_z");

    private DrawerDisplayKeys() {
    }
}
