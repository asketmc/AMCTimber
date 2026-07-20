// SPDX-License-Identifier: GPL-3.0-only
package com.asketmc.qa;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Test-only late policy listener for AMCTimber's final-event-state E2E contract. */
public final class EventPolicyFixture extends JavaPlugin implements Listener {
    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("event policy fixture ready");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBreak(BlockBreakEvent event) {
        if (event.getBlock().getY() != 100 || event.getBlock().getZ() != 0) return;
        if (event.getBlock().getX() == -20) {
            event.setCancelled(true);
            getLogger().info("policy fixture cancelled break at -20,100,0");
        } else if (event.getBlock().getX() == -40) {
            event.setDropItems(false);
            getLogger().info("policy fixture disabled drops at -40,100,0");
        }
    }
}
