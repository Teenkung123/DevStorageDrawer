package com.teenkung.devstoragedrawer.command;

import com.teenkung.devstoragedrawer.config.DrawerMessages;
import com.teenkung.devstoragedrawer.config.DrawerSettings;
import org.bukkit.entity.Player;

/** Narrow runtime surface used by the administrative command. */
public interface DrawerAdminFacade {
    void reload(DrawerSettings settings, DrawerMessages messages);

    void repairLoadedDrawers();

    void migrateTargetedDrawer(Player player);
}
