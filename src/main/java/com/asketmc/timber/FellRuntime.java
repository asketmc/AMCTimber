package com.asketmc.timber;

import java.util.Objects;

/** Generation-scoped service/config references captured atomically when a fell starts. */
record FellRuntime(TimberConfig config, Sched scheduler, Debug debug, FelledTrunkStore store,
                   Fx effects, XpBridge xpBridge, Messages messages, Protection protection,
                   RuntimeWorkLimiter workLimiter, CrushDispatcher crushDispatcher,
                   RecoveryBudget recoveryBudget) {
    FellRuntime {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(debug, "debug");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(effects, "effects");
        Objects.requireNonNull(xpBridge, "xpBridge");
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(protection, "protection");
        Objects.requireNonNull(workLimiter, "workLimiter");
        Objects.requireNonNull(crushDispatcher, "crushDispatcher");
        Objects.requireNonNull(recoveryBudget, "recoveryBudget");
    }
}
