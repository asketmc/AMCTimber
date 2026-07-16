package com.asketmc.timber;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.inventory.ItemStack;
import org.joml.Quaternionf;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Owns one bounded PREPARED -> FALLING -> LANDED lifecycle. */
final class FellSession {
    private static final double LEAN_DEGREES = 2.5;

    private record CrushCandidate(UUID id, Location sample) {
        private CrushCandidate { sample = sample.clone(); }
    }

    /** Stackless sentinel used to stop Bukkit-side candidate conversion after Paper returns a query result. */
    private static final class CrushQueryLimit extends RuntimeException {
        private static final CrushQueryLimit INSTANCE = new CrushQueryLimit();
        private CrushQueryLimit() { super(null, null, false, false); }
    }

    private final Sched sched;
    private final Debug debug;
    private final FellJobManager manager;
    private final FelledTrunkStore store;
    private final TimberConfig cfg;
    private final Fx fx;
    private final XpBridge xpBridge;
    private final Messages messages;
    private final Protection protection;
    private final RuntimeWorkLimiter workLimiter;
    private final CrushDispatcher crushDispatcher;
    private final TreeShape shape;
    private final ToppleAnimator.Rig rig;
    private final LandingPlan landing;
    private final Player ownerPlayer;
    private final UUID owner;
    private final BlockKey cutKey;
    private final float[] axis;
    private final Deque<ItemStack> leafLoot;
    private final YieldLedger yield;
    private final float sizeT;
    private final FellLifecycle lifecycle;
    private final EntityBudget.Reservation reservation;
    private final RecoveryBudget.Reservation recoveryReservation;
    private final RuntimeWorkLimiter.QueueReservation queueReservation;
    private final List<FelledTrunk.Hitbox> landingHitboxes = new ArrayList<>();
    private boolean managerReleased;
    private boolean settleElapsed;
    private boolean crushComplete;
    private boolean landScheduled;

    FellSession(Sched sched, Debug debug, FellJobManager manager, FelledTrunkStore store,
                TimberConfig cfg, Fx fx, XpBridge xpBridge, Messages messages, Protection protection,
                RuntimeWorkLimiter workLimiter, CrushDispatcher crushDispatcher,
                TreeShape shape, ToppleAnimator.Rig rig, LandingPlan landing,
                Player ownerPlayer, UUID owner, BlockKey cutKey, List<ItemStack> leafLoot,
                YieldLedger yield, FellLifecycle lifecycle, EntityBudget.Reservation reservation,
                RecoveryBudget.Reservation recoveryReservation,
                RuntimeWorkLimiter.QueueReservation queueReservation) {
        this.sched = sched;
        this.debug = debug;
        this.manager = manager;
        this.store = store;
        this.cfg = cfg;
        this.fx = fx;
        this.xpBridge = xpBridge;
        this.messages = messages;
        this.protection = protection;
        this.workLimiter = workLimiter;
        this.crushDispatcher = crushDispatcher;
        this.shape = shape;
        this.rig = rig;
        this.landing = landing;
        this.ownerPlayer = ownerPlayer;
        this.owner = owner;
        this.cutKey = cutKey;
        this.axis = ToppleAnimator.axis(shape.dirX, shape.dirZ);
        this.leafLoot = new ArrayDeque<>(leafLoot);
        this.yield = yield;
        this.sizeT = Fx.sizeT(shape.logCount());
        this.lifecycle = lifecycle;
        this.reservation = reservation;
        this.recoveryReservation = recoveryReservation;
        this.queueReservation = queueReservation;
    }

    void start() {
        try {
            if (!lifecycle.beginFall()) throw new IllegalStateException("fell session was already started");
            debug.full("fell start: " + shape.baseMaterial + " logs=" + shape.logCount()
                    + " leaves=" + rig.leaves.size() + " leafLoot=" + leafLoot.size()
                    + " yDrop=" + String.format("%.1f", landing.yDrop)
                    + " at " + shape.cutX + ',' + shape.cutY + ',' + shape.cutZ
                    + (owner != null ? " by " + owner : " (QA)"));
            schedulePhases();
        } catch (RuntimeException | LinkageError failure) {
            fail("schedule", failure);
        }
    }

