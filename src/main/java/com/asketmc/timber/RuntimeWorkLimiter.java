package com.asketmc.timber;

import java.util.ArrayDeque;
import java.util.Deque;

/** One server-wide per-tick governor for atomic launch and paced entity/crush operations. */
final class RuntimeWorkLimiter {
    record Snapshot(int blockOpsLeft, int entityOpsLeft, int queuedEntityOps,
                    int crushQueriesLeft, int crushTargetsLeft) {}

    private record Phase(int units, Runnable task) {}

    private final Deque<Phase> phases = new ArrayDeque<>();
    private int maxBlockOps;
    private int maxEntityOps;
    private int maxQueuedEntityOps;
    private int maxCrushQueries;
    private int maxCrushTargets;
    private long maxCrushNanos;
    private int blockOpsLeft;
    private int entityOpsLeft;
    private int queuedEntityOps;
    private int reservedQueuedEntityOps;
    private int crushQueriesLeft;
    private int crushTargetsLeft;
    private long crushDeadlineNanos;

    RuntimeWorkLimiter(TimberConfig cfg) { updateLimits(cfg); }

    synchronized void updateLimits(TimberConfig cfg) {
        maxBlockOps = cfg.launchBlockOpsPerTick;
        maxEntityOps = cfg.entityOperationsPerTick;
        maxQueuedEntityOps = cfg.maxQueuedEntityOperations;
        maxCrushQueries = cfg.crushQueriesPerTick;
        maxCrushTargets = cfg.crushTargetsPerTick;
        maxCrushNanos = cfg.crushMaxMicrosPerTick * 1_000L;
        blockOpsLeft = Math.min(blockOpsLeft, maxBlockOps);
        entityOpsLeft = Math.min(entityOpsLeft, maxEntityOps);
    }

    void beginTick() {
        synchronized (this) {
            blockOpsLeft = maxBlockOps;
            // A reload may lower the configured allowance below work that was already promised.
            // Drain that old work without letting new launches use the temporary compatibility headroom.
            entityOpsLeft = Math.max(maxEntityOps, largestQueuedPhase());
            crushQueriesLeft = maxCrushQueries;
            crushTargetsLeft = maxCrushTargets;
            long now = System.nanoTime();
            crushDeadlineNanos = now > Long.MAX_VALUE - maxCrushNanos
                    ? Long.MAX_VALUE : now + maxCrushNanos;
        }
        drainEntityPhases();
        synchronized (this) {
            entityOpsLeft = Math.min(entityOpsLeft, maxEntityOps);
        }
    }

    synchronized Admission tryReserveLaunch(int blockUnits, int entityUnits) {
        int blocks = Math.max(0, blockUnits);
        int entities = Math.max(0, entityUnits);
        if (blocks > maxBlockOps || entities > maxEntityOps
                || blocks > blockOpsLeft || entities > entityOpsLeft) return null;
        blockOpsLeft -= blocks;
        entityOpsLeft -= entities;
        return new Admission(this, blocks, entities);
    }

    synchronized boolean enqueueEntityPhase(int units, Runnable task) {
        int normalized = Math.max(1, units);
        if (normalized > maxEntityOps
                || (long) queuedEntityOps + reservedQueuedEntityOps + normalized > maxQueuedEntityOps) return false;
        phases.addLast(new Phase(normalized, task));
        queuedEntityOps += normalized;
        return true;
    }

    synchronized QueueReservation tryReserveQueue(int units) {
        int normalized = Math.max(0, units);
        if ((long) queuedEntityOps + reservedQueuedEntityOps + normalized > maxQueuedEntityOps) return null;
        reservedQueuedEntityOps += normalized;
        return new QueueReservation(this, normalized);
    }

    synchronized boolean enqueueReserved(int units, Runnable task, QueueReservation reservation) {
        int normalized = Math.max(1, units);
        if (reservation == null || reservation.closed || normalized > reservation.remaining) return false;
        reservation.remaining -= normalized;
        reservedQueuedEntityOps -= normalized;
        phases.addLast(new Phase(normalized, task));
        queuedEntityOps += normalized;
        return true;
    }

    private void drainEntityPhases() {
        while (true) {
            Phase phase;
            synchronized (this) {
                phase = phases.peekFirst();
                if (phase == null || phase.units > entityOpsLeft) return;
                phases.removeFirst();
                queuedEntityOps -= phase.units;
                entityOpsLeft -= phase.units;
            }
            try {
                phase.task.run();
            } catch (RuntimeException | LinkageError ignored) {
                // Every production phase contains its own failure path. This last-resort containment
                // keeps one defective phase from terminating the shared repeating governor task.
            }
        }
    }

    private int largestQueuedPhase() {
        int largest = 0;
        for (Phase phase : phases) largest = Math.max(largest, phase.units);
        return largest;
    }

    synchronized boolean takeCrushQuery() {
        if (!crushTimeAvailable() || crushQueriesLeft <= 0) return false;
        crushQueriesLeft--;
        return true;
    }

    synchronized boolean takeCrushTarget() {
        if (!crushTimeAvailable() || crushTargetsLeft <= 0) return false;
        crushTargetsLeft--;
        return true;
    }

    synchronized boolean crushTimeAvailable() {
        return System.nanoTime() - crushDeadlineNanos < 0;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(blockOpsLeft, entityOpsLeft, queuedEntityOps,
                crushQueriesLeft, crushTargetsLeft);
    }

    private synchronized void release(Admission admission) {
        if (admission.closed) return;
        admission.closed = true;
        if (!admission.blocksCommitted) {
            blockOpsLeft = Math.min(maxBlockOps, blockOpsLeft + admission.blockUnits);
        }
        if (!admission.entitiesCommitted) {
            entityOpsLeft = Math.min(maxEntityOps, entityOpsLeft + admission.entityUnits);
        }
    }

    private synchronized void release(QueueReservation reservation) {
        if (reservation.closed) return;
        reservation.closed = true;
        reservedQueuedEntityOps -= reservation.remaining;
        reservation.remaining = 0;
    }

    static final class Admission implements AutoCloseable {
        private final RuntimeWorkLimiter owner;
        private final int blockUnits;
        private final int entityUnits;
        private boolean blocksCommitted;
        private boolean entitiesCommitted;
        private boolean closed;

        private Admission(RuntimeWorkLimiter owner, int blockUnits, int entityUnits) {
            this.owner = owner;
            this.blockUnits = blockUnits;
            this.entityUnits = entityUnits;
        }

        void commitBlocks() { blocksCommitted = true; }

        void commit() {
            blocksCommitted = true;
            entitiesCommitted = true;
        }

        @Override public void close() { owner.release(this); }
    }

    static final class QueueReservation implements AutoCloseable {
        private final RuntimeWorkLimiter owner;
        private int remaining;
        private boolean closed;

        private QueueReservation(RuntimeWorkLimiter owner, int remaining) {
            this.owner = owner;
            this.remaining = remaining;
        }

        int remaining() { return remaining; }

        @Override public void close() { owner.release(this); }
    }
}
