package com.asketmc.timber;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** Registry and lifecycle cleanup for landed trunks. */
final class FelledTrunkStore {
    private final Logger log;
    private final Map<UUID, FelledTrunk> index = new ConcurrentHashMap<>();
    private final Set<FelledTrunk> all = ConcurrentHashMap.newKeySet();
    private final Set<PendingYield> pendingYields = ConcurrentHashMap.newKeySet();
    private final List<PendingYieldFile.Entry> dormantYields = new ArrayList<>();
    private EntityBudget budget;
    private Path recoveryFile;
    private boolean recoveryStateDirty;

    FelledTrunkStore(Logger log) {
        this.log = log;
    }

    void initializeRecovery(Path dataDirectory, EntityBudget entityBudget) {
        this.budget = entityBudget;
        this.recoveryFile = dataDirectory.resolve("pending-yields.properties");
        try {
            dormantYields.addAll(PendingYieldFile.read(recoveryFile));
            if (!dormantYields.isEmpty()) {
                log.warning("loaded " + dormantYields.size() + " pending timber yield record(s)");
            }
        } catch (IOException | RuntimeException failure) {
            quarantineInvalidRecoveryFile(failure);
        }
        promoteDormantYields();
        persistRecoveryState();
    }

    void register(FelledTrunk trunk) {
        all.add(trunk);
        for (UUID id : trunk.allIds()) index.put(id, trunk);
    }

    FelledTrunk byInteraction(Entity entity) {
        return entity == null ? null : index.get(entity.getUniqueId());
    }

    void drop(FelledTrunk trunk) {
        all.remove(trunk);
        for (UUID id : trunk.allIds()) index.remove(id);
    }

    int size() {
        return all.size();
    }

    int pendingYieldCount() {
        return pendingYields.size() + dormantYields.size();
    }

    boolean recoveryCapacityAvailable() {
        if (budget == null) return false;
        return pendingYieldCount() + budget.snapshot().maxSessions() <= PendingYieldFile.MAX_ENTRIES;
    }

    boolean deferYield(World world, Location location, org.bukkit.Material material,
                       YieldLedger yield, EntityBudget.Reservation reservation) {
        if (yield.empty() || pendingYieldCount() >= PendingYieldFile.MAX_ENTRIES
                || !reservation.resize(0)) return false;
        pendingYields.add(new PendingYield(world, location, material, yield, reservation));
        log.warning("deferred " + yield.remaining() + " logs after item delivery was rejected");
        recoveryStateDirty = true;
        persistRecoveryState();
        return true;
    }

    FelledTrunk nearest(Location from, double radius) {
        FelledTrunk best = null;
        double bestSquared = radius * radius;
        for (FelledTrunk trunk : all) {
            if (!trunk.valid()) continue;
            Location center = trunk.center();
            if (center.getWorld() != from.getWorld()) continue;
            double distanceSquared = center.distanceSquared(from);
            if (distanceSquared <= bestSquared) {
                bestSquared = distanceSquared;
                best = trunk;
            }
        }
        return best;
    }

    void sweep() {
        long now = System.currentTimeMillis();
        promoteDormantYields();
        retryPendingYields(now, false);
        for (FelledTrunk trunk : new ArrayList<>(all)) {
            if (!trunk.valid()) {
                try {
                    if (!trunk.deferCompletedYield(this, "entity invalidation")) trunk.expire();
                } finally {
                    drop(trunk);
                }
            } else if (now >= trunk.despawnAtMillis()) {
                try {
                    trunk.expire();
                } finally {
                    drop(trunk);
                }
            }
        }
    }

    int purgeTagged(String reason) {
        int killed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!entity.getScoreboardTags().contains(Tags.ANIM)
                        && !entity.getScoreboardTags().contains(Tags.TRUNK)) continue;
                entity.remove();
                killed++;
            }
        }
        if (killed > 0) log.info("purged " + killed + " orphan timber entities (" + reason + ").");
        return killed;
    }

    void shutdown() {
        for (FelledTrunk trunk : new ArrayList<>(all)) {
            try {
                trunk.deferOnShutdown(this);
            } catch (RuntimeException | LinkageError failure) {
                log.warning("trunk shutdown recovery failed: " + failure);
            }
        }
        all.clear();
        index.clear();
        boolean persisted = persistRecoveryState();
        if (!persisted) {
            retryPendingYields(System.currentTimeMillis(), true);
            persistRecoveryState();
        }
        for (PendingYield pending : new ArrayList<>(pendingYields)) {
            pending.close();
        }
        pendingYields.clear();
        dormantYields.clear();
        purgeTagged("disable");
    }

    private void retryPendingYields(long now, boolean force) {
        boolean changed = false;
        for (PendingYield pending : new ArrayList<>(pendingYields)) {
            try {
                PendingYield.Attempt attempt = pending.attempt(now, force);
                changed |= attempt.changed();
                if (attempt.complete()) {
                    pendingYields.remove(pending);
                    pending.close();
                    log.info("delivered deferred timber yield");
                }
            } catch (RuntimeException | LinkageError failure) {
                log.warning("deferred timber yield retry failed: " + failure.getClass().getSimpleName());
            }
        }
        if (changed) {
            promoteDormantYields();
            recoveryStateDirty = true;
        }
        if (recoveryStateDirty) {
            if (!persistRecoveryState() && changed) {
                quarantineRecoveryFile("stale-after-delivery");
                persistRecoveryState();
            }
        }
    }

    private void promoteDormantYields() {
        if (budget == null || dormantYields.isEmpty()) return;
        Iterator<PendingYieldFile.Entry> iterator = dormantYields.iterator();
        while (iterator.hasNext()) {
            PendingYieldFile.Entry entry = iterator.next();
            World world = Bukkit.getWorld(entry.worldId());
            if (world == null) continue;
            EntityBudget.Reservation reservation = budget.tryReserve(0);
            if (reservation == null) return;
            if (!reservation.resize(0)) {
                reservation.close();
                return;
            }
            Location location = new Location(world, entry.x(), entry.y(), entry.z());
            pendingYields.add(new PendingYield(entry.id(), world, location, entry.material(),
                    new YieldLedger(entry.amount()), reservation));
            iterator.remove();
        }
    }

    private boolean persistRecoveryState() {
        if (recoveryFile == null) return true;
        List<PendingYieldFile.Entry> entries = new ArrayList<>(dormantYields);
        for (PendingYield pending : pendingYields) entries.add(pending.snapshot());
        try {
            PendingYieldFile.write(recoveryFile, entries);
            recoveryStateDirty = false;
            return true;
        } catch (IOException | RuntimeException failure) {
            recoveryStateDirty = true;
            log.severe("could not persist pending timber yield: " + failure.getMessage());
            return false;
        }
    }

    private void quarantineInvalidRecoveryFile(Exception failure) {
        log.severe("pending-yield recovery file is invalid: " + failure.getMessage());
        quarantineRecoveryFile("invalid");
    }

    private void quarantineRecoveryFile(String reason) {
        if (recoveryFile == null || !Files.exists(recoveryFile)) return;
        Path quarantine = recoveryFile.resolveSibling(
                "pending-yields." + reason + '-' + System.currentTimeMillis() + ".properties");
        try {
            Files.move(recoveryFile, quarantine, StandardCopyOption.REPLACE_EXISTING);
            log.severe("moved recovery data to " + quarantine.getFileName());
        } catch (IOException moveFailure) {
            log.severe("could not quarantine recovery data: " + moveFailure.getMessage());
        }
    }
}
