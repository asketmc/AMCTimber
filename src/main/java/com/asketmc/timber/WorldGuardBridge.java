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
import org.bukkit.entity.LivingEntity;
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
            return query.testBuild(BukkitAdapter.adapt(loc), localPlayer, Flags.BLOCK_BREAK)
                    ? Decision.ALLOW : Decision.DENY;
        } catch (RuntimeException | LinkageError error) {
            return Decision.ERROR;
        }
    }

    @Override
    public Decision check(Player player, Action action, Location location, org.bukkit.Material material) {
        if (!present) return Decision.ALLOW;
        try {
            LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
            RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
            return check(query, localPlayer, action, location);
        } catch (RuntimeException | LinkageError error) {
            return Decision.ERROR;
        }
    }

    @Override
    public Decision canDamage(Player player, LivingEntity target, Location location) {
        if (!present) return Decision.ALLOW;
        try {
            LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
            RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
            boolean allowed = query.testState(BukkitAdapter.adapt(location), localPlayer,
                    target instanceof Player ? Flags.PVP : Flags.DAMAGE_ANIMALS);
            return allowed ? Decision.ALLOW : Decision.DENY;
        } catch (RuntimeException | LinkageError error) {
            return Decision.ERROR;
        }
    }

    @Override
    public Attempt beginAttempt(Player player) {
        if (!present) return (action, location, material) -> Decision.ALLOW;
        try {
            LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            RegionQuery query = container.createQuery();
            return (action, location, material) -> {
                try {
                    return check(query, localPlayer, action, location);
                } catch (RuntimeException | LinkageError error) {
                    return Decision.ERROR;
                }
            };
        } catch (RuntimeException | LinkageError error) {
            return (action, location, material) -> Decision.ERROR;
        }
    }

    private static Decision check(RegionQuery query, LocalPlayer player, Action action, Location location) {
        boolean allowed = switch (action) {
            case SOURCE_BREAK -> query.testBuild(BukkitAdapter.adapt(location), player, Flags.BLOCK_BREAK);
            case LAND_ENTITY -> query.testBuild(BukkitAdapter.adapt(location), player, Flags.BUILD);
            case TRUNK_INTERACT -> query.testState(BukkitAdapter.adapt(location), player, Flags.INTERACT);
            case ITEM_DROP -> query.testState(BukkitAdapter.adapt(location), player, Flags.ITEM_DROP);
        };
        return allowed ? Decision.ALLOW : Decision.DENY;
    }
}
