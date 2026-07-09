package com.asketmc.timber;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/** Bounded in-memory retry state for failure-recovery yield that could not be delivered immediately. */
final class PendingYield {
    private static final long RETRY_MILLIS = 5_000L;
    record Attempt(boolean complete, boolean changed) {}

    private final UUID id;
    private final World world;
    private final Location location;
    private final Material material;
    private final YieldLedger yield;
    private final EntityBudget.Reservation reservation;
    private long nextAttemptMillis;

    PendingYield(World world, Location location, Material material,
                 YieldLedger yield, EntityBudget.Reservation reservation) {
        this(UUID.randomUUID(), world, location, material, yield, reservation);
    }

    PendingYield(UUID id, World world, Location location, Material material,
                 YieldLedger yield, EntityBudget.Reservation reservation) {
        this.id = id;
        this.world = world;
        this.location = location.clone();
        this.material = material;
        this.yield = yield;
        this.reservation = reservation;
    }

    Attempt attempt(long now, boolean force) {
        if (!force && now < nextAttemptMillis) return new Attempt(false, false);
        nextAttemptMillis = now + RETRY_MILLIS;
        if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return new Attempt(false, false);
        }
        int before = yield.remaining();
        while (!yield.empty()) {
            int amount = yield.nextStack();
            int delivered = ItemDelivery.tryDeliver(
                    world, location, new ItemStack(material, amount));
            if (delivered > 0) yield.delivered(delivered);
            if (delivered < amount) return new Attempt(false, yield.remaining() != before);
        }
        return new Attempt(true, yield.remaining() != before);
    }

    int remaining() {
        return yield.remaining();
    }

    PendingYieldFile.Entry snapshot() {
        return new PendingYieldFile.Entry(id, world.getUID(), location.getX(), location.getY(), location.getZ(),
                material, yield.remaining());
    }

    void close() {
        reservation.close();
    }
}
