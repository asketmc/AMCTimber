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
 * own cached DESTROY permission (wilderness is always allowed). Read-only, main-thread. Fail-open: absent
 * Towny or any API error returns {@code true}. Presence is reported once in the enable line, so silent.
 */
final class TownyBridge {
    private boolean present;

    void init() { present = Bukkit.getPluginManager().getPlugin("Towny") != null; }

    boolean present() { return present; }

    boolean canDestroy(Player player, Location loc, Material material) {
        if (!present) return true;
        try {
            if (TownyAPI.getInstance().isWilderness(loc)) return true;
            return PlayerCacheUtil.getCachePermission(player, loc, material, TownyPermission.ActionType.DESTROY);
        } catch (Throwable t) {
            return true; // fail-open
        }
    }
}
