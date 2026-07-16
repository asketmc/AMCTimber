package com.asketmc.timber;

import java.util.Objects;
import java.util.function.LongSupplier;

/** Fail-closed read, protection-call, and elapsed-time budget for one untrusted fell stage. */
final class FellAttemptBudget {
    private final int maxReads;
    private final int maxHookCalls;
    private final LongSupplier nanoTime;
    private final long deadlineNanos;
    private int reads;
    private int hookCalls;
    private String blockedReason;

    FellAttemptBudget(int maxReads, int maxHookCalls, long maxNanos, LongSupplier nanoTime) {
        this.maxReads = Math.max(0, maxReads);
        this.maxHookCalls = Math.max(0, maxHookCalls);
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        long now = nanoTime.getAsLong();
        long duration = Math.max(1L, maxNanos);
        this.deadlineNanos = now > Long.MAX_VALUE - duration ? Long.MAX_VALUE : now + duration;
    }

    static FellAttemptBudget from(TimberConfig cfg) {
        return new FellAttemptBudget(cfg.maxScanReads, cfg.maxProtectionHookCalls,
                cfg.maxAttemptMicros * 1_000L, System::nanoTime);
    }

    boolean checkpoint() {
        if (blockedReason != null) return false;
        if (nanoTime.getAsLong() - deadlineNanos >= 0) {
            blockedReason = "attempt-time-budget";
            return false;
        }
        return true;
    }

    boolean takeRead() {
        if (!checkpoint()) return false;
        if (reads >= maxReads) {
            blockedReason = "scan-read-budget";
            return false;
        }
        reads++;
        return true;
    }

    boolean takeHookCall() {
        if (!checkpoint()) return false;
        if (hookCalls >= maxHookCalls) {
            blockedReason = "protection-call-budget";
            return false;
        }
        hookCalls++;
        return true;
    }

    boolean blocked() {
        return blockedReason != null || !checkpoint();
    }

    String reason() {
        return blockedReason == null ? "attempt-budget" : blockedReason;
    }

    int reads() { return reads; }

    int hookCalls() { return hookCalls; }
}
