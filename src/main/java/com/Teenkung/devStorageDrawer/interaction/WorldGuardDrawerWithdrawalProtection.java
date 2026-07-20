package com.teenkung.devstoragedrawer.interaction;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.block.Barrel;
import org.bukkit.entity.Player;

/** Uses WorldGuard's normal chest-access decision for custom drawer withdrawals. */
public final class WorldGuardDrawerWithdrawalProtection implements DrawerWithdrawalProtection {

    @Override
    public boolean canWithdraw(final Player player, final Barrel barrel) {
        final LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
        final Location location = BukkitAdapter.adapt(barrel.getLocation());
        if (WorldGuard.getInstance().getPlatform().getSessionManager().hasBypass(localPlayer, localPlayer.getWorld())) {
            return true;
        }
        final RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        return query.testState(location, localPlayer, Flags.CHEST_ACCESS);
    }
}
