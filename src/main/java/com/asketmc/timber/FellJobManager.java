package com.asketmc.timber;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Coordinates bounded, transactional handoff from world blocks to a falling session. */
final class FellJobManager {
    private final EntityBudget budget;
    private final Set<BlockKey> activeCuts = ConcurrentHashMap.newKeySet();
    private final Set<FellSession> activeSessions = ConcurrentHashMap.newKeySet();

    FellJobManager(EntityBudget budget) {
        this.budget = budget;
    }

    int activeCount() {
        return activeCuts.size();
    }

    boolean tryFell(Player owner, TreeShape shape, FellRuntime runtime) {
        TimberConfig cfg = runtime.config();
        if (!runtime.store().recoveryCapacityAvailable()) {
            runtime.debug().full("fell rejected: recovery journal capacity is exhausted");
            return false;
        }
        if (!landingPathLoaded(shape)) {
            runtime.debug().full("fell rejected: landing path crosses an unloaded chunk");
            return false;
        }
        if (!ToppleAnimator.canRenderLogs(cfg, shape)) {
            runtime.debug().full("fell rejected: logs=" + shape.logCount()
                    + " exceeds display cap " + cfg.maxDisplayEntities);
            return false;
        }
        int largestPhase = ToppleAnimator.renderedCount(cfg, shape)
                + FelledTrunk.hitboxCount(shape.height);
        if (largestPhase > cfg.entityOperationsPerTick
                || FellWorkEstimate.launchEntityOps(cfg, shape) > cfg.entityOperationsPerTick) {
            runtime.debug().full("fell rejected: one atomic entity phase exceeds per-tick work cap");
            return false;
        }

        int blockWork = FellWorkEstimate.launchBlockOps(shape, cfg.leafLoot);
        int entityWork = FellWorkEstimate.launchEntityOps(cfg, shape);
        RuntimeWorkLimiter.Admission workAdmission = runtime.workLimiter()
                .tryReserveLaunch(blockWork, entityWork);
        if (workAdmission == null) {
            runtime.debug().full("fell rejected by global per-tick launch budget");
            return false;
        }
        RuntimeWorkLimiter.QueueReservation queueReservation = runtime.workLimiter()
                .tryReserveQueue(FellWorkEstimate.queuedEntityOps(cfg, shape));
        if (queueReservation == null) {
            workAdmission.close();
            runtime.debug().full("fell rejected by global entity-work queue capacity");
            return false;
        }

        BlockKey cutKey = BlockKey.from(shape);
        synchronized (activeCuts) {
            if (activeCuts.size() >= cfg.maxConcurrentFells || !activeCuts.add(cutKey)) {
                workAdmission.close();
                queueReservation.close();
                return false;
            }
        }

        EntityBudget.Reservation reservation = budget.tryReserve(ToppleAnimator.plannedPeakEntities(cfg, shape));
        if (reservation == null) {
            release(cutKey);
            workAdmission.close();
            queueReservation.close();
            runtime.debug().full("fell rejected by global entity/session budget");
            return false;
        }

        WorldMutationJournal journal = new WorldMutationJournal(shape.world);
        ToppleAnimator.Rig rig = null;
        RecoveryBudget.Reservation recoveryReservation = null;
        FellSession session;
        try {
            // Ground probing, footprint construction, protection traversal and loot reads are real
            // launch work even when policy or state rejects the fell. Never refund those block units.
            workAdmission.commitBlocks();
            double yDrop = groundDrop(shape);
            LandingPlan landing = LandingPlan.compute(shape, cfg, yDrop);
            FellAttemptBudget authorizationBudget = FellAttemptBudget.from(cfg);
            if (owner != null && !runtime.protection().canFell(owner, shape, authorizationBudget)) {
                reservation.close();
                release(cutKey);
                workAdmission.close();
                queueReservation.close();
                runtime.debug().full("fell rejected by source protection policy");
                return false;
            }

            List<ItemStack> leafLoot = cfg.leafLoot ? collectLeafLoot(shape) : List.of();
            for (ItemStack item : leafLoot) {
                if (!PendingYieldFile.supportedYieldMaterial(item.getType())) {
                    reservation.close();
                    release(cutKey);
                    workAdmission.close();
                    queueReservation.close();
                    runtime.debug().warn("fell rejected: unsupported recovery material " + item.getType());
                    return false;
                }
            }
            if (owner != null && !runtime.protection().canLand(
                    owner, shape, landing, leafLoot, cfg.logsYielded(shape.logCount()),
                    authorizationBudget)) {
                reservation.close();
                release(cutKey);
                workAdmission.close();
                queueReservation.close();
                runtime.debug().full("fell rejected by landing protection policy");
                return false;
            }
            int recoveryRecords = FelledTrunkStore.deliveryRecords(leafLoot)
                    + (cfg.logsYielded(shape.logCount()) > 0 ? 1 : 0);
            recoveryReservation = runtime.recoveryBudget().tryReserve(recoveryRecords);
            if (recoveryReservation == null) {
                reservation.close();
                release(cutKey);
                workAdmission.close();
                queueReservation.close();
                runtime.debug().full("fell rejected by durable-yield capacity");
                return false;
            }

            List<TreeShape.Node> snapshots = mutationSnapshots(shape);
            if (!journal.preflight(snapshots)) {
                recoveryReservation.close();
                reservation.close();
                release(cutKey);
                workAdmission.close();
                queueReservation.close();
                runtime.debug().full("fell rejected: world changed after scan");
                return false;
            }

            removeTree(journal, shape);

            runtime.effects().crackStart(new Location(shape.world,
                    shape.cutX + 0.5, shape.cutY, shape.cutZ + 0.5));
            // Entity work becomes spent immediately before the first spawn attempt.
            workAdmission.commit();
            rig = ToppleAnimator.spawn(cfg, shape);

            FellLifecycle lifecycle = new FellLifecycle();
            YieldLedger yield = new YieldLedger(cfg.logsYielded(shape.logCount()));
            UUID ownerId = owner == null ? null : owner.getUniqueId();
            session = new FellSession(runtime.scheduler(), runtime.debug(), this, runtime.store(),
                    cfg, runtime.effects(), runtime.xpBridge(), runtime.messages(), runtime.protection(),
                    runtime.workLimiter(), runtime.crushDispatcher(), shape, rig, landing,
                    owner, ownerId, cutKey, leafLoot, yield, lifecycle, reservation,
                    recoveryReservation, queueReservation);
        } catch (RuntimeException | LinkageError failure) {
            int restored = journal.rollback();
            int restoreFailures = journal.rollbackFailures();
            ToppleAnimator.remove(rig);
            if (recoveryReservation != null) recoveryReservation.close();
            reservation.close();
            release(cutKey);
            workAdmission.close();
            queueReservation.close();
            runtime.debug().warn("fell launch rolled back at " + shape.cutX + ',' + shape.cutY + ',' + shape.cutZ
                    + ": " + failure.getClass().getSimpleName() + " (restored " + restored
                    + " blocks, restore failures " + restoreFailures + ")");
            return false;
        }

        // Ownership transfers after the destructive transaction commits. start() contains and recovers
        // scheduler failures, so a committed fell can never re-enter the rollback path or duplicate yield.
        journal.commit();
        workAdmission.close();
        activeSessions.add(session);
        session.start();
        return true;
    }

