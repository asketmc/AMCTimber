package com.asketmc.timber;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * In-memory registry of live {@link FelledTrunk}s (a trunk is reachable from EVERY one of its hitbox
 * UUIDs), plus the cleanup "spine": a per-second sweep that despawns expired/invalid trunks, and a
 * tagged-entity purge run both at start-up and on disable so neither a crash nor a /reload can strand a
 * single display. Our entities are {@code persistent=false} so they never reach the region files anyway —
 * the purge is the belt-and-braces guarantee of zero accumulation.
 *
 * <p><b>Folia.</b> Entity removal must happen on the region thread that owns the entity, so the sweep
 * dispatches each trunk's removal to its own region via {@link Sched}. The cross-region world scan in
 * {@link #purgeTagged} is skipped on Folia (where {@code /reload} is unsupported and persistent=false
 * entities never survive a restart, so there is nothing to reconcile).
 */
final class FelledTrunkStore {
    private final Logger log;
    private final Map<UUID, FelledTrunk> index = new ConcurrentHashMap<>();
    private final Set<FelledTrunk> all = ConcurrentHashMap.newKeySet();

    FelledTrunkStore(Logger log) { this.log = log; }

    void register(FelledTrunk t) {
        all.add(t);
        for (UUID id : t.allIds()) index.put(id, t);
    }

    FelledTrunk byInteraction(Entity e) {
        return e == null ? null : index.get(e.getUniqueId());
    }

    void drop(FelledTrunk t) {
        all.remove(t);
        for (UUID id : t.allIds()) index.remove(id);
    }

    int size() { return all.size(); }

    /** Nearest valid trunk to a point within radius (used by the /amctimber test hooks). */
    FelledTrunk nearest(Location from, double radius) {
        FelledTrunk best = null;
        double bestSq = radius * radius;
        for (FelledTrunk t : all) {
            if (!t.valid()) continue;
            Location c = t.center();
            if (c.getWorld() != from.getWorld()) continue;
            double dsq = c.distanceSquared(from);
            if (dsq <= bestSq) { bestSq = dsq; best = t; }
        }
        return best;
    }

    /**
     * Despawn expired trunks and reap any whose entities went invalid (e.g. chunk unloaded). Runs on the
     * global region; the actual entity removal is dispatched to each trunk's own region thread (Folia-safe,
     * a plain main-thread call on Paper).
     */
    void sweep(Fx fx, Sched sched) {
        long now = System.currentTimeMillis();
        for (FelledTrunk t : new ArrayList<>(all)) {
            if (!t.valid()) { drop(t); sched.atLocation(t.center(), t::remove); continue; }
            if (now >= t.despawnAtMillis()) {
                drop(t);
                Location c = t.center();
                sched.atLocation(c, () -> { fx.leafRustle(c); t.remove(); });
            }
        }
    }

    /** Kill every timber-tagged entity across all loaded worlds — run at enable AND disable (Paper only). */
    int purgeTagged(String why) {
        if (Sched.FOLIA) return 0;   // /reload unsupported + persistent=false ⇒ nothing to reconcile on Folia
        int killed = 0;
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (e.getScoreboardTags().contains(Tags.ANIM) || e.getScoreboardTags().contains(Tags.TRUNK)) {
                    e.remove();
                    killed++;
                }
            }
        }
        if (killed > 0) log.info("purged " + killed + " orphan timber entit(y/ies) (" + why + ").");
        return killed;
    }

    /** On disable: drop all tracked trunks, then purge anything tagged (covers mid-fall rigs too). */
    void shutdown() {
        for (FelledTrunk t : all) {
            try { t.remove(); } catch (Throwable ignored) { /* best-effort; persistent=false ⇒ no disk leak */ }
        }
        all.clear();
        index.clear();
        purgeTagged("disable");
    }
}