    private void schedulePhases() {
        double lean = Math.toRadians(LEAN_DEGREES);
        double rest = Math.toRadians(cfg.fallRestDegrees);
        double overshoot = Math.toRadians(cfg.fallRestDegrees + cfg.overshootDegrees);
        int rigOps = FellWorkEstimate.phaseEntityOps(rig);

        long tick = 1L;
        if (cfg.creakTicks > 0) {
            int duration = cfg.creakTicks;
            schedule(tick, "creak", rigOps, () -> {
                fx.creak(pivotLoc(), sizeT);
                applyAngle(lean, duration, 0);
            });
            tick += cfg.creakTicks;
        }

        long fallStart = tick;
        schedule(fallStart, "fall", rigOps,
                () -> applyAngle(overshoot, cfg.fallDurationTicks, landing.yDrop));
        schedule(fallStart + Math.max(1, cfg.fallDurationTicks / 2), "mid-fall", 24, this::midFall);

        long settleAt = fallStart + cfg.fallDurationTicks;
        schedule(settleAt, "settle", rigOps, () -> {
            applyAngle(rest, Math.max(1, cfg.bounceTicks), landing.yDrop);
            fx.impact(shape.world, impactLine(), shape.baseMaterial.createBlockData(), sizeT);
            crushComplete = !submitCrush();
            sched.later(() -> {
                settleElapsed = true;
                maybeScheduleLand();
            }, Math.max(1, cfg.bounceTicks));
        });
    }

    private void schedule(long ticks, String phase, int units, Runnable task) {
        sched.later(() -> {
            if (lifecycle.terminal()) return;
            if (!workLimiter.enqueueReserved(units, () -> runPhase(phase, task), queueReservation)) {
                fail(phase + " admission", new IllegalStateException("entity work queue is full"));
            }
        }, ticks);
    }

    private void maybeScheduleLand() {
        if (landScheduled || !settleElapsed || !crushComplete || lifecycle.terminal()) return;
        landScheduled = true;
        schedule(1L, "land", FellWorkEstimate.landingEntityOps(rig, landing), this::land);
    }

    private void runPhase(String phase, Runnable task) {
        if (lifecycle.terminal()) return;
        try {
            task.run();
        } catch (RuntimeException | LinkageError failure) {
            fail(phase, failure);
        }
    }

    private void applyAngle(double angle, int durationTicks, double drop) {
        Quaternionf rotation = ToppleAnimator.quat(axis, angle);
        for (ToppleAnimator.Seg segment : rig.logs) interpolate(segment, rotation, durationTicks, drop);
        for (ToppleAnimator.Seg segment : rig.leaves) interpolate(segment, rotation, durationTicks, drop);
    }

    private void interpolate(ToppleAnimator.Seg segment, Quaternionf rotation,
                             int durationTicks, double drop) {
        BlockDisplay display = segment.display;
        if (display == null || !display.isValid()) return;
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(durationTicks);
        Location anchor = display.getLocation();
        display.setTransformation(ToppleAnimator.transformFromAnchor(segment.node,
                rig.pivotX, rig.pivotY, rig.pivotZ, rotation, drop,
                anchor.getX(), anchor.getY(), anchor.getZ()));
    }

    private Location pivotLoc() {
        return new Location(shape.world, rig.pivotX, rig.pivotY, rig.pivotZ);
    }

    private void midFall() {
        if (rig.leaves.isEmpty()) return;
        Quaternionf rotation = ToppleAnimator.quat(axis, Math.toRadians(cfg.fallRestDegrees * 0.45));
        List<TreeShape.Node> sample = ToppleAnimator.decimate(nodesOf(rig.leaves), 24);
        List<double[]> positions = new ArrayList<>(sample.size());
        for (TreeShape.Node node : sample) {
            positions.add(ToppleAnimator.landedPos(node, rig.pivotX, rig.pivotY, rig.pivotZ,
                    rotation, landing.yDrop * 0.5));
        }
        fx.fallingLeaves(shape.world, positions, rig.leaves.getFirst().node.data.getMaterial());
        if (!positions.isEmpty()) {
            double[] center = positions.get(positions.size() / 2);
            fx.fallRustle(new Location(shape.world, center[0], center[1], center[2]), sizeT);
        }
    }

