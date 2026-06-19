package com.asketmc.timber;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.logging.Logger;

/**
 * Soft WorldGuard bridge. {@link #canBuild} answers "may this player break a block here?" by delegating
 * to WG's own {@code testBuild(BUILD)} query (which already honours region membership and the wg bypass
 * permission). Read-only — no synthetic events, no side effects. Fail-open: if WG is absent or the API
 * throws, we return {@code true} and let the already-permitted base break stand for the rest of the tree.
 */
final class WorldGuardBridge {
    private final Logger log;
    private boolean present;

    WorldGuardBridge(Logger log) { this.log = log; }

    void init() {
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") == null) {
            log.info("WorldGuard not present — region checks skipped (fail-open).");
            present = false;
            return;
        }
        present = true;
        log.info("WorldGuard present — felling honours region BUILD permission.");
    }

    boolean present() { return present; }

    boolean canBuild(Player player, Location loc) {
        if (!present) return true;
        try {
            LocalPlayer lp = WorldGuardPlugin.inst().wrapPlayer(player);
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            RegionQuery query = container.createQuery();
            return query.testBuild(BukkitAdapter.adapt(loc), lp, Flags.BUILD);
        } catch (Throwable t) {
            return true; // fail-open
        }
    }
}
