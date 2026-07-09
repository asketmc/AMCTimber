package com.asketmc.timber;

/** Global budget for active falls and landed trunk entities. */
final class EntityBudget {
    record Snapshot(int sessions, int entities, int maxSessions, int maxEntities) {}

    private int maxSessions;
    private int maxEntities;
    private int sessions;
    private int entities;

    EntityBudget(int maxSessions, int maxEntities) {
        updateLimits(maxSessions, maxEntities);
    }

    synchronized void updateLimits(int newMaxSessions, int newMaxEntities) {
        maxSessions = Math.max(1, newMaxSessions);
        maxEntities = Math.max(1, newMaxEntities);
    }

    synchronized Reservation tryReserve(int peakEntities) {
        int requested = Math.max(1, peakEntities);
        if (sessions >= maxSessions || entities + requested > maxEntities) return null;
        sessions++;
        entities += requested;
        return new Reservation(this, requested);
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(sessions, entities, maxSessions, maxEntities);
    }

    private synchronized boolean resize(Reservation reservation, int newCount) {
        if (reservation.closed) return false;
        int normalized = Math.max(0, newCount);
        int delta = normalized - reservation.entities;
        if (delta > 0 && entities + delta > maxEntities) return false;
        entities += delta;
        reservation.entities = normalized;
        return true;
    }

    private synchronized void release(Reservation reservation) {
        if (reservation.closed) return;
        reservation.closed = true;
        sessions = Math.max(0, sessions - 1);
        entities = Math.max(0, entities - reservation.entities);
        reservation.entities = 0;
    }

    static final class Reservation implements AutoCloseable {
        private final EntityBudget owner;
        private int entities;
        private boolean closed;

        private Reservation(EntityBudget owner, int entities) {
            this.owner = owner;
            this.entities = entities;
        }

        boolean resize(int newCount) {
            return owner.resize(this, newCount);
        }

        int entities() {
            return entities;
        }

        boolean closed() {
            return closed;
        }

        @Override
        public void close() {
            owner.release(this);
        }
    }
}