    private List<Location> impactLine() {
        List<Location> line = new ArrayList<>();
        double y = rig.pivotY - landing.yDrop;
        for (double distance = 1.0; distance <= Math.max(2.0, shape.height); distance += 2.5) {
            line.add(new Location(shape.world, rig.pivotX + shape.dirX * distance,
                    y, rig.pivotZ + shape.dirZ * distance));
        }
        return line;
    }

    private static List<TreeShape.Node> nodesOf(List<ToppleAnimator.Seg> segments) {
        List<TreeShape.Node> nodes = new ArrayList<>(segments.size());
        for (ToppleAnimator.Seg segment : segments) nodes.add(segment.node);
        return nodes;
    }

    private boolean submitCrush() {
        double damage = cfg.crushDamageFor(shape.logCount());
        if (damage <= 0 || ownerPlayer == null || !ownerPlayer.isOnline()) return false;
        if (!crushDispatcher.submit(new SessionCrushJob(damage))) {
            debug.full("crush skipped: global crush queue is full");
            return false;
        }
        return true;
    }

    private void finishCrush() {
        if (crushComplete) return;
        crushComplete = true;
        maybeScheduleLand();
    }

    private final class SessionCrushJob implements CrushDispatcher.Job {
        private final double damage;
        private final double groundY = rig.pivotY - landing.yDrop;
        private final double radius = cfg.crushRadius;
        private final double span = Math.max(2.0, shape.height);
        private final double step = Math.max(1.0, radius * 1.5);
        private final Set<UUID> seen = new HashSet<>();
        private final Deque<CrushCandidate> candidates = new ArrayDeque<>();
        private int visited;
        private double distance = 0.5;

        private SessionCrushJob(double damage) { this.damage = damage; }

        @Override
        public CrushDispatcher.Step step(RuntimeWorkLimiter limiter) {
            if (lifecycle.terminal()) {
                return CrushDispatcher.Step.DONE;
            }
            if (ownerPlayer == null || !ownerPlayer.isOnline()) {
                finishCrush();
                return CrushDispatcher.Step.DONE;
            }
            CrushCandidate candidate = candidates.peekFirst();
            if (candidate != null) {
                if (!limiter.takeCrushTarget()) return CrushDispatcher.Step.BLOCKED;
                candidates.removeFirst();
                Entity entity = Bukkit.getEntity(candidate.id());
                if (entity instanceof LivingEntity living && eligibleCrushTarget(living)) {
                    Location current = living.getLocation();
                    if (withinCrushSample(current, candidate.sample(), radius)
                            && crushAllowedAt(current) && protection.canDamage(ownerPlayer, living)) {
                        living.damage(damage, ownerPlayer);
                    }
                }
                return CrushDispatcher.Step.PROGRESS;
            }
            if (distance > span || visited >= cfg.maxCrushCandidates) {
                finishCrush();
                return CrushDispatcher.Step.DONE;
            }
            if (!limiter.takeCrushQuery()) return CrushDispatcher.Step.BLOCKED;

            Location at = new Location(shape.world, rig.pivotX + shape.dirX * distance,
                    groundY + 1.0, rig.pivotZ + shape.dirZ * distance);
            distance += step;
            if (!crushAllowedAt(at)) return CrushDispatcher.Step.PROGRESS;
            try {
                // Returning false avoids retaining a Bukkit result collection. The stackless sentinel caps
                // plugin-side conversion; Paper's underlying spatial query remains one non-preemptible unit.
                shape.world.getNearbyEntities(at, radius, radius + 0.6, radius, entity -> {
                    if (visited >= cfg.maxCrushCandidates || !limiter.crushTimeAvailable()) {
                        throw CrushQueryLimit.INSTANCE;
                    }
                    visited++;
                    if (entity instanceof LivingEntity living && eligibleCrushTarget(living)
                            && seen.add(living.getUniqueId())) {
                        candidates.addLast(new CrushCandidate(living.getUniqueId(), at));
                    }
                    return false;
                });
            } catch (CrushQueryLimit exhausted) {
                distance = span + 1.0;
            }
            if (visited >= cfg.maxCrushCandidates || !limiter.crushTimeAvailable()) {
                distance = span + 1.0;
            }
            return CrushDispatcher.Step.PROGRESS;
        }

