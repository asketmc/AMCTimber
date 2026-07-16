package com.asketmc.timber;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeWorkLimiterTest {
    @Test
    @Tag("P0")
    void launchReservationsAreGlobalPerTickAndRejectedWorkReturnsCapacity() {
        TimberConfig cfg = config(300, 100, 200);
        RuntimeWorkLimiter limiter = new RuntimeWorkLimiter(cfg);
        limiter.beginTick();

        RuntimeWorkLimiter.Admission first = limiter.tryReserveLaunch(200, 60);
        assertNotNull(first);
        assertNull(limiter.tryReserveLaunch(101, 41));
        first.close();
        assertNotNull(limiter.tryReserveLaunch(300, 100));
    }

    @Test
    @Tag("P0")
    void spentBlockWorkIsNotRefundedWhenEntityWorkNeverStarts() {
        RuntimeWorkLimiter limiter = new RuntimeWorkLimiter(config(300, 100, 200));
        limiter.beginTick();

        RuntimeWorkLimiter.Admission admission = limiter.tryReserveLaunch(200, 60);
        assertNotNull(admission);
        admission.commitBlocks();
        admission.close();

        assertEquals(100, limiter.snapshot().blockOpsLeft());
        assertEquals(100, limiter.snapshot().entityOpsLeft());
        assertNull(limiter.tryReserveLaunch(101, 0));
    }

    @Test
    void queuedEntityPhasesDrainFifoWithinOneSharedAllowance() {
        TimberConfig cfg = config(300, 100, 200);
        RuntimeWorkLimiter limiter = new RuntimeWorkLimiter(cfg);
        AtomicInteger order = new AtomicInteger();
        assertTrue(limiter.enqueueEntityPhase(70, () -> assertEquals(0, order.getAndIncrement())));
        assertTrue(limiter.enqueueEntityPhase(40, () -> assertEquals(1, order.getAndIncrement())));

        limiter.beginTick();
        assertEquals(1, order.get());
        assertEquals(40, limiter.snapshot().queuedEntityOps());
        limiter.beginTick();
        assertEquals(2, order.get());
    }

    @Test
    void queueAndPerPhaseCapsFailClosed() {
        RuntimeWorkLimiter limiter = new RuntimeWorkLimiter(config(300, 100, 120));
        assertFalse(limiter.enqueueEntityPhase(101, () -> {}));
        assertTrue(limiter.enqueueEntityPhase(80, () -> {}));
        assertFalse(limiter.enqueueEntityPhase(50, () -> {}));
    }

    @Test
    @Tag("P0")
    void loweringLimitsDoesNotStrandPromisedOrAlreadyQueuedWork() {
        RuntimeWorkLimiter limiter = new RuntimeWorkLimiter(config(300, 200, 400));
        RuntimeWorkLimiter.QueueReservation promised = limiter.tryReserveQueue(180);
        assertNotNull(promised);
        AtomicInteger ran = new AtomicInteger();
        assertTrue(limiter.enqueueReserved(180, ran::incrementAndGet, promised));

        limiter.updateLimits(config(300, 100, 120));
        assertNull(limiter.tryReserveQueue(1), "lowered cap must block new queue promises");
        limiter.beginTick();

        assertEquals(1, ran.get());
        assertEquals(0, limiter.snapshot().queuedEntityOps());
        promised.close();
    }

    private static TimberConfig config(int blocks, int entities, int queued) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("runtime-work.launch-block-ops-per-tick", blocks);
        yaml.set("runtime-work.entity-operations-per-tick", entities);
        yaml.set("runtime-work.max-queued-entity-operations", queued);
        return new TimberConfig(yaml);
    }
}
