package com.asketmc.timber;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Landed trunk state with idempotent completion, expiry, and planned-shutdown recovery. */
final class FelledTrunk {
    static final class Hitbox {
        final Interaction entity;
        final double dist;

        Hitbox(Interaction entity, double dist) {
            this.entity = entity;
            this.dist = dist;
        }
    }

    private final World world;
    private final List<BlockDisplay> displays;
    private final List<Hitbox> hitboxes;
    private final Set<UUID> ids;
    final UUID owner;
    private final Material dropMaterial;
    private final int totalYield;
    private final YieldLedger yield;
    private final int required;
    private final int initialDisplays;
    private int progress;
    private final double baseX, baseY, baseZ;
    private final double dirX, dirZ;
    private final double height;
    private volatile long despawnAtMillis;
    private final TimberConfig cfg;
    private final Fx fx;
    private final XpBridge xpBridge;
    private final Messages messages;
    private final Debug debug;
    private final FellLifecycle lifecycle;
    private final EntityBudget.Reservation reservation;
    private final Map<UUID, Long> lastChop = new HashMap<>();
    private final Map<UUID, Long> lastHint = new HashMap<>();

    FelledTrunk(World world, List<BlockDisplay> displays, List<Hitbox> hitboxes, UUID owner,
                Material dropMaterial, YieldLedger yield, int required,
                double baseX, double baseY, double baseZ, double dirX, double dirZ, double height,
                TimberConfig cfg, Fx fx, XpBridge xpBridge, Messages messages, Debug debug,
                FellLifecycle lifecycle, EntityBudget.Reservation reservation) {
        this.world = world;
        this.displays = displays;
        this.hitboxes = hitboxes;
        this.ids = new HashSet<>();
        for (Hitbox hitbox : hitboxes) ids.add(hitbox.entity.getUniqueId());
        this.owner = owner;
        this.dropMaterial = dropMaterial;
        this.totalYield = yield.remaining();
        this.yield = yield;
        this.required = Math.max(1, required);
        this.initialDisplays = Math.max(1, displays.size());
        this.baseX = baseX;
        this.baseY = baseY;
        this.baseZ = baseZ;
        this.dirX = dirX;
        this.dirZ = dirZ;
        this.height = height;
        this.cfg = cfg;
        this.fx = fx;
        this.xpBridge = xpBridge;
        this.messages = messages;
        this.debug = debug;
        this.lifecycle = lifecycle;
        this.reservation = reservation;
        this.despawnAtMillis = System.currentTimeMillis() + cfg.despawnSeconds * 1000L;
    }

    TimberConfig config() {
        return cfg;
    }

    Set<UUID> allIds() {
        return ids;
    }

    long despawnAtMillis() {
        return despawnAtMillis;
    }

    synchronized boolean valid() {
        for (Hitbox hitbox : hitboxes) if (hitbox.entity.isValid()) return true;
        return false;
    }

    boolean blockedFor(Player player) {
        return cfg.ownerLock && owner != null && !owner.equals(player.getUniqueId());
    }

    Location center() {
        return new Location(world, baseX + dirX * height * 0.5,
                baseY + 0.5, baseZ + dirZ * height * 0.5);
    }

    synchronized Interaction anyHitbox() {
        for (Hitbox hitbox : hitboxes) if (hitbox.entity.isValid()) return hitbox.entity;
        return null;
    }

    synchronized boolean chopReady(UUID player) {
        long now = System.currentTimeMillis();
        long cooldownMillis = cfg.chopCooldownTicks * 50L;
        Long last = lastChop.get(player);
        if (last != null && now - last < cooldownMillis) return false;
        lastChop.put(player, now);
        return true;
    }

    synchronized boolean hintReady(UUID player) {
        long now = System.currentTimeMillis();
        Long last = lastHint.get(player);
        if (last != null && now - last < 1500L) return false;
        lastHint.put(player, now);
        return true;
    }

    /** Returns true when the trunk reached a terminal state and must be removed from the store. */
    synchronized boolean chop(Player player, int amount) {
        if (!lifecycle.is(FellLifecycle.State.LANDED)) return lifecycle.terminal();
        progress = Math.min(required, progress + Math.max(1, amount));
        despawnAtMillis = System.currentTimeMillis() + cfg.despawnSeconds * 1000L;

        double fraction = progress / (double) required;
        fx.chopHit(pointAt(Math.max(0.5, height * (1.0 - fraction) * 0.9)),
                dropMaterial.createBlockData(), fraction);
        if (progress < required) {
            shrinkTo(keepCount(initialDisplays, required, progress));
            messages.progress(player, progress, required);
            return false;
        }

        if (!lifecycle.beginCompletion()) return lifecycle.terminal();
        boolean cleanup = true;
        try {
            dropRemainingYield();
            int xp = cfg.xpFor(totalYield);
            xpBridge.grant(player, xp);
            fx.chopBreak(center(), dropMaterial.createBlockData());
            messages.yield(player, dropMaterial, totalYield, xp, xpBridge.present());
            lifecycle.complete();
        } catch (RuntimeException | LinkageError failure) {
            int remaining = yield.remaining();
            if (remaining > 0 && lifecycle.retryCompletion()) {
                cleanup = false;
                despawnAtMillis = Long.MAX_VALUE;
                debug.warn("trunk yield delivery deferred; remaining logs=" + remaining);
                return false;
            }
            lifecycle.fail();
            debug.warn("trunk completion failed; recovering remaining yield: " + failure.getClass().getSimpleName());
        } finally {
            if (cleanup) cleanupEntitiesAndBudget();
        }
        return true;
    }

