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

/**
 * Soft WorldGuard bridge. {@link #canBreak} asks WorldGuard whether this player may break here.
 * The query is read-only. An absent installation is neutral; API errors are returned to the configured
 * protection error policy.
 */
final class WorldGuardBridge implements ProtectionHook {
    private boolean present;

    @Override
    public String name() { return "WorldGuard"; }

    @Override
    public void init() { present = Bukkit.getPluginManager().getPlugin("WorldGuard") != null; }

    @Override
    public boolean present() { return present; }

    @Override
    public Decision canBreak(Player player, Location loc, org.bukkit.Material material) {
        if (!present) return Decision.ALLOW;
        try {
            LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            RegionQuery query = container.createQuery();
            return query.testBuild(BukkitAdapter.adapt(loc), localPlayer, Flags.BUILD)
                    ? Decision.ALLOW : Decision.DENY;
        } catch (RuntimeException | LinkageError error) {
            return Decision.ERROR;
        }
    }
}