    void finish(FellSession session, BlockKey cutKey) {
        activeSessions.remove(session);
        release(cutKey);
    }

    private void release(BlockKey cutKey) {
        synchronized (activeCuts) {
            activeCuts.remove(cutKey);
        }
    }

    void shutdown() {
        for (FellSession session : new ArrayList<>(activeSessions)) {
            try {
                session.abortOnShutdown();
            } catch (RuntimeException | LinkageError failure) {
                // Continue releasing every other session; fail() is designed not to throw.
            }
        }
        activeSessions.clear();
        synchronized (activeCuts) {
            activeCuts.clear();
        }
    }

    /** Exact snapshot set that must remain stable between scan and mutation, including kept stumps. */
    static List<TreeShape.Node> mutationSnapshots(TreeShape shape) {
        List<TreeShape.Node> nodes = new ArrayList<>(shape.stumps.size() + shape.attachments.size()
                + shape.leaves.size() + shape.logs.size());
        nodes.addAll(shape.stumps);
        nodes.addAll(shape.attachments);
        nodes.addAll(shape.leaves);
        nodes.addAll(shape.logs);
        return nodes;
    }

    /** Exact production removal order, shared by the Paper jungle fixture. */
    static void removeTree(WorldMutationJournal journal, TreeShape shape) {
        for (TreeShape.Node node : shape.attachments) requireRemoved(journal, node);
        for (TreeShape.Node node : shape.leaves) requireRemoved(journal, node);
        for (TreeShape.Node node : shape.logs) requireRemoved(journal, node);
    }

