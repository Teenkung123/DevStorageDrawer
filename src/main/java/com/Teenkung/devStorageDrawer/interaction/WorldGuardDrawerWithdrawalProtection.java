package com.Teenkung.devStorageDrawer.interaction;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag.State;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
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
        if (query.testState(location, localPlayer, Flags.CHEST_ACCESS)) {
            return true;
        }

        final ApplicableRegionSet regions = query.getApplicableRegions(location);
        if (regions.isVirtual() || regions.size() == 0) {
            return true;
        }

        boolean hasExplicitChestRule = false;
        for (final ProtectedRegion region : regions) {
            final State chestAccess = region.getFlag(Flags.CHEST_ACCESS);
            if (chestAccess == null) {
                continue;
            }
            hasExplicitChestRule = true;
            final boolean memberOrOwner = region.isMember(localPlayer) || region.isOwner(localPlayer);
            if (chestAccess == State.DENY && !memberOrOwner) {
                return false;
            }
            if (chestAccess == State.ALLOW && memberOrOwner) {
                return true;
            }
        }

        return !hasExplicitChestRule;
    }
}
