package com.asketmc.timber;

/** Durable-yield capacity that is deliberately independent of live display/session capacity. */
final class RecoveryBudget {
    record Snapshot(int reserved, int pending, int maxEntries) {}

    private int maxEntries;
    private int reserved;
    private int pending;

    RecoveryBudget(int maxEntries) {
        updateLimit(maxEntries);
    }

    synchronized void updateLimit(int entries) {
        maxEntries = Math.max(1, entries);
    }

    synchronized void restorePending(int entries) {
        if (entries < 0) throw new IllegalArgumentException("negative recovery capacity");
        pending += entries;
    }

    synchronized Reservation tryReserve(int entries) {
        int requested = Math.max(0, entries);
        if (pending + reserved + requested > maxEntries) return null;
        reserved += requested;
        return new Reservation(this, requested);
    }

    private synchronized boolean transfer(Reservation reservation, int entries) {
        if (reservation.closed || entries < 0 || entries > reservation.remaining) return false;
        reservation.remaining -= entries;
        reserved -= entries;
        pending += entries;
        return true;
    }

    synchronized void delivered() {
        if (pending <= 0) throw new IllegalStateException("recovery accounting underflow");
        pending--;
    }

    private synchronized void release(Reservation reservation) {
        if (reservation.closed) return;
        reservation.closed = true;
        reserved -= reservation.remaining;
        reservation.remaining = 0;
    }

    synchronized Snapshot snapshot() { return new Snapshot(reserved, pending, maxEntries); }

    static final class Reservation implements AutoCloseable {
        private final RecoveryBudget owner;
        private int remaining;
        private boolean closed;

        private Reservation(RecoveryBudget owner, int remaining) {
            this.owner = owner;
            this.remaining = remaining;
        }

        boolean transfer(int entries) { return owner.transfer(this, entries); }

        int remaining() { return remaining; }

        @Override public void close() { owner.release(this); }
    }
}
