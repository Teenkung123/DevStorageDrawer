package com.Teenkung.devStorageDrawer.command;

import com.Teenkung.devStorageDrawer.config.DrawerMessages;
import com.Teenkung.devStorageDrawer.config.DrawerSettings;
import org.bukkit.entity.Player;

/** Narrow runtime surface used by the administrative command. */
public interface DrawerAdminFacade {
    void reload(DrawerSettings settings, DrawerMessages messages);

    void repairLoadedDrawers();

    void migrateTargetedDrawer(Player player);
}