        @Override
        public void failed(Throwable failure) {
            debug.warn("crush job failed: " + failure.getClass().getSimpleName());
            finishCrush();
        }
    }

    private boolean eligibleCrushTarget(LivingEntity living) {
        if (living instanceof Player player) {
            if (!cfg.crushHitPlayers) return false;
            if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return false;
            boolean feller = owner != null && owner.equals(player.getUniqueId());
            return feller || cfg.crushPvp;
        }
        return !(living instanceof ArmorStand) && cfg.crushHitMobs && !protectedMob(living);
    }

    private boolean crushAllowedAt(Location location) {
        return ownerPlayer != null && protection.canBreak(ownerPlayer, location, shape.baseMaterial);
    }

    static boolean withinCrushSample(Location candidate, Location sample, double radius) {
        if (candidate == null || sample == null || candidate.getWorld() != sample.getWorld()) return false;
        double horizontal = Math.max(0.0, radius);
        return Math.abs(candidate.getX() - sample.getX()) <= horizontal
                && Math.abs(candidate.getY() - sample.getY()) <= horizontal + 0.6
                && Math.abs(candidate.getZ() - sample.getZ()) <= horizontal;
    }

    private static boolean protectedMob(LivingEntity entity) {
        if (entity instanceof AbstractVillager) return true;
        if (entity instanceof Tameable tameable && tameable.isTamed()) return true;
        return entity.isLeashed();
    }

    private void land() {
        if (!lifecycle.beginLanding()) return;
        try {
            if (ownerPlayer != null && !protection.canLand(ownerPlayer, shape, landing,
                    new ArrayList<>(leafLoot), yield.remaining(), FellAttemptBudget.from(cfg))) {
                throw new IllegalStateException("landing protection changed during fall");
            }
            Quaternionf rest = ToppleAnimator.quat(axis, Math.toRadians(cfg.fallRestDegrees));
            landCanopy();
            queueLeafLoot();

            List<BlockDisplay> logs = landLogs(rest);
            spawnHitboxes(landingHitboxes);
            int finalEntities = logs.size() + landingHitboxes.size();
            if (!reservation.resize(finalEntities)) {
                throw new IllegalStateException("landed trunk exceeds global entity budget");
            }

            FelledTrunk trunk = new FelledTrunk(shape.world, logs, landingHitboxes, owner, shape.baseMaterial,
                    yield, cfg.hitsRequiredFor(shape.logCount()), rig.pivotX,
                    rig.pivotY - landing.yDrop, rig.pivotZ, shape.dirX, shape.dirZ,
                    Math.max(2.0, shape.height), cfg, fx, xpBridge, messages, debug,
                    lifecycle, reservation, recoveryReservation, store, protection,
                    workLimiter, queueReservation, landing.logDropLocation);
            if (!lifecycle.markLanded()) throw new IllegalStateException("invalid landing transition");
            debug.full("toppled + trunk spawned: logs=" + shape.logCount()
                    + " yield=" + yield.remaining() + " hits=" + cfg.hitsRequiredFor(shape.logCount())
                    + " hitboxes=" + landingHitboxes.size());
            store.register(trunk);
            releaseManager();
        } catch (RuntimeException | LinkageError failure) {
            fail("land", failure);
        }
    }

    private void landCanopy() {
        org.bukkit.block.data.BlockData leafData = rig.leaves.isEmpty() ? null : rig.leaves.getFirst().node.data;
        if (leafData != null) fx.leafCrash(shape.world, landing.leafImpactPositions, leafData);
        for (ToppleAnimator.Seg segment : rig.leaves) {
            if (segment.display != null && segment.display.isValid()) segment.display.remove();
        }
        rig.leaves.clear();
    }

    private void queueLeafLoot() {
        if (leafLoot.isEmpty()) return;
        List<ItemStack> items = new ArrayList<>(leafLoot);
        if (!store.deferItems(owner, shape.world, landing.recoveryLocation, items, recoveryReservation)) {
            throw new IllegalStateException("leaf yield queue rejected delivery");
        }
        leafLoot.clear();
    }

