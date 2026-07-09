package com.asketmc.timber;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.inventory.ItemStack;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One in-flight topple, staged for atmosphere: a creaking lean, the accelerating fall (with leaves
 * drifting off mid-arc), a slight overshoot, the settle, and an impact line of dust along where the
 * trunk hits. Individual displays need no per-tick code — the client interpolates between the few
 * keyframes we set, so the server cost is a handful of {@code setTransformation} passes.
 *
 * <p>Every staged step is scheduled on the region thread that owns the tree (via {@link Sched}), so the
 * whole sequence is correct on Folia as well as Paper.
 */
final class FellJob {
    private static final double LEAN_DEGREES = 2.5;

    private final TimberPlugin plugin;
    private final FellJobManager mgr;
    private final TreeShape shape;
    private final ToppleAnimator.Rig rig;
    private final Player ownerPlayer;
    private final UUID owner;
    private final long cutKey;
    private final float[] axis;
    private final double yDrop;
    private final List<ItemStack> leafLoot;
    private final float sizeT;

    FellJob(TimberPlugin plugin, FellJobManager mgr, TreeShape shape, ToppleAnimator.Rig rig,
            Player ownerPlayer, UUID owner, long cutKey, double yDrop, List<ItemStack> leafLoot) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.shape = shape;
        this.rig = rig;
        this.ownerPlayer = ownerPlayer;
        this.owner = owner;
        this.cutKey = cutKey;
        this.axis = ToppleAnimator.axis(shape.dirX, shape.dirZ);
        this.yDrop = yDrop;
        this.leafLoot = leafLoot;
        this.sizeT = Fx.sizeT(shape.logCount());
    }

    void start() {
        TimberConfig cfg = plugin.cfg();
        plugin.debug().full("fell start: " + shape.baseMaterial + " logs=" + shape.logCount()
                + " leaves=" + rig.leaves.size() + " leafLoot=" + leafLoot.size()
                + " yDrop=" + String.format("%.1f", yDrop)
                + " at " + shape.cutX + "," + shape.cutY + "," + shape.cutZ
                + (owner != null ? " by " + owner : " (test)"));

        double lean = Math.toRadians(LEAN_DEGREES);
        double rest = Math.toRadians(cfg.fallRestDegrees);
        double overshoot = Math.toRadians(cfg.fallRestDegrees + cfg.overshootDegrees);

        long t = 1L;
        // Phase 0: the creak — the trunk leans a couple of degrees while the wood groans.
        if (cfg.creakTicks > 0) {
            final int creakTicks = cfg.creakTicks;
            schedule(t, () -> {
                plugin.fx().creak(pivotLoc(), sizeT);
                applyAngle(lean, creakTicks, 0);
            });
            t += cfg.creakTicks;
        }

        // Phase 1: the fall — one interpolated arc to a slight overshoot, dropping to the ground line.
        final long fallStart = t;
        schedule(fallStart, () -> applyAngle(overshoot, cfg.fallDurationTicks, yDrop));
        schedule(fallStart + Math.max(1, cfg.fallDurationTicks / 2), this::midFall);

        // Phase 2: settle back to rest (the "bounce") + the impact line.
        long settleAt = fallStart + cfg.fallDurationTicks;
        schedule(settleAt, () -> {
            applyAngle(rest, Math.max(1, cfg.bounceTicks), yDrop);
            plugin.fx().impact(shape.world, impactLine(), shape.baseMaterial.createBlockData(), sizeT);
            crush(cfg);
        });

        // Land: convert to a felled trunk.
        schedule(settleAt + Math.max(1, cfg.bounceTicks), this::land);
    }

    private void schedule(long ticks, Runnable r) {
        plugin.sched().atLocationLater(pivotLoc(), r, ticks);
    }

    private void applyAngle(double angle, int durationTicks, double drop) {
        Quaternionf q = ToppleAnimator.quat(axis, angle);
        for (ToppleAnimator.Seg s : rig.logs) interp(s, q, durationTicks, drop);
        for (ToppleAnimator.Seg s : rig.leaves) interp(s, q, durationTicks, drop);
    }

    private void interp(ToppleAnimator.Seg s, Quaternionf q, int durationTicks, double drop) {
        BlockDisplay d = s.display;
        if (d == null || !d.isValid()) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(durationTicks);
        d.setTransformation(ToppleAnimator.transform(s.node, rig.pivotX, rig.pivotY, rig.pivotZ, q, drop));
    }

    private Location pivotLoc() {
        return new Location(shape.world, rig.pivotX, rig.pivotY, rig.pivotZ);
    }

    /** Mid-fall: leaves rush + a drift of tinted leaf particles peels off the canopy. */
    private void midFall() {
        TimberConfig cfg = plugin.cfg();
        if (rig.leaves.isEmpty()) return;
        double mid = Math.toRadians(cfg.fallRestDegrees * 0.45);
        Quaternionf q = ToppleAnimator.quat(axis, mid);
        List<TreeShape.Node> sample = ToppleAnimator.decimate(nodesOf(rig.leaves), 24);
        List<double[]> positions = new ArrayList<>(sample.size());
        for (TreeShape.Node n : sample) {
            positions.add(ToppleAnimator.landedPos(n, rig.pivotX, rig.pivotY, rig.pivotZ, q, yDrop * 0.5));
        }
        TreeShape.Node first = rig.leaves.get(0).node;
        plugin.fx().fallingLeaves(shape.world, positions, first.data.getMaterial());
        if (!positions.isEmpty()) {
            double[] c = positions.get(positions.size() / 2);
            plugin.fx().fallRustle(new Location(shape.world, c[0], c[1], c[2]), sizeT);
        }
    }

    /** Dust line along where the trunk slams down. */
    private List<Location> impactLine() {
        List<Location> line = new ArrayList<>();
        double y = rig.pivotY - yDrop;
        for (double d = 1.0; d <= Math.max(2.0, shape.height); d += 2.5) {
            line.add(new Location(shape.world, rig.pivotX + shape.dirX * d, y, rig.pivotZ + shape.dirZ * d));
        }
        return line;
    }

    private static List<TreeShape.Node> nodesOf(List<ToppleAnimator.Seg> segs) {
        List<TreeShape.Node> out = new ArrayList<>(segs.size());
        for (ToppleAnimator.Seg s : segs) out.add(s.node);
        return out;
    }

    /**
     * Valheim-style impact: the slamming trunk crushes whatever stands in its landing path. Damage scales
     * with tree size (a momentum proxy) and is capped so a giant can one-shot an unarmored player. The
     * feller usually stands at the stump (behind the pivot) and so is safe — you only get crushed standing
     * where it falls. Server-side health damage only (no knockback), so it never trips movement anti-cheat.
     */
    private void crush(TimberConfig cfg) {
        double dmg = cfg.crushDamageFor(shape.logCount());
        if (dmg <= 0) return;
        double groundY = rig.pivotY - yDrop;
        double r = cfg.crushRadius;
        double span = Math.max(2.0, shape.height);
        Set<UUID> hit = new HashSet<>();
        for (double d = 0.5; d <= span; d += 1.0) {
            Location at = new Location(shape.world,
                    rig.pivotX + shape.dirX * d, groundY + 1.0, rig.pivotZ + shape.dirZ * d);
            if (!crushAllowedAt(at)) continue;
            for (Entity e : shape.world.getNearbyEntities(at, r, r + 0.6, r)) {
                if (!(e instanceof LivingEntity le) || !hit.add(e.getUniqueId())) continue;
                if (!crushAllowedAt(le.getLocation())) continue;
                if (le instanceof Player pl) {
                    if (!cfg.crushHitPlayers) continue;
                    if (pl.getGameMode() == GameMode.CREATIVE || pl.getGameMode() == GameMode.SPECTATOR) continue;
                    boolean feller = owner != null && owner.equals(pl.getUniqueId());
                    if (!feller && !cfg.crushPvp) continue;   // only the woodcutter, unless pvp is enabled
                } else if (le instanceof ArmorStand) {
                    continue;                                  // don't smash decorative armour stands
                } else if (protectedMob(le)) {
                    continue;                                  // never grief pets, leashed mobs or villagers
                } else if (!cfg.crushHitMobs) {
                    continue;
                }
                le.damage(dmg);
            }
        }
    }

    private boolean crushAllowedAt(Location loc) {
        return ownerPlayer == null || plugin.protection().canBreak(ownerPlayer, loc, shape.baseMaterial);
    }

    private static boolean protectedMob(LivingEntity le) {
        if (le instanceof AbstractVillager) return true;
        if (le instanceof Tameable tameable && tameable.isTamed()) return true;
        return le.isLeashed();
    }

    private void land() {
        try {
        TimberConfig cfg = plugin.cfg();
        Quaternionf qRest = ToppleAnimator.quat(axis, Math.toRadians(cfg.fallRestDegrees));

        // The canopy crashes: debris + loot where the leaves came to rest, then the leaf displays go.
        org.bukkit.block.data.BlockData leafData = rig.leaves.isEmpty() ? null : rig.leaves.get(0).node.data;
        List<TreeShape.Node> leafSample = ToppleAnimator.decimate(nodesOf(rig.leaves), 40);
        List<double[]> landed = new ArrayList<>(leafSample.size());
        for (TreeShape.Node n : leafSample) {
            landed.add(ToppleAnimator.landedPos(n, rig.pivotX, rig.pivotY, rig.pivotZ, qRest, yDrop));
        }
        if (leafData != null) plugin.fx().leafCrash(shape.world, landed, leafData);
        for (ToppleAnimator.Seg s : rig.leaves) if (s.display != null && s.display.isValid()) s.display.remove();

        if (!leafLoot.isEmpty()) {
            List<double[]> spots = landed.isEmpty()
                    ? List.of(new double[]{rig.pivotX + shape.dirX * shape.height * 0.7,
                                           rig.pivotY - yDrop + 0.5,
                                           rig.pivotZ + shape.dirZ * shape.height * 0.7})
                    : landed;
            int i = 0;
            for (ItemStack it : leafLoot) {
                double[] p = spots.get(i++ % spots.size());
                shape.world.dropItemNaturally(new Location(shape.world, p[0], p[1] + 0.3, p[2]), it);
            }
        }

        // Keep the lying log displays as the trunk, far end first so chopping shortens toward the base.
        // Each display is rebased onto its landed position — during the fall they all "live" at the pivot
        // (only the transform spans the tree), which LOS-based entity hiding judges as one occludable
        // point and blinks the whole trunk; after the rebase the trunk is a row of real positions.
        List<ToppleAnimator.Seg> ordered = new ArrayList<>(rig.logs);
        ordered.sort(Comparator.comparingInt((ToppleAnimator.Seg s) -> s.node.y).reversed());
        List<BlockDisplay> logs = new ArrayList<>();
        for (ToppleAnimator.Seg s : ordered) {
            BlockDisplay d = s.display;
            if (d == null || !d.isValid()) continue;
            ToppleAnimator.rebaseLanded(d, s.node, rig.pivotX, rig.pivotY, rig.pivotZ, qRest, yDrop);
            d.removeScoreboardTag(Tags.ANIM);
            d.addScoreboardTag(Tags.TRUNK);
            if (owner != null) d.addScoreboardTag(Tags.ownerTag(owner));
            logs.add(d);
        }

        // A row of hitboxes along the whole lying trunk, so swinging anywhere on it works.
        double groundY = rig.pivotY - yDrop;
        int boxes = FelledTrunk.hitboxCount(shape.height);
        final UUID ownerF = owner;
        List<FelledTrunk.Hitbox> hitboxes = new ArrayList<>(boxes);
        for (int k = 0; k < boxes; k++) {
            double dist = (k + 0.5) * shape.height / boxes;
            Location at = new Location(shape.world,
                    rig.pivotX + shape.dirX * dist, groundY, rig.pivotZ + shape.dirZ * dist);
            Interaction hb = shape.world.spawn(at, Interaction.class, i -> {
                i.setInteractionWidth(1.7f);
                i.setInteractionHeight(1.4f);
                i.setResponsive(true);
                i.setPersistent(false);
                i.addScoreboardTag(Tags.TRUNK);
                if (ownerF != null) i.addScoreboardTag(Tags.ownerTag(ownerF));
            });
            hitboxes.add(new FelledTrunk.Hitbox(hb, dist));
        }

        int yield = cfg.logsYielded(shape.logCount());
        int hits = cfg.hitsRequiredFor(shape.logCount());
        FelledTrunk trunk = new FelledTrunk(shape.world, logs, hitboxes, owner, shape.baseMaterial,
                yield, hits, rig.pivotX, groundY, rig.pivotZ, shape.dirX, shape.dirZ,
                Math.max(2.0, shape.height), cfg.despawnSeconds);
        plugin.store().register(trunk);

        plugin.debug().full("toppled + trunk spawned: logs=" + shape.logCount()
                + " yield=" + yield + " hits=" + hits + " hitboxes=" + hitboxes.size()
                + " leafLoot=" + leafLoot.size());
        } finally {
            mgr.finish(this, cutKey);
        }
    }

    void emergencyDrop() {
        for (ToppleAnimator.Seg s : rig.leaves) if (s.display != null && s.display.isValid()) s.display.remove();
        for (ToppleAnimator.Seg s : rig.logs) if (s.display != null && s.display.isValid()) s.display.remove();
        int remaining = plugin.cfg().logsYielded(shape.logCount());
        Location drop = new Location(shape.world, rig.pivotX, rig.pivotY - yDrop + 0.5, rig.pivotZ);
        while (remaining > 0) {
            int stack = Math.min(64, remaining);
            shape.world.dropItemNaturally(drop, new ItemStack(shape.baseMaterial, stack));
            remaining -= stack;
        }
    }
}
