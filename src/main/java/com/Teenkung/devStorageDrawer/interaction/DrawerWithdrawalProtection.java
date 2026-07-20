package com.teenkung.devstoragedrawer.interaction;

import org.bukkit.block.Barrel;
import org.bukkit.entity.Player;

/** Applies optional external protection checks before a player receives drawer contents. */
@FunctionalInterface
public interface DrawerWithdrawalProtection {

    DrawerWithdrawalProtection ALLOW_ALL = (player, barrel) -> true;

    boolean canWithdraw(Player player, Barrel barrel);
}
