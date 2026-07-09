package com.asketmc.timber;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YieldLedgerTest {
    @Test
    @Tag("P0")
    void tracksOnlyUndeliveredYieldAcrossMultipleStacks() {
        YieldLedger ledger = new YieldLedger(130);

        assertEquals(64, ledger.nextStack());
        ledger.delivered(64);
        assertEquals(64, ledger.nextStack());
        ledger.delivered(64);
        assertEquals(2, ledger.nextStack());
        ledger.delivered(2);
        assertTrue(ledger.empty());
        assertEquals(0, ledger.nextStack());
    }

    @Test
    void rejectsInvalidAccounting() {
        YieldLedger ledger = new YieldLedger(10);
        assertThrows(IllegalArgumentException.class, () -> ledger.delivered(0));
        assertThrows(IllegalArgumentException.class, () -> ledger.delivered(11));
    }
}
