package com.asketmc.timber;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

interface ProtectionHook {
    enum Decision { ALLOW, DENY, ERROR }

    String name();

    void init();

    boolean present();

    Decision canBreak(Player player, Location location, Material material);
}
