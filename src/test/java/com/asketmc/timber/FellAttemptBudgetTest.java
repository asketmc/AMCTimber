package com.asketmc.timber;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FellAttemptBudgetTest {
    @Test
    @Tag("P0")
    void readAndHookCapsFailClosedWithStickyReasons() {
        FellAttemptBudget reads = new FellAttemptBudget(2, 5, 1_000, () -> 0L);
        assertTrue(reads.takeRead());
        assertTrue(reads.takeRead());
        assertFalse(reads.takeRead());
        assertEquals("scan-read-budget", reads.reason());

        FellAttemptBudget hooks = new FellAttemptBudget(5, 2, 1_000, () -> 0L);
        assertTrue(hooks.takeHookCall());
        assertTrue(hooks.takeHookCall());
        assertFalse(hooks.takeHookCall());
        assertEquals("protection-call-budget", hooks.reason());
    }

    @Test
    void elapsedDeadlineCoversCachedCpuWorkAndIsOverflowSafe() {
        AtomicLong clock = new AtomicLong(10L);
        FellAttemptBudget budget = new FellAttemptBudget(5, 5, 20L, clock::get);
        clock.set(29L);
        assertTrue(budget.checkpoint());
        clock.set(30L);
        assertFalse(budget.checkpoint());
        assertEquals("attempt-time-budget", budget.reason());

        AtomicLong nearMax = new AtomicLong(Long.MAX_VALUE - 5L);
        FellAttemptBudget saturated = new FellAttemptBudget(1, 1, 100L, nearMax::get);
        assertTrue(saturated.checkpoint());
    }
}
