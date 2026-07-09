package com.asketmc.timber;

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

/** Owns one explicit PREPARED -> FALLING -> LANDED lifecycle. */
final class FellSession {
    private static final double LEAN_DEGREES = 2.5;

    private final Sched sched;
    private final Debug debug;
    private final FellJobManager manager;
    private final FelledTrunkStore store;
    private final TimberConfig cfg;
    private final Fx fx;
    private final XpBridge xpBridge;
    private final Messages messages;
    private final Protection protection;
    private final TreeShape shape;
    private final ToppleAnimator.Rig rig;
    private final Player ownerPlayer;
    private final UUID owner;
    private final BlockKey cutKey;
    private final float[] axis;
    private final double yDrop;
    private final Deque<ItemStack> leafLoot;
    private final YieldLedger yield;
    private final float sizeT;
    private final FellLifecycle lifecycle;
    private final EntityBudget.Reservation reservation;
    private boolean managerReleased;

    FellSession(Sched sched, Debug debug, FellJobManager manager, FelledTrunkStore store,
                TimberConfig cfg, Fx fx, XpBridge xpBridge, Messages messages, Protection protection,
                TreeShape shape, ToppleAnimator.Rig rig, Player ownerPlayer, UUID owner,
                BlockKey cutKey, double yDrop, List<ItemStack> leafLoot,
                YieldLedger yield, FellLifecycle lifecycle, EntityBudget.Reservation reservation) {
        this.sched = sched;
        this.debug = debug;
        this.manager = manager;
        this.store = store;
        this.cfg = cfg;
        this.fx = fx;
        this.xpBridge = xpBridge;
        this.messages = messages;
        this.protection = protection;
        this.shape = shape;
        this.rig = rig;
        this.ownerPlayer = ownerPlayer;
        this.owner = owner;
        this.cutKey = cutKey;
        this.axis = ToppleAnimator.axis(shape.dirX, shape.dirZ);
        this.yDrop = yDrop;
        this.leafLoot = new ArrayDeque<>(leafLoot);
        this.yield = yield;
        this.sizeT = Fx.sizeT(shape.logCount());
        this.lifecycle = lifecycle;
        this.reservation = reservation;
    }

