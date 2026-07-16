package com.asketmc.timber;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryBudgetTest {
    @Test
    @Tag("P0")
    void durableRecordsNeverConsumeLiveEntitySessions() {
        RecoveryBudget recovery = new RecoveryBudget(3);
        recovery.restorePending(1);
        RecoveryBudget.Reservation reservation = recovery.tryReserve(2);
        assertNotNull(reservation);
        assertTrue(reservation.transfer(1));
        assertEquals(new RecoveryBudget.Snapshot(1, 2, 3), recovery.snapshot());
        assertNull(recovery.tryReserve(1));

        recovery.delivered();
        reservation.close();
        assertEquals(new RecoveryBudget.Snapshot(0, 1, 3), recovery.snapshot());

        EntityBudget live = new EntityBudget(1, 10);
        assertNotNull(live.tryReserve(1), "pending recovery must not own the live session budget");
    }

    @Test
    void transferCannotExceedOwnedReservation() {
        RecoveryBudget recovery = new RecoveryBudget(2);
        RecoveryBudget.Reservation reservation = recovery.tryReserve(1);
        assertNotNull(reservation);
        assertFalse(reservation.transfer(2));
        reservation.close();
        assertEquals(0, recovery.snapshot().reserved());
    }

    @Test
    void loweringConfiguredCapacityPreservesRestoredRecordsAndBlocksOnlyNewWork() {
        RecoveryBudget recovery = new RecoveryBudget(100);
        recovery.restorePending(80);
        recovery.updateLimit(64);

        assertEquals(new RecoveryBudget.Snapshot(0, 80, 64), recovery.snapshot());
        assertNull(recovery.tryReserve(1));
        recovery.delivered();
        assertEquals(79, recovery.snapshot().pending());
    }
}