    private List<BlockDisplay> landLogs(Quaternionf rest) {
        List<ToppleAnimator.Seg> ordered = new ArrayList<>(rig.logs);
        ordered.sort(Comparator.comparingInt((ToppleAnimator.Seg segment) -> segment.node.y).reversed());
        List<BlockDisplay> logs = new ArrayList<>();
        for (ToppleAnimator.Seg segment : ordered) {
            BlockDisplay display = segment.display;
            if (display == null || !display.isValid()) continue;
            ToppleAnimator.rebaseLanded(display, segment.node, rig.pivotX, rig.pivotY,
                    rig.pivotZ, rest, landing.yDrop);
            display.removeScoreboardTag(Tags.ANIM);
            display.addScoreboardTag(Tags.TRUNK);
            if (owner != null) display.addScoreboardTag(Tags.ownerTag(owner));
            logs.add(display);
        }
        return logs;
    }

    private void spawnHitboxes(List<FelledTrunk.Hitbox> hitboxes) {
        int boxes = landing.hitboxLocations.size();
        for (int index = 0; index < boxes; index++) {
            double distance = (index + 0.5) * shape.height / boxes;
            Location at = landing.hitboxLocations.get(index);
            Interaction interaction = shape.world.spawn(at, Interaction.class, entity -> {
                entity.setInteractionWidth(1.7f);
                entity.setInteractionHeight(1.4f);
                entity.setResponsive(true);
                entity.setPersistent(false);
                entity.addScoreboardTag(Tags.TRUNK);
                if (owner != null) entity.addScoreboardTag(Tags.ownerTag(owner));
            });
            hitboxes.add(new FelledTrunk.Hitbox(interaction, distance));
        }
    }

    void abortOnShutdown() { fail("plugin shutdown", null); }

    private void fail(String phase, Throwable failure) {
        if (!lifecycle.fail()) {
            releaseManager();
            return;
        }
        try {
            recoverStep("leaf yield", () -> {
                if (leafLoot.isEmpty()) return;
                List<ItemStack> items = new ArrayList<>(leafLoot);
                if (!store.retainItems(owner, shape.world, recoveryLocation(), items,
                        recoveryReservation)) {
                    throw new IllegalStateException("terminal leaf-yield retention rejected");
                }
                leafLoot.clear();
            });
            if (!yield.empty()) {
                if (!store.retainYield(owner, shape.world, recoveryLocation(), shape.baseMaterial,
                        yield, recoveryReservation)) {
                    debug.warn("fell terminal log-yield retention rejected; undelivered logs="
                            + yield.remaining());
                }
            }
            String detail = failure == null ? "" : ": " + failure.getClass().getSimpleName()
                    + (failure.getMessage() == null ? "" : " (" + failure.getMessage() + ')');
            debug.warn("fell recovered after " + phase + " failure at "
                    + shape.cutX + ',' + shape.cutY + ',' + shape.cutZ + detail
                    + (yield.empty() ? "" : "; undelivered logs=" + yield.remaining()));
        } finally {
            recoveryReservation.close();
            if (!enqueueFailureCleanup()) {
                debug.warn("fell cleanup queue rejected work; orphan sweep will remove tagged entities");
                reservation.close();
                queueReservation.close();
            }
            releaseManager();
        }
    }

    private boolean enqueueFailureCleanup() {
        int units = rig.logs.size() + rig.leaves.size() + landingHitboxes.size();
        if (units <= 0) {
            reservation.close();
            queueReservation.close();
            return true;
        }
        return workLimiter.enqueueReserved(units, () -> {
            try {
                ToppleAnimator.remove(rig);
                for (FelledTrunk.Hitbox hitbox : landingHitboxes) {
                    try {
                        if (hitbox.entity.isValid()) hitbox.entity.remove();
                    } catch (RuntimeException | LinkageError ignored) {
                        // Tagged-entity purge is the final cleanup backstop.
                    }
                }
                landingHitboxes.clear();
            } finally {
                reservation.close();
                queueReservation.close();
            }
        }, queueReservation);
    }

    private void recoverStep(String step, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError recoveryFailure) {
            debug.warn("fell recovery step failed (" + step + "): "
                    + recoveryFailure.getClass().getSimpleName());
        }
    }

    private Location recoveryLocation() { return landing.recoveryLocation.clone(); }

    private synchronized void releaseManager() {
        if (managerReleased) return;
        managerReleased = true;
        manager.finish(this, cutKey);
    }
}
