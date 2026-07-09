package com.asketmc.timber;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FellLifecycleTest {
    @Test
    @Tag("P0")
    void happyPathHasExplicitOrderedTransitions() {
        FellLifecycle lifecycle = new FellLifecycle();

        assertEquals(FellLifecycle.State.PREPARED, lifecycle.state());
        assertTrue(lifecycle.beginFall());
        assertTrue(lifecycle.beginLanding());
        assertTrue(lifecycle.markLanded());
        assertTrue(lifecycle.beginCompletion());
        assertTrue(lifecycle.complete());
        assertTrue(lifecycle.terminal());
        assertEquals(FellLifecycle.State.COMPLETED, lifecycle.state());
    }

    @Test
    @Tag("P0")
    void invalidAndRepeatedTerminalTransitionsAreRejected() {
        FellLifecycle lifecycle = new FellLifecycle();

        assertFalse(lifecycle.beginLanding());
        assertTrue(lifecycle.beginFall());
        assertTrue(lifecycle.fail());
        assertFalse(lifecycle.fail());
        assertFalse(lifecycle.beginLanding());
        assertEquals(FellLifecycle.State.FAILED, lifecycle.state());
    }

    @Test
    void expirationIsTerminalAndOnlyAllowedFromLanded() {
        FellLifecycle lifecycle = new FellLifecycle();
        assertFalse(lifecycle.expire());
        assertTrue(lifecycle.beginFall());
        assertTrue(lifecycle.beginLanding());
        assertTrue(lifecycle.markLanded());
        assertTrue(lifecycle.expire());
        assertTrue(lifecycle.terminal());
    }

    @Test
    @Tag("P0")
    void rejectedDeliveryCanReturnCompletionToLandedForRetry() {
        FellLifecycle lifecycle = new FellLifecycle();
        assertTrue(lifecycle.beginFall());
        assertTrue(lifecycle.beginLanding());
        assertTrue(lifecycle.markLanded());
        assertTrue(lifecycle.beginCompletion());
        assertTrue(lifecycle.retryCompletion());
        assertEquals(FellLifecycle.State.LANDED, lifecycle.state());
        assertFalse(lifecycle.terminal());
    }
}
