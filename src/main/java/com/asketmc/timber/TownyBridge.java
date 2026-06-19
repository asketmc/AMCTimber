package com.asketmc.timber;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.TownyPermission;
import com.palmergames.bukkit.towny.utils.PlayerCacheUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.logging.Logger;

/**
 * Soft Towny bridge. {@link #canDestroy} answers "may this player destroy a block here?" using Towny's
 * own cached DESTROY permission (wilderness is always allowed). Read-only, main-thread. Fail-open: absent
 * Towny or any API error returns {@code true}.
 */
final class TownyBridge {
    private final Logger log;
    private boolean present;

    TownyBridge(Logger log) { this.log = log; }

    void init() {
        if (Bukkit.getPluginManager().getPlugin("Towny") == null) {
            log.info("Towny not present — claim checks skipped (fail-open).");
            present = false;
            return;
        }
        present = true;
        log.info("Towny present — felling honours town DESTROY permission.");
    }

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
