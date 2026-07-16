package com.asketmc.timber;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** Registry, bounded durable-yield dispatcher, and lifecycle cleanup for landed trunks. */
final class FelledTrunkStore {
    private final Logger log;
    private final RecoveryBudget recoveryBudget;
    private final Map<UUID, FelledTrunk> index = new ConcurrentHashMap<>();
    private final Set<FelledTrunk> all = ConcurrentHashMap.newKeySet();
    private final Deque<PendingYield> pendingYields = new ArrayDeque<>();
    /** Terminal handoffs retained in memory but withheld from delivery until a journal write succeeds. */
    private final Deque<PendingYield> stagedYields = new ArrayDeque<>();
    private final Deque<PendingYieldFile.Entry> dormantYields = new ArrayDeque<>();
    private Path recoveryFile;
    private boolean recoveryStateDirty;
    private int deliveryStepsPerTick = 8;
    private Protection protection;

    FelledTrunkStore(Logger log, RecoveryBudget recoveryBudget) {
        this.log = log;
        this.recoveryBudget = recoveryBudget;
    }

    void updateDeliveryLimit(int steps) {
        deliveryStepsPerTick = Math.max(1, steps);
    }

    void updateProtection(Protection protection) {
        this.protection = protection;
    }

    void initializeRecovery(Path dataDirectory) {
        this.recoveryFile = dataDirectory.resolve("pending-yields.properties");
        try {
            dormantYields.addAll(PendingYieldFile.read(recoveryFile));
            recoveryBudget.restorePending(dormantYields.size());
            if (!dormantYields.isEmpty()) {
                log.warning("loaded " + dormantYields.size() + " pending timber yield record(s)");
            }
        } catch (IOException | RuntimeException failure) {
            quarantineInvalidRecoveryFile(failure);
        }
        promoteDormantYields(deliveryStepsPerTick);
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

    int size() { return all.size(); }

    int pendingYieldCount() { return pendingYields.size() + stagedYields.size() + dormantYields.size(); }

    boolean recoveryCapacityAvailable() {
        RecoveryBudget.Snapshot snapshot = recoveryBudget.snapshot();
        return snapshot.pending() + snapshot.reserved() < snapshot.maxEntries();
    }

    boolean deferYield(UUID ownerId, World world, Location location, Material material, YieldLedger yield,
                       RecoveryBudget.Reservation recoveryReservation) {
        if (yield.empty()) return true;
        if (!PendingYieldFile.supportedYieldMaterial(material)
                || recoveryReservation == null || recoveryReservation.remaining() < 1) return false;
        int amount = yield.remaining();
        PendingYield pending = new PendingYield(UUID.randomUUID(), ownerId, world, location,
                material, new YieldLedger(amount));
        boolean wasDirty = recoveryStateDirty;
        pendingYields.addLast(pending);
        recoveryStateDirty = true;
        if (!persistRecoveryState()) {
            pendingYields.removeLastOccurrence(pending);
            recoveryStateDirty = wasDirty;
            return false;
        }
        if (!recoveryReservation.transfer(1)) {
            pendingYields.removeLastOccurrence(pending);
            recoveryStateDirty = true;
            persistRecoveryState();
            return false;
        }
        yield.delivered(amount);
        log.warning("queued " + amount + " timber item(s) for paced delivery");
        return true;
    }

    /**
     * Transfers a terminal source ledger to store ownership even when the first journal write fails.
     * Staged records cannot deliver until a later successful atomic checkpoint promotes them.
     */
    boolean retainYield(UUID ownerId, World world, Location location, Material material, YieldLedger yield,
                        RecoveryBudget.Reservation recoveryReservation) {
        if (yield.empty()) return true;
        if (!PendingYieldFile.supportedYieldMaterial(material)
                || recoveryReservation == null || recoveryReservation.remaining() < 1) return false;
        int amount = yield.remaining();
        PendingYield pending = new PendingYield(UUID.randomUUID(), ownerId, world, location,
                material, new YieldLedger(amount));
        stagedYields.addLast(pending);
        if (!recoveryReservation.transfer(1)) {
            stagedYields.removeLastOccurrence(pending);
            return false;
        }
        yield.delivered(amount);
        recoveryStateDirty = true;
        boolean durable = persistRecoveryState();
        log.warning("retained " + amount + " timber item(s) for paced delivery"
                + (durable ? "" : "; journal retry pending"));
        return true;
    }

    boolean deferItems(UUID ownerId, World world, Location location, List<ItemStack> items,
                       RecoveryBudget.Reservation recoveryReservation) {
        Map<Material, Integer> totals = new LinkedHashMap<>();
        for (ItemStack item : items) {
            if (item != null && item.getAmount() > 0) totals.merge(item.getType(), item.getAmount(), Integer::sum);
        }
        if (totals.isEmpty()) return true;
        for (Material material : totals.keySet()) {
            if (!PendingYieldFile.supportedYieldMaterial(material)) return false;
        }
        if (recoveryReservation == null || recoveryReservation.remaining() < totals.size()) return false;
        List<PendingYield> added = new ArrayList<>();
        for (Map.Entry<Material, Integer> entry : totals.entrySet()) {
            PendingYield pending = new PendingYield(UUID.randomUUID(), ownerId, world, location,
                    entry.getKey(), new YieldLedger(entry.getValue()));
            pendingYields.addLast(pending);
            added.add(pending);
        }
        boolean wasDirty = recoveryStateDirty;
        recoveryStateDirty = true;
        if (!persistRecoveryState()) {
            pendingYields.removeAll(added);
            recoveryStateDirty = wasDirty;
            return false;
        }
        if (!recoveryReservation.transfer(totals.size())) {
            pendingYields.removeAll(added);
            recoveryStateDirty = true;
            persistRecoveryState();
            return false;
        }
        return true;
    }

    /** Terminal counterpart to deferItems; ownership is retained in a non-deliverable staging queue. */
    boolean retainItems(UUID ownerId, World world, Location location, List<ItemStack> items,
                        RecoveryBudget.Reservation recoveryReservation) {
        Map<Material, Integer> totals = validatedTotals(items);
        if (totals == null) return false;
        if (totals.isEmpty()) return true;
        if (recoveryReservation == null || recoveryReservation.remaining() < totals.size()) return false;
        List<PendingYield> added = new ArrayList<>();
        for (Map.Entry<Material, Integer> entry : totals.entrySet()) {
            PendingYield pending = new PendingYield(UUID.randomUUID(), ownerId, world, location,
                    entry.getKey(), new YieldLedger(entry.getValue()));
            stagedYields.addLast(pending);
            added.add(pending);
        }
        if (!recoveryReservation.transfer(totals.size())) {
            stagedYields.removeAll(added);
            return false;
        }
        recoveryStateDirty = true;
        boolean durable = persistRecoveryState();
        log.warning("retained " + totals.size() + " terminal leaf-yield record(s)"
                + (durable ? "" : "; journal retry pending"));
        return true;
    }

    private static Map<Material, Integer> validatedTotals(List<ItemStack> items) {
        Map<Material, Integer> totals = new LinkedHashMap<>();
        for (ItemStack item : items) {
            if (item != null && item.getAmount() > 0) totals.merge(item.getType(), item.getAmount(), Integer::sum);
        }
        for (Material material : totals.keySet()) {
            if (!PendingYieldFile.supportedYieldMaterial(material)) return null;
        }
        return totals;
    }

    static int deliveryRecords(List<ItemStack> items) {
        Set<Material> materials = java.util.EnumSet.noneOf(Material.class);
        for (ItemStack item : items) if (item != null && item.getAmount() > 0) materials.add(item.getType());
        return materials.size();
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

    void tickYieldDelivery() {
        promoteDormantYields(deliveryStepsPerTick);
        retryPendingYields(System.currentTimeMillis(), false, deliveryStepsPerTick);
        // Checkpoint acknowledged world drops in the same delivery tick. Deferring this to the
        // one-second sweep would leave a much larger crash window in which restored ledgers could
        // repeat items that already entered the world.
        if (recoveryStateDirty) persistRecoveryState();
    }

    void sweep() {
        long now = System.currentTimeMillis();
        for (FelledTrunk trunk : new ArrayList<>(all)) {
            if (!trunk.valid()) {
                try {
                    if (trunk.needsCompletedYieldRecovery()) {
                        if (!trunk.deferCompletedYield(this, "entity invalidation")) continue;
                    } else {
                        trunk.expire();
                    }
                } finally {
                    if (!trunk.needsCompletedYieldRecovery()) drop(trunk);
                }
            } else if (now >= trunk.despawnAtMillis()) {
                try {
                    trunk.expire();
                } finally {
                    drop(trunk);
                }
            }
        }
        if (recoveryStateDirty) persistRecoveryState();
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
            retryPendingYields(System.currentTimeMillis(), true, deliveryStepsPerTick);
            persisted = persistRecoveryState();
        }
        if (persisted) {
            pendingYields.clear();
            stagedYields.clear();
            dormantYields.clear();
        } else {
            log.severe("pending timber yield remains in memory after repeated shutdown persistence failure");
        }
        purgeTagged("disable");
    }

    private void retryPendingYields(long now, boolean force, int maxSteps) {
        boolean changed = false;
        int steps = Math.min(Math.max(0, maxSteps), pendingYields.size());
        for (int step = 0; step < steps; step++) {
            PendingYield pending = pendingYields.pollFirst();
            if (pending == null) break;
            try {
                PendingYield.Attempt attempt = pending.attemptOne(now, force, protection);
                changed |= attempt.changed();
                if (attempt.complete()) {
                    recoveryBudget.delivered();
                    log.info("delivered deferred timber yield");
                } else {
                    pendingYields.addLast(pending);
                }
            } catch (RuntimeException | LinkageError failure) {
                pendingYields.addLast(pending);
                log.warning("deferred timber yield retry failed: " + failure.getClass().getSimpleName());
            }
        }
        if (changed) recoveryStateDirty = true;
    }

    private void promoteDormantYields(int maxPromotions) {
        if (dormantYields.isEmpty()) return;
        int limit = Math.max(1, maxPromotions);
        int visits = Math.min(limit, dormantYields.size());
        for (int visit = 0; visit < visits; visit++) {
            PendingYieldFile.Entry entry = dormantYields.removeFirst();
            World world = Bukkit.getWorld(entry.worldId());
            if (world == null) {
                dormantYields.addLast(entry);
                continue;
            }
            Location location = new Location(world, entry.x(), entry.y(), entry.z());
            pendingYields.addLast(new PendingYield(entry.id(), entry.ownerId(), world, location,
                    entry.material(), new YieldLedger(entry.amount())));
        }
    }

    private boolean persistRecoveryState() {
        if (recoveryFile == null) return true;
        List<PendingYieldFile.Entry> entries = new ArrayList<>(dormantYields);
        for (PendingYield pending : pendingYields) entries.add(pending.snapshot());
        for (PendingYield pending : stagedYields) entries.add(pending.snapshot());
        try {
            PendingYieldFile.write(recoveryFile, entries);
            recoveryStateDirty = false;
            while (!stagedYields.isEmpty()) pendingYields.addLast(stagedYields.removeFirst());
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
