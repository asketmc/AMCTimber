package com.asketmc.timber;

import org.bukkit.plugin.Plugin;

/** Main-thread scheduler for the explicitly Paper-family runtime model. */
final class Sched {
    private final Plugin plugin;

    Sched(Plugin plugin) {
        this.plugin = plugin;
    }

    void next(Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    void timer(Runnable task, long delay, long period) {
        plugin.getServer().getScheduler().runTaskTimer(plugin, task,
                Math.max(1L, delay), Math.max(1L, period));
    }

    void later(Runnable task, long delayTicks) {
        plugin.getServer().getScheduler().runTaskLater(plugin, task, Math.max(1L, delayTicks));
    }
}