    void start() {
        try {
            if (!lifecycle.beginFall()) throw new IllegalStateException("fell session was already started");
            debug.full("fell start: " + shape.baseMaterial + " logs=" + shape.logCount()
                    + " leaves=" + rig.leaves.size() + " leafLoot=" + leafLoot.size()
                    + " yDrop=" + String.format("%.1f", yDrop)
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

        long tick = 1L;
        if (cfg.creakTicks > 0) {
            int duration = cfg.creakTicks;
            schedule(tick, "creak", () -> {
                fx.creak(pivotLoc(), sizeT);
                applyAngle(lean, duration, 0);
            });
            tick += cfg.creakTicks;
        }

        long fallStart = tick;
        schedule(fallStart, "fall", () -> applyAngle(overshoot, cfg.fallDurationTicks, yDrop));
        schedule(fallStart + Math.max(1, cfg.fallDurationTicks / 2), "mid-fall", this::midFall);

        long settleAt = fallStart + cfg.fallDurationTicks;
        schedule(settleAt, "settle", () -> {
            applyAngle(rest, Math.max(1, cfg.bounceTicks), yDrop);
            fx.impact(shape.world, impactLine(), shape.baseMaterial.createBlockData(), sizeT);
            crush();
        });
        schedule(settleAt + Math.max(1, cfg.bounceTicks), "land", this::land);
    }

    private void schedule(long ticks, String phase, Runnable task) {
        sched.later(() -> runPhase(phase, task), ticks);
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
                    rotation, yDrop * 0.5));
        }
        fx.fallingLeaves(shape.world, positions, rig.leaves.getFirst().node.data.getMaterial());
        if (!positions.isEmpty()) {
            double[] center = positions.get(positions.size() / 2);
            fx.fallRustle(new Location(shape.world, center[0], center[1], center[2]), sizeT);
        }
    }

    private List<Location> impactLine() {
        List<Location> line = new ArrayList<>();
        double y = rig.pivotY - yDrop;
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

    private void crush() {
        double damage = cfg.crushDamageFor(shape.logCount());
        if (damage <= 0) return;
        double groundY = rig.pivotY - yDrop;
        double radius = cfg.crushRadius;
        double span = Math.max(2.0, shape.height);
        double step = Math.max(1.0, radius * 1.5);
        Set<UUID> hit = new HashSet<>();
        for (double distance = 0.5; distance <= span; distance += step) {
            Location at = new Location(shape.world, rig.pivotX + shape.dirX * distance,
                    groundY + 1.0, rig.pivotZ + shape.dirZ * distance);
            if (!crushAllowedAt(at)) continue;
            for (Entity entity : shape.world.getNearbyEntities(at, radius, radius + 0.6, radius)) {
                if (!(entity instanceof LivingEntity living) || !hit.add(entity.getUniqueId())) continue;
                if (!crushAllowedAt(living.getLocation())) continue;
                if (living instanceof Player player) {
                    if (!cfg.crushHitPlayers) continue;
                    if (player.getGameMode() == GameMode.CREATIVE
                            || player.getGameMode() == GameMode.SPECTATOR) continue;
                    boolean feller = owner != null && owner.equals(player.getUniqueId());
                    if (!feller && !cfg.crushPvp) continue;
                } else if (living instanceof ArmorStand || protectedMob(living) || !cfg.crushHitMobs) {
                    continue;
                }
                living.damage(damage);
            }
        }
    }

    private boolean crushAllowedAt(Location location) {
        return ownerPlayer == null || protection.canBreak(ownerPlayer, location, shape.baseMaterial);
    }

    private static boolean protectedMob(LivingEntity entity) {
        if (entity instanceof AbstractVillager) return true;
        if (entity instanceof Tameable tameable && tameable.isTamed()) return true;
        return entity.isLeashed();
    }

    private void land() {
        if (!lifecycle.beginLanding()) return;
        List<FelledTrunk.Hitbox> hitboxes = new ArrayList<>();
        try {
            Quaternionf rest = ToppleAnimator.quat(axis, Math.toRadians(cfg.fallRestDegrees));
            List<double[]> landedLeaves = landCanopy(rest);
            dropLeafLoot(landedLeaves);

            List<BlockDisplay> logs = landLogs(rest);
            double groundY = rig.pivotY - yDrop;
            spawnHitboxes(hitboxes, groundY);
            int finalEntities = logs.size() + hitboxes.size();
            if (!reservation.resize(finalEntities)) {
                throw new IllegalStateException("landed trunk exceeds global entity budget");
            }

            FelledTrunk trunk = new FelledTrunk(shape.world, logs, hitboxes, owner, shape.baseMaterial,
                    yield, cfg.hitsRequiredFor(shape.logCount()), rig.pivotX, groundY, rig.pivotZ,
                    shape.dirX, shape.dirZ, Math.max(2.0, shape.height), cfg, fx, xpBridge,
                    messages, debug, lifecycle, reservation);
            if (!lifecycle.markLanded()) throw new IllegalStateException("invalid landing transition");
            debug.full("toppled + trunk spawned: logs=" + shape.logCount()
                    + " yield=" + yield.remaining() + " hits=" + cfg.hitsRequiredFor(shape.logCount())
                    + " hitboxes=" + hitboxes.size());
            store.register(trunk);
            releaseManager();
        } catch (RuntimeException | LinkageError failure) {
            for (FelledTrunk.Hitbox hitbox : hitboxes) {
                if (hitbox.entity.isValid()) hitbox.entity.remove();
            }
            fail("land", failure);
        }
    }

    private List<double[]> landCanopy(Quaternionf rest) {
        org.bukkit.block.data.BlockData leafData = rig.leaves.isEmpty() ? null : rig.leaves.getFirst().node.data;
        List<TreeShape.Node> sample = ToppleAnimator.decimate(nodesOf(rig.leaves), 40);
        List<double[]> landed = new ArrayList<>(sample.size());
        for (TreeShape.Node node : sample) {
            landed.add(ToppleAnimator.landedPos(node, rig.pivotX, rig.pivotY, rig.pivotZ, rest, yDrop));
        }
        if (leafData != null) fx.leafCrash(shape.world, landed, leafData);
        for (ToppleAnimator.Seg segment : rig.leaves) {
            if (segment.display != null && segment.display.isValid()) segment.display.remove();
        }
        rig.leaves.clear();
        return landed;
    }

    private void dropLeafLoot(List<double[]> landed) {
        List<double[]> spots = landed.isEmpty()
                ? List.of(new double[]{rig.pivotX, rig.pivotY - yDrop + 0.5, rig.pivotZ}) : landed;
        int index = 0;
        while (!leafLoot.isEmpty()) {
            ItemStack item = leafLoot.peekFirst();
            double[] point = spots.get(index++ % spots.size());
            int delivered = ItemDelivery.tryDeliver(shape.world,
                    new Location(shape.world, point[0], point[1] + 0.3, point[2]), item);
            if (delivered <= 0) throw new IllegalStateException("leaf item delivery was rejected");
            if (delivered < item.getAmount()) {
                item.setAmount(item.getAmount() - delivered);
                throw new IllegalStateException("leaf item delivery was partial");
            }
            leafLoot.removeFirst();
        }
    }

    private List<BlockDisplay> landLogs(Quaternionf rest) {
        List<ToppleAnimator.Seg> ordered = new ArrayList<>(rig.logs);
        ordered.sort(Comparator.comparingInt((ToppleAnimator.Seg segment) -> segment.node.y).reversed());
        List<BlockDisplay> logs = new ArrayList<>();
        for (ToppleAnimator.Seg segment : ordered) {
            BlockDisplay display = segment.display;
            if (display == null || !display.isValid()) continue;
            ToppleAnimator.rebaseLanded(display, segment.node, rig.pivotX, rig.pivotY, rig.pivotZ, rest, yDrop);
            display.removeScoreboardTag(Tags.ANIM);
            display.addScoreboardTag(Tags.TRUNK);
            if (owner != null) display.addScoreboardTag(Tags.ownerTag(owner));
            logs.add(display);
        }
        return logs;
    }

    private void spawnHitboxes(List<FelledTrunk.Hitbox> hitboxes, double groundY) {
        int boxes = FelledTrunk.hitboxCount(shape.height);
        for (int index = 0; index < boxes; index++) {
            double distance = (index + 0.5) * shape.height / boxes;
            Location at = new Location(shape.world, rig.pivotX + shape.dirX * distance,
                    groundY, rig.pivotZ + shape.dirZ * distance);
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

    void abortOnShutdown() {
        fail("plugin shutdown", null, true);
    }

    private void fail(String phase, Throwable failure) {
        fail(phase, failure, false);
    }

    private void fail(String phase, Throwable failure, boolean deferYieldWithoutSpawn) {
        if (!lifecycle.fail()) {
            releaseManager();
            return;
        }
        boolean deferred = false;
        try {
            recoverStep("entity cleanup", () -> ToppleAnimator.remove(rig));
            recoverStep("leaf loot", this::recoverLeafLoot);
            if (!deferYieldWithoutSpawn) recoverStep("log yield", this::dropLogYield);
            if (!yield.empty()) {
                Location at = recoveryLocation();
                deferred = store.deferYield(shape.world, at, shape.baseMaterial,
                        yield, reservation);
            }
            String detail = failure == null ? "" : ": " + failure.getClass().getSimpleName()
                    + (failure.getMessage() == null ? "" : " (" + failure.getMessage() + ')');
            debug.warn("fell recovered after " + phase + " failure at "
                    + shape.cutX + ',' + shape.cutY + ',' + shape.cutZ + detail
                    + (yield.empty() ? "" : "; undelivered logs=" + yield.remaining()));
        } finally {
            if (!deferred) reservation.close();
            releaseManager();
        }
    }

    private void recoverStep(String step, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError recoveryFailure) {
            debug.warn("fell recovery step failed (" + step + "): "
                    + recoveryFailure.getClass().getSimpleName());
        }
    }

    private void recoverLeafLoot() {
        Location at = recoveryLocation();
        while (!leafLoot.isEmpty()) {
            ItemStack item = leafLoot.peekFirst();
            int delivered = ItemDelivery.tryDeliver(shape.world, at, item);
            if (delivered <= 0) throw new IllegalStateException("leaf item delivery was rejected");
            if (delivered < item.getAmount()) {
                item.setAmount(item.getAmount() - delivered);
                throw new IllegalStateException("leaf item delivery was partial");
            }
            leafLoot.removeFirst();
        }
    }

    private void dropLogYield() {
        Location at = recoveryLocation();
        while (!yield.empty()) {
            int stack = yield.nextStack();
            int delivered = ItemDelivery.tryDeliver(
                    shape.world, at, new ItemStack(shape.baseMaterial, stack));
            if (delivered > 0) yield.delivered(delivered);
            if (delivered < stack) throw new IllegalStateException("log item delivery was rejected");
        }
    }

    private Location recoveryLocation() {
        return new Location(shape.world, rig.pivotX, rig.pivotY - yDrop + 0.5, rig.pivotZ);
    }

    private synchronized void releaseManager() {
        if (managerReleased) return;
        managerReleased = true;
        manager.finish(this, cutKey);
    }
}
