package com.asketmc.timber;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Region-thread-aware scheduling that works identically on Paper and Folia.
 *
 * <p>Paper has shipped the Folia-compatible scheduler API (global / region / entity schedulers) since
 * 1.20.1, so we can write Folia-correct code with <em>no</em> external library and no reflection: on
 * ordinary Paper these run on the main thread; on Folia they run on the thread that owns the target
 * region or entity. All tasks are auto-cancelled when the plugin disables.
 *
 * <p>Every world/entity mutation in this plugin is funnelled through here so it always lands on the
 * correct region thread — that is the whole of the Folia port.
 */
final class Sched {

    /** True on Folia (and forks) — used to skip cross-region entity scans that only Folia forbids. */
    static final boolean FOLIA = detectFolia();

    private final Plugin plugin;

    Sched(Plugin plugin) { this.plugin = plugin; }

    /** Run once, next tick, on the global region (server-wide work: the despawn sweep, command dispatch). */
    void global(Runnable r) {
        Bukkit.getGlobalRegionScheduler().run(plugin, t -> r.run());
    }

    /** Repeating task on the global region. delay/period are clamped to >= 1 tick (Folia rejects 0). */
    void globalTimer(Runnable r, long delay, long period) {
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> r.run(),
                Math.max(1L, delay), Math.max(1L, period));
    }

    /** Run once, next tick, on the region owning {@code loc}. */
    void atLocation(Location loc, Runnable r) {
        Bukkit.getRegionScheduler().run(plugin, loc, t -> r.run());
    }

    /** Run after {@code delayTicks} on the region owning {@code loc} (clamped to >= 1 tick). */
    void atLocationLater(Location loc, Runnable r, long delayTicks) {
        Bukkit.getRegionScheduler().runDelayed(plugin, loc, t -> r.run(), Math.max(1L, delayTicks));
    }

    /** Run on the region currently owning {@code entity} (no-op if the entity has been removed). */
    void atEntity(Entity entity, Runnable r) {
        entity.getScheduler().run(plugin, t -> r.run(), null);
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
