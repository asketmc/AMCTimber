package com.asketmc.timber;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.LivingEntity;

interface ProtectionHook {
    enum Decision { ALLOW, DENY, ERROR }
    enum Action { SOURCE_BREAK, LAND_ENTITY, TRUNK_INTERACT, ITEM_DROP }

    interface Attempt {
        Decision check(Action action, Location location, Material material);
    }

    String name();

    void init();

    boolean present();

    Decision canBreak(Player player, Location location, Material material);

    default Decision check(Player player, Action action, Location location, Material material) {
        return canBreak(player, location, material);
    }

    default Decision canDamage(Player player, LivingEntity target, Location location) {
        return Decision.ALLOW;
    }

    default Attempt beginAttempt(Player player) {
        return (action, location, material) -> check(player, action, location, material);
    }
}