    synchronized void expire() {
        if (!lifecycle.expire()) return;
        try {
            fx.leafRustle(center());
        } catch (RuntimeException | LinkageError failure) {
            debug.warn("trunk expiry effect failed: " + failure.getClass().getSimpleName());
        } finally {
            cleanupEntitiesAndBudget();
        }
    }

    synchronized void deferOnShutdown(FelledTrunkStore store) {
        if (!lifecycle.fail()) return;
        int remaining = yield.remaining();
        boolean deferred = false;
        try {
            deferred = remaining == 0 || store.deferYield(world, center(), dropMaterial, yield, reservation);
            if (remaining > 0) {
                debug.warn((deferred ? "persisting " : "could not persist ") + remaining
                        + " pending logs during plugin shutdown");
            }
        } finally {
            cleanupEntities(deferred && remaining > 0);
        }
    }

    synchronized boolean deferCompletedYield(FelledTrunkStore store, String reason) {
        int remaining = yield.remaining();
        if (progress < required || remaining == 0 || !lifecycle.is(FellLifecycle.State.LANDED)) return false;
        if (!lifecycle.fail()) return false;
        boolean deferred = false;
        try {
            deferred = store.deferYield(world, center(), dropMaterial, yield, reservation);
            debug.warn((deferred ? "moved completed trunk yield to recovery queue after "
                    : "could not retain completed trunk yield after ") + reason
                    + "; remaining logs=" + remaining);
            return true;
        } finally {
            cleanupEntities(deferred);
        }
    }

    static int keepCount(int displays, int required, int progress) {
        if (progress >= required) return 0;
        return (int) Math.ceil(displays * (double) (required - progress) / required);
    }

    static int hitboxCount(double height) {
        return Math.max(1, Math.min(64, (int) Math.ceil(height / 2.5)));
    }

    private Location pointAt(double distance) {
        return new Location(world, baseX + dirX * distance, baseY + 0.6, baseZ + dirZ * distance);
    }

    private void dropRemainingYield() {
        int stackIndex = 0;
        int amount;
        while ((amount = yield.nextStack()) > 0) {
            double distance = Math.min(height - 0.5, 1.0 + stackIndex * 2.0);
            int delivered = ItemDelivery.tryDeliver(world, pointAt(Math.max(0.5, distance)),
                    new ItemStack(dropMaterial, amount));
            if (delivered > 0) yield.delivered(delivered);
            if (delivered < amount) throw new IllegalStateException("item delivery was rejected");
            stackIndex++;
        }
    }

    private void shrinkTo(int keep) {
        while (displays.size() > keep) {
            BlockDisplay display = displays.removeFirst();
            removeBestEffort(display);
        }
        double keptLength = height * displays.size() / (double) initialDisplays;
        for (int index = hitboxes.size() - 1; index > 0; index--) {
            Hitbox hitbox = hitboxes.get(index);
            if (hitbox.dist <= keptLength + 1.0) continue;
            removeBestEffort(hitbox.entity);
            hitboxes.remove(index);
        }
        if (!reservation.resize(displays.size() + hitboxes.size())) {
            throw new IllegalStateException("entity budget accounting rejected a decreasing resize");
        }
    }

    private void cleanupEntitiesAndBudget() {
        cleanupEntities(false);
    }

    private void cleanupEntities(boolean keepReservation) {
        try {
            for (BlockDisplay display : displays) removeBestEffort(display);
            displays.clear();
            for (Hitbox hitbox : new ArrayList<>(hitboxes)) removeBestEffort(hitbox.entity);
            hitboxes.clear();
        } finally {
            if (!keepReservation) reservation.close();
        }
    }

    private static void removeBestEffort(org.bukkit.entity.Entity entity) {
        try {
            if (entity != null && entity.isValid()) entity.remove();
        } catch (RuntimeException | LinkageError ignored) {
            // The tagged-entity sweep remains the shutdown/startup cleanup backstop.
        }
    }

    void needAxe(Player player) { messages.needAxe(player); }

    void ownerLocked(Player player) { messages.ownerLocked(player); }

    int yieldLogs() {
        return totalYield;
    }
}
