package com.asketmc.timber;

/** Explicit, thread-safe lifecycle shared by a falling session and its landed trunk. */
final class FellLifecycle {
    enum State { PREPARED, FALLING, LANDING, LANDED, COMPLETING, COMPLETED, EXPIRED, FAILED }

    private State state = State.PREPARED;

    synchronized State state() {
        return state;
    }

    synchronized boolean beginFall() {
        return transition(State.PREPARED, State.FALLING);
    }

    synchronized boolean beginLanding() {
        return transition(State.FALLING, State.LANDING);
    }

    synchronized boolean markLanded() {
        return transition(State.LANDING, State.LANDED);
    }

    synchronized boolean beginCompletion() {
        return transition(State.LANDED, State.COMPLETING);
    }

    synchronized boolean complete() {
        return transition(State.COMPLETING, State.COMPLETED);
    }

    synchronized boolean retryCompletion() {
        return transition(State.COMPLETING, State.LANDED);
    }

    synchronized boolean expire() {
        return transition(State.LANDED, State.EXPIRED);
    }

    synchronized boolean fail() {
        if (terminal(state)) return false;
        state = State.FAILED;
        return true;
    }

    synchronized boolean is(State expected) {
        return state == expected;
    }

    synchronized boolean terminal() {
        return terminal(state);
    }

    private boolean transition(State expected, State next) {
        if (state != expected) return false;
        state = next;
        return true;
    }

    private static boolean terminal(State state) {
        return state == State.COMPLETED || state == State.EXPIRED || state == State.FAILED;
    }
}
