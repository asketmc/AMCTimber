package com.asketmc.timber;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FellAttemptAdmissionTest {
    @Test
    @Tag("P0")
    void coordinatedPlayersShareGlobalCapAndCooldownSurvivesTickReset() {
        FellAttemptAdmission admission = new FellAttemptAdmission(2, 2);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();

        assertTrue(admission.tryAcquire(first));
        assertTrue(admission.tryAcquire(second));
        assertFalse(admission.tryAcquire(third));
        admission.beginTick();
        assertFalse(admission.tryAcquire(first));
        assertTrue(admission.tryAcquire(third));
        admission.beginTick();
        assertTrue(admission.tryAcquire(first));
    }
}
