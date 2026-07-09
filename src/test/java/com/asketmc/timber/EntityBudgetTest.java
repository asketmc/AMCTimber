package com.asketmc.timber;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityBudgetTest {
    @Test
    @Tag("P0")
    void enforcesGlobalSessionAndEntityCapsAndReleasesExactlyOnce() {
        EntityBudget budget = new EntityBudget(2, 100);
        EntityBudget.Reservation first = budget.tryReserve(60);
        EntityBudget.Reservation second = budget.tryReserve(40);

        assertNotNull(first);
        assertNotNull(second);
        assertNull(budget.tryReserve(1));
        assertEquals(new EntityBudget.Snapshot(2, 100, 2, 100), budget.snapshot());

        first.close();
        first.close();
        assertEquals(1, budget.snapshot().sessions());
        assertEquals(40, budget.snapshot().entities());
        assertNotNull(budget.tryReserve(60));
    }

    @Test
    void resizeCanReleaseCapacityButCannotExceedTheGlobalLimit() {
        EntityBudget budget = new EntityBudget(2, 100);
        EntityBudget.Reservation first = budget.tryReserve(70);
        EntityBudget.Reservation second = budget.tryReserve(30);

        assertTrue(first.resize(50));
        assertEquals(80, budget.snapshot().entities());
        assertFalse(second.resize(60));
        assertEquals(80, budget.snapshot().entities());
    }

    @Test
    void lowerReloadedLimitsDoNotEvictExistingSessionsButBlockNewOnes() {
        EntityBudget budget = new EntityBudget(4, 200);
        EntityBudget.Reservation reservation = budget.tryReserve(100);
        budget.updateLimits(1, 50);

        assertNotNull(reservation);
        assertNull(budget.tryReserve(1));
        reservation.close();
        assertNotNull(budget.tryReserve(50));
    }
}
