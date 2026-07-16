package com.asketmc.timber;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Main-thread global/per-player admission before any tree graph traversal. */
final class FellAttemptAdmission {
    private static final long PRUNE_INTERVAL_TICKS = 1_200L;

    private final Map<UUID, Long> nextPlayerTick = new HashMap<>();
    private int maxPerTick;
    private int cooldownTicks;
    private long tick;
    private int admittedThisTick;

    FellAttemptAdmission(int maxPerTick, int cooldownTicks) {
        updateLimits(maxPerTick, cooldownTicks);
    }

    synchronized void updateLimits(int newMaxPerTick, int newCooldownTicks) {
        maxPerTick = Math.max(1, newMaxPerTick);
        cooldownTicks = Math.max(0, newCooldownTicks);
    }

    synchronized void beginTick() {
        tick++;
        admittedThisTick = 0;
        if (tick % PRUNE_INTERVAL_TICKS == 0) {
            Iterator<Long> iterator = nextPlayerTick.values().iterator();
            while (iterator.hasNext()) if (iterator.next() <= tick) iterator.remove();
        }
    }

    synchronized boolean tryAcquire(UUID playerId) {
        if (playerId == null || admittedThisTick >= maxPerTick) return false;
        long next = nextPlayerTick.getOrDefault(playerId, Long.MIN_VALUE);
        if (tick < next) return false;
        admittedThisTick++;
        nextPlayerTick.put(playerId, tick + cooldownTicks);
        return true;
    }

    synchronized int trackedPlayers() { return nextPlayerTick.size(); }
}
