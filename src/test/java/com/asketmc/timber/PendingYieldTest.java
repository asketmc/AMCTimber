package com.asketmc.timber;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingYieldTest {
    @Test
    @Tag("P0")
    void rejectedSpawnRetainsLedgerUntilRateLimitedRetrySucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        World world = worldThatAcceptsOnSecondAttempt(attempts);
        EntityBudget budget = new EntityBudget(1, 10);
        EntityBudget.Reservation reservation = budget.tryReserve(10);
        assertNotNull(reservation);
        assertTrue(reservation.resize(0));
        PendingYield pending = new PendingYield(world, new Location(world, 0, 64, 0),
                Material.OAK_LOG, new YieldLedger(10), reservation);

        assertFalse(pending.attempt(10_000L, false).complete());
        assertEquals(10, pending.remaining());
        assertFalse(pending.attempt(10_001L, false).complete());
        assertEquals(1, attempts.get(), "retry must be rate limited");
        assertTrue(pending.attempt(15_000L, false).complete());
        assertEquals(0, pending.remaining());

        pending.close();
        assertEquals(0, budget.snapshot().sessions());
    }

    @Test
    void retryDoesNotLoadAnUnloadedChunk() {
        AtomicInteger attempts = new AtomicInteger();
        World world = world(attempts, false);
        EntityBudget budget = new EntityBudget(1, 10);
        EntityBudget.Reservation reservation = budget.tryReserve(0);
        PendingYield pending = new PendingYield(world, new Location(world, 0, 64, 0),
                Material.OAK_LOG, new YieldLedger(1), reservation);

        assertFalse(pending.attempt(10_000L, true).complete());
        assertEquals(0, attempts.get());
        pending.close();
    }

    @Test
    @Tag("P0")
    void partialMultiStackDeliveryReportsJournalProgress() {
        AtomicInteger attempts = new AtomicInteger();
        World world = worldWithOutcomes(attempts, true, false);
        EntityBudget budget = new EntityBudget(1, 10);
        EntityBudget.Reservation reservation = budget.tryReserve(0);
        PendingYield pending = new PendingYield(world, new Location(world, 0, 64, 0),
                Material.OAK_LOG, new YieldLedger(130), reservation);

        PendingYield.Attempt result = pending.attempt(10_000L, true);
        assertFalse(result.complete());
        assertTrue(result.changed());
        assertEquals(66, pending.remaining());
        assertEquals(66, pending.snapshot().amount());
        pending.close();
    }

    private static World worldThatAcceptsOnSecondAttempt(AtomicInteger attempts) {
        return worldWithOutcomes(attempts, false, true);
    }

    private static World world(AtomicInteger attempts, boolean chunkLoaded) {
        return world(attempts, chunkLoaded, new boolean[]{true});
    }

    private static World worldWithOutcomes(AtomicInteger attempts, boolean... outcomes) {
        return world(attempts, true, outcomes);
    }

    private static World world(AtomicInteger attempts, boolean chunkLoaded, boolean[] outcomes) {
        UUID worldId = UUID.randomUUID();
        return (World) Proxy.newProxyInstance(PendingYieldTest.class.getClassLoader(),
                new Class<?>[]{World.class}, (proxy, method, args) -> {
                    if (method.getName().equals("dropItemNaturally")) {
                        int attempt = attempts.getAndIncrement();
                        boolean accepted = outcomes[Math.min(attempt, outcomes.length - 1)];
                        return item(accepted);
                    }
                    if (method.getName().equals("isChunkLoaded")) return chunkLoaded;
                    if (method.getName().equals("getUID")) return worldId;
                    if (method.getName().equals("toString")) return "pending-yield-test-world";
                    return defaultValue(method.getReturnType());
                });
    }

    private static Item item(boolean accepted) {
        return (Item) Proxy.newProxyInstance(PendingYieldTest.class.getClassLoader(),
                new Class<?>[]{Item.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isValid", "isInWorld" -> accepted;
                    case "toString" -> "pending-yield-test-item";
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        return 0D;
    }
}
