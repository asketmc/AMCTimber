package com.asketmc.timber;

/** Tracks undelivered log yield so completion and shutdown recovery are idempotent. */
final class YieldLedger {
    private int remaining;

    YieldLedger(int total) {
        remaining = Math.max(0, total);
    }

    synchronized int nextStack() {
        return Math.min(64, remaining);
    }

    synchronized void delivered(int amount) {
        if (amount <= 0 || amount > remaining) throw new IllegalArgumentException("invalid delivered amount");
        remaining -= amount;
    }

    synchronized int remaining() {
        return remaining;
    }

    synchronized boolean empty() {
        return remaining == 0;
    }
}
