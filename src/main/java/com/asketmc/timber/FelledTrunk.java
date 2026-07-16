package com.asketmc.timber;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;

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
    private final RecoveryBudget.Reservation recoveryReservation;
    private final FelledTrunkStore store;
    private final Protection protection;
    private final RuntimeWorkLimiter workLimiter;
    private final RuntimeWorkLimiter.QueueReservation queueReservation;
    private final Location yieldLocation;
    private final Map<UUID, Long> lastChop = new HashMap<>();
    private final Map<UUID, Long> lastHint = new HashMap<>();
    private int desiredKeep;
    private boolean shrinkQueued;
    private boolean cleanupRequested;
    /** Actor who first supplied the completing chop; retained across journal retries and shutdown. */
    private UUID completionActorId;

    FelledTrunk(World world, List<BlockDisplay> displays, List<Hitbox> hitboxes, UUID owner,
                Material dropMaterial, YieldLedger yield, int required,
                double baseX, double baseY, double baseZ, double dirX, double dirZ, double height,
                TimberConfig cfg, Fx fx, XpBridge xpBridge, Messages messages, Debug debug,
                FellLifecycle lifecycle, EntityBudget.Reservation reservation,
                RecoveryBudget.Reservation recoveryReservation, FelledTrunkStore store,
                Protection protection, RuntimeWorkLimiter workLimiter,
                RuntimeWorkLimiter.QueueReservation queueReservation, Location yieldLocation) {
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
        this.recoveryReservation = recoveryReservation;
        this.store = store;
        this.protection = protection;
        this.workLimiter = workLimiter;
        this.queueReservation = queueReservation;
        this.yieldLocation = yieldLocation.clone();
        this.desiredKeep = this.initialDisplays;
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

    Material dropMaterial() { return dropMaterial; }

    Location yieldLocation() { return yieldLocation.clone(); }

    synchronized boolean willComplete(int amount) {
        return progress + Math.max(1, amount) >= required;
    }

    boolean authorized(Player player, Interaction interaction, int amount) {
        org.bukkit.util.BoundingBox box = interaction.getBoundingBox();
        int maxX = (int) Math.floor(Math.nextDown(box.getMaxX()));
        int maxY = (int) Math.floor(Math.nextDown(box.getMaxY()));
        int maxZ = (int) Math.floor(Math.nextDown(box.getMaxZ()));
        for (int x = (int) Math.floor(box.getMinX()); x <= maxX; x++) {
            for (int y = (int) Math.floor(box.getMinY()); y <= maxY; y++) {
                for (int z = (int) Math.floor(box.getMinZ()); z <= maxZ; z++) {
                    if (!protection.canPerform(player, ProtectionHook.Action.TRUNK_INTERACT,
                            new Location(world, x, y, z), dropMaterial)) return false;
                }
            }
        }
        return yield.empty() || !willComplete(amount)
                || protection.canPerform(player, ProtectionHook.Action.ITEM_DROP,
                yieldLocation, dropMaterial);
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
        bestEffort("chop effect", () -> fx.chopHit(
                pointAt(Math.max(0.5, height * (1.0 - fraction) * 0.9)),
                dropMaterial.createBlockData(), fraction));
        if (progress < required) {
            scheduleShrink(keepCount(initialDisplays, required, progress));
            bestEffort("progress message", () -> messages.progress(player, progress, required));
            return false;
        }
        if (completionActorId == null) completionActorId = player.getUniqueId();

        if (!lifecycle.beginCompletion()) return lifecycle.terminal();
        try {
            dropRemainingYield(completionActorId);
            lifecycle.complete();
            requestCleanup();
        } catch (RuntimeException | LinkageError failure) {
            lifecycle.retryCompletion();
            despawnAtMillis = Long.MAX_VALUE;
            debug.warn("trunk yield journal commit deferred; remaining logs=" + yield.remaining());
            return false;
        }

        int xp = cfg.xpFor(totalYield);
        bestEffort("completion XP", () -> xpBridge.grant(player, xp));
        bestEffort("completion effect", () -> fx.chopBreak(center(), dropMaterial.createBlockData()));
        bestEffort("completion message",
                () -> messages.yield(player, dropMaterial, totalYield, xp, xpBridge.present()));
        return true;
    }

    synchronized void expire() {
        if (!lifecycle.expire()) return;
        try {
            fx.leafRustle(center());
        } catch (RuntimeException | LinkageError failure) {
            debug.warn("trunk expiry effect failed: " + failure.getClass().getSimpleName());
        } finally {
            requestCleanup();
        }
    }

    synchronized void deferOnShutdown(FelledTrunkStore store) {
        if (lifecycle.terminal()) return;
        int remaining = yield.remaining();
        boolean retained = remaining == 0 || store.retainYield(recoveryActorId(), world, yieldLocation,
                dropMaterial, yield, recoveryReservation);
        if (!retained) {
            debug.warn("could not retain " + remaining + " pending logs during plugin shutdown");
            return;
        }
        if (!lifecycle.fail()) return;
        if (remaining > 0) debug.warn("retained " + remaining + " pending logs during plugin shutdown");
        cleanupEntitiesNow();
    }

    synchronized boolean deferCompletedYield(FelledTrunkStore store, String reason) {
        int remaining = yield.remaining();
        if (progress < required || remaining == 0 || !lifecycle.is(FellLifecycle.State.LANDED)) return false;
        boolean retained = store.retainYield(recoveryActorId(), world, yieldLocation, dropMaterial, yield,
                recoveryReservation);
        if (!retained) {
            debug.warn("could not retain completed trunk yield after " + reason
                    + "; remaining logs=" + remaining);
            return false;
        }
        if (!lifecycle.fail()) return false;
        debug.warn("moved completed trunk yield to recovery queue after " + reason
                + "; remaining logs=" + remaining);
        requestCleanup();
        return true;
    }

    synchronized boolean needsCompletedYieldRecovery() {
        return progress >= required && !yield.empty() && lifecycle.is(FellLifecycle.State.LANDED);
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

    private void dropRemainingYield(UUID actorId) {
        if (yield.empty()) return;
        if (!store.deferYield(actorId, world, yieldLocation, dropMaterial, yield,
                recoveryReservation)) {
            throw new IllegalStateException("durable item-delivery queue rejected yield");
        }
    }

    private UUID recoveryActorId() {
        return completionActorId == null ? owner : completionActorId;
    }

    private synchronized void scheduleShrink(int keep) {
        desiredKeep = Math.min(desiredKeep, Math.max(0, keep));
        if (shrinkQueued || cleanupRequested) return;
        int target = desiredKeep;
        int units = removalCount(target);
        if (units <= 0) return;
        shrinkQueued = true;
        if (!workLimiter.enqueueReserved(units, () -> runShrink(target), queueReservation)) {
            shrinkQueued = false;
            throw new IllegalStateException("paced trunk shrink admission was lost");
        }
    }

    private synchronized void runShrink(int keep) {
        shrinkTo(keep);
        shrinkQueued = false;
        if (cleanupRequested) {
            enqueueCleanup();
        } else if (desiredKeep < displays.size()) {
            scheduleShrink(desiredKeep);
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

    private int removalCount(int keep) {
        int normalized = Math.max(0, Math.min(keep, displays.size()));
        int count = displays.size() - normalized;
        double keptLength = height * normalized / (double) initialDisplays;
        for (int index = hitboxes.size() - 1; index > 0; index--) {
            if (hitboxes.get(index).dist > keptLength + 1.0) count++;
        }
        return count;
    }

    private synchronized void requestCleanup() {
        cleanupRequested = true;
        if (!shrinkQueued) enqueueCleanup();
    }

    private void enqueueCleanup() {
        int units = displays.size() + hitboxes.size();
        if (units <= 0) {
            closeBudgets();
            return;
        }
        if (!workLimiter.enqueueReserved(units, this::cleanupEntitiesNow, queueReservation)) {
            debug.warn("paced trunk cleanup admission was lost; tagged-entity purge remains the backstop");
            closeBudgets();
        }
    }

    private synchronized void cleanupEntitiesNow() {
        try {
            for (BlockDisplay display : displays) removeBestEffort(display);
            displays.clear();
            for (Hitbox hitbox : new ArrayList<>(hitboxes)) removeBestEffort(hitbox.entity);
            hitboxes.clear();
        } finally {
            closeBudgets();
        }
    }

    private void closeBudgets() {
        reservation.close();
        recoveryReservation.close();
        queueReservation.close();
    }

    private void bestEffort(String action, Runnable runnable) {
        try {
            runnable.run();
        } catch (RuntimeException | LinkageError failure) {
            debug.warn(action + " failed after durable completion: " + failure.getClass().getSimpleName());
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
