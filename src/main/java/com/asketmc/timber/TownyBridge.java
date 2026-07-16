package com.asketmc.timber;

import com.palmergames.bukkit.towny.object.TownyPermission;
import com.palmergames.bukkit.towny.utils.PlayerCacheUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Soft Towny bridge. {@link #canBreak} answers "may this player destroy a block here?" using Towny's
 * own cached DESTROY permission for every Towny coordinate. Read-only and main-thread. An absent
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
            return PlayerCacheUtil.getCachePermission(player, loc, material, TownyPermission.ActionType.DESTROY)
                    ? Decision.ALLOW : Decision.DENY;
        } catch (RuntimeException | LinkageError error) {
            return Decision.ERROR;
        }
    }

    @Override
    public Decision check(Player player, Action action, Location location, Material material) {
        if (!present) return Decision.ALLOW;
        try {
            TownyPermission.ActionType townyAction = switch (action) {
                case SOURCE_BREAK -> TownyPermission.ActionType.DESTROY;
                case LAND_ENTITY -> TownyPermission.ActionType.BUILD;
                case TRUNK_INTERACT -> TownyPermission.ActionType.SWITCH;
                case ITEM_DROP -> TownyPermission.ActionType.ITEM_USE;
            };
            return PlayerCacheUtil.getCachePermission(player, location, material, townyAction)
                    ? Decision.ALLOW : Decision.DENY;
        } catch (RuntimeException | LinkageError error) {
            return Decision.ERROR;
        }
    }

    @Override
    public Attempt beginAttempt(Player player) {
        if (!present) return (action, location, material) -> Decision.ALLOW;
        return (action, location, material) -> check(player, action, location, material);
    }
}
