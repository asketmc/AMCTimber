package com.asketmc.timber;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/** One durable, round-robin item-yield record; each attempt performs at most one world spawn. */
final class PendingYield {
    private static final long RETRY_MILLIS = 5_000L;
    record Attempt(boolean complete, boolean changed) {}

    private final UUID id;
    private final UUID ownerId;
    private final World world;
    private final Location location;
    private final Material material;
    private final YieldLedger yield;
    private long nextAttemptMillis;

    PendingYield(World world, Location location, Material material, YieldLedger yield) {
        this(UUID.randomUUID(), null, world, location, material, yield);
    }

    PendingYield(UUID id, World world, Location location, Material material, YieldLedger yield) {
        this(id, null, world, location, material, yield);
    }

    PendingYield(UUID id, UUID ownerId, World world, Location location,
                 Material material, YieldLedger yield) {
        this.id = id;
        this.ownerId = ownerId;
        this.world = world;
        this.location = location.clone();
        this.material = material;
        this.yield = yield;
    }

    Attempt attemptOne(long now, boolean force) {
        return attemptOne(now, force, null);
    }

    Attempt attemptOne(long now, boolean force, Protection protection) {
        if (!force && now < nextAttemptMillis) return new Attempt(false, false);
        if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            nextAttemptMillis = now + RETRY_MILLIS;
            return new Attempt(false, false);
        }
        if (protection != null && protection.active()) {
            // V1 journals did not record an actor. Keep those migrated records dormant whenever
            // a protection integration is active instead of treating an unknown actor as trusted.
            if (ownerId == null) {
                nextAttemptMillis = now + RETRY_MILLIS;
                return new Attempt(false, false);
            }
            Player actor = Bukkit.getPlayer(ownerId);
            if (actor == null || !actor.isOnline()
                    || !protection.canPerform(actor, ProtectionHook.Action.ITEM_DROP, location, material)) {
                nextAttemptMillis = now + RETRY_MILLIS;
                return new Attempt(false, false);
            }
        }
        int before = yield.remaining();
        int amount = yield.nextStack();
        if (amount <= 0) return new Attempt(true, false);
        int delivered = ItemDelivery.tryDeliver(world, location, new ItemStack(material, amount));
        if (delivered > 0) yield.delivered(delivered);
        if (delivered < amount) nextAttemptMillis = now + RETRY_MILLIS;
        return new Attempt(yield.empty(), yield.remaining() != before);
    }

    int remaining() {
        return yield.remaining();
    }

    PendingYieldFile.Entry snapshot() {
        return new PendingYieldFile.Entry(id, ownerId, world.getUID(),
                location.getX(), location.getY(), location.getZ(), material, yield.remaining());
    }
}