    private static void requireRemoved(WorldMutationJournal journal, TreeShape.Node node) {
        if (!journal.remove(node)) throw new IllegalStateException("world snapshot changed during commit");
    }

    private static List<ItemStack> collectLeafLoot(TreeShape shape) {
        Map<Material, Integer> counts = new HashMap<>();
        World world = shape.world;
        List<TreeShape.Node> canopy = new ArrayList<>(shape.leaves.size() + shape.attachments.size());
        canopy.addAll(shape.leaves);
        canopy.addAll(shape.attachments);
        for (TreeShape.Node node : canopy) {
            Block block = world.getBlockAt(node.x, node.y, node.z);
            if (!WorldMutationJournal.sameSnapshot(node.data, block.getBlockData())) continue;
            for (ItemStack item : block.getDrops()) {
                if (item != null && item.getAmount() > 0) {
                    counts.merge(item.getType(), item.getAmount(), Integer::sum);
                }
            }
        }
        List<ItemStack> output = new ArrayList<>();
        for (Map.Entry<Material, Integer> entry : counts.entrySet()) {
            int remaining = entry.getValue();
            while (remaining > 0) {
                int stack = Math.min(64, remaining);
                output.add(new ItemStack(entry.getKey(), stack));
                remaining -= stack;
            }
        }
        return output;
    }

    private static double groundDrop(TreeShape shape) {
        World world = shape.world;
        int midX = (int) Math.floor(shape.pivotX + shape.dirX * shape.height * 0.5);
        int midZ = (int) Math.floor(shape.pivotZ + shape.dirZ * shape.height * 0.5);
        if (!world.isChunkLoaded(midX >> 4, midZ >> 4)) return 0;
        int startY = (int) Math.floor(shape.pivotY) - 1;
        for (int y = startY; y >= world.getMinHeight(); y--) {
            Block block = world.getBlockAt(midX, y, midZ);
            Material material = block.getType();
            if (block.isLiquid() || (material.isSolid() && !TreeScanner.isLeaf(material))) {
                return Math.max(0, shape.pivotY - (y + 1));
            }
        }
        return 0;
    }

    private static boolean landingPathLoaded(TreeShape shape) {
        int steps = Math.max(1, (int) Math.ceil(Math.max(2.0, shape.height) / 4.0));
        for (int step = 0; step <= steps; step++) {
            double distance = Math.max(2.0, shape.height) * step / steps;
            int x = (int) Math.floor(shape.pivotX + shape.dirX * distance);
            int z = (int) Math.floor(shape.pivotZ + shape.dirZ * distance);
            if (!shape.world.isChunkLoaded(x >> 4, z >> 4)) return false;
        }
        return true;
    }
}
