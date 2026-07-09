package com.asketmc.timber;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.TownyPermission;
import com.palmergames.bukkit.towny.utils.PlayerCacheUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Soft Towny bridge. {@link #canDestroy} answers "may this player destroy a block here?" using Towny's
 * own cached DESTROY permission (wilderness is always allowed). Read-only and main-thread. An absent
 * Towny installation is neutral; API errors are returned to the configured protection error policy.
 */
final class TownyBridge implements ProtectionHook {
    private boolean present;

    @Override
    public String name() { return "Towny"; }

    @Override
    public void init() { present = Bukkit.getPluginManager().getPlugin("Towny") != null; }

    @Override
    public boolean present() { return present; }

    @Override
    public Decision canBreak(Player player, Location loc, Material material) {
        if (!present) return Decision.ALLOW;
        try {
            if (TownyAPI.getInstance().isWilderness(loc)) return Decision.ALLOW;
            return PlayerCacheUtil.getCachePermission(player, loc, material, TownyPermission.ActionType.DESTROY)
                    ? Decision.ALLOW : Decision.DENY;
        } catch (RuntimeException | LinkageError error) {
            return Decision.ERROR;
        }
    }
}
