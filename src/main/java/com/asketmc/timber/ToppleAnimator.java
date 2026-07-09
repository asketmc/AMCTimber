package com.asketmc.timber;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the toppling rig out of {@link BlockDisplay} entities and computes the rotation transforms.
 *
 * <p><b>Math.</b> Every block is shown by one display spawned at the pivot {@code P}. A block originally
 * at world offset {@code o = blockPos - P} is made to orbit {@code P} by angle θ about a horizontal axis
 * {@code a} perpendicular to the fall direction: with a quaternion {@code q = rot(a, θ)}, the display's
 * transform is {@code translation = q·o − (0, yDrop, 0)}, {@code leftRotation = q}. At θ=0/yDrop=0 that
 * is the identity (block sits exactly where it was); at θ≈90° the column has rotated flat along the fall
 * direction, and {@code yDrop} lowers the whole rig to the ground when the cut happened above it (stump
 * mode mid-trunk cuts). The client interpolates the whole arc, so the server only sets the transform a
 * couple of times per fall.
 */
final class ToppleAnimator {
    private ToppleAnimator() {}

    /** A display paired with the snapshot node it renders (so the fall transform can be recomputed). */
    static final class Seg {
        final BlockDisplay display;
        final TreeShape.Node node;
        Seg(BlockDisplay display, TreeShape.Node node) { this.display = display; this.node = node; }
    }

    /** The spawned rig: log segments (kept as the felled trunk) and leaf segments (removed on land). */
    static final class Rig {
        final List<Seg> logs = new ArrayList<>();
        final List<Seg> leaves = new ArrayList<>();
        double pivotX, pivotY, pivotZ;
    }

    /** Horizontal rotation axis for a fall direction (dx,dz): perpendicular, signed so +θ falls toward d. */
    static float[] axis(double dirX, double dirZ) {
        // a = (dz, 0, -dx) — derivation in the class doc; unit since (dx,dz) is unit.
        return new float[]{(float) dirZ, 0f, (float) -dirX};
    }

    static Quaternionf quat(float[] axis, double angleRad) {
        return new Quaternionf().fromAxisAngleRad(axis[0], axis[1], axis[2], (float) angleRad);
    }

    /** Transform that places {@code node} rotated by {@code q} about pivot P, dropped by {@code yDrop}. */
    static Transformation transform(TreeShape.Node node, double px, double py, double pz,
                                    Quaternionf q, double yDrop) {
        Vector3f off = new Vector3f((float) (node.x - px), (float) (node.y - py), (float) (node.z - pz));
        Vector3f translation = new Quaternionf(q).transform(off);    // q·o (copy q so we don't mutate)
        translation.y -= (float) yDrop;
        return new Transformation(translation, new Quaternionf(q), new Vector3f(1, 1, 1), new Quaternionf());
    }

    /** World position of a node under rotation {@code q} and drop {@code yDrop} (for particles/loot). */
    static double[] landedPos(TreeShape.Node node, double px, double py, double pz,
                              Quaternionf q, double yDrop) {
        Vector3f off = new Vector3f((float) (node.x - px + 0.5), (float) (node.y - py + 0.5), (float) (node.z - pz + 0.5));
        Vector3f t = new Quaternionf(q).transform(off);
        return new double[]{px + t.x, py + t.y - yDrop, pz + t.z};
    }

    /**
     * Rebase a landed display: move the ENTITY to its final world-space corner and fold the translation
     * away (keeping only the rest rotation), so the entity's position matches what players actually see.
     * While toppling, every display "lives" at the pivot and only its transformation spans the tree —
     * LOS-based systems (anti-ESP entity hiding, tracking range) judge the pivot point and hide the whole
     * trunk. After this rebase the lying trunk is a row of correctly-positioned entities. The world-space
     * vertices are identical before/after (P+q·(o)+q·v == P_new+q·v), so there is no visual pop.
     */
    static void rebaseLanded(BlockDisplay d, TreeShape.Node node, double px, double py, double pz,
                             Quaternionf qRest, double yDrop) {
        Transformation t = transform(node, px, py, pz, qRest, yDrop);
        Vector3f tr = t.getTranslation();
        boolean moved = d.teleport(new Location(d.getWorld(), px + tr.x, py + tr.y, pz + tr.z));
        if (!moved) throw new IllegalStateException("landed display teleport was rejected");
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(0);
        d.setTransformation(new Transformation(new Vector3f(0, 0, 0), new Quaternionf(qRest),
                new Vector3f(1, 1, 1), new Quaternionf()));
    }

    /**
     * Spawn the rig at θ=0 (everything sitting exactly where the real blocks were). Leaves are decimated to
     * fit the per-fell display cap. Caller drives the fall by interpolating to the rest transform.
     */
    static Rig spawn(TimberConfig cfg, TreeShape shape) {
        World world = shape.world;
        double px = shape.pivotX, py = shape.pivotY, pz = shape.pivotZ;
        Location at = new Location(world, px, py, pz);
        Quaternionf identity = new Quaternionf();
        Rig rig = new Rig();
        rig.pivotX = px; rig.pivotY = py; rig.pivotZ = pz;

        try {
            for (TreeShape.Node n : shape.logs) {
                Transformation start = transform(n, px, py, pz, identity, 0);
                rig.logs.add(new Seg(spawnOne(cfg, world, at, n, start), n));
            }

            int leafBudget = Math.max(0, cfg.maxDisplayEntities - shape.logs.size());
            List<TreeShape.Node> leaves = decimate(shape.leaves, leafBudget);
            for (TreeShape.Node n : leaves) {
                Transformation start = transform(n, px, py, pz, identity, 0);
                rig.leaves.add(new Seg(spawnOne(cfg, world, at, n, start), n));
            }
            return rig;
        } catch (RuntimeException | LinkageError failure) {
            remove(rig);
            throw failure;
        }
    }

    static boolean canRenderLogs(TimberConfig cfg, TreeShape shape) {
        return shape.logs.size() <= cfg.maxDisplayEntities;
    }

    static int renderedCount(TimberConfig cfg, TreeShape shape) {
        int leaves = Math.min(shape.leaves.size(), Math.max(0, cfg.maxDisplayEntities - shape.logs.size()));
        return shape.logs.size() + leaves;
    }

    static int plannedPeakEntities(TimberConfig cfg, TreeShape shape) {
        int landed = shape.logs.size() + FelledTrunk.hitboxCount(shape.height);
        return Math.max(renderedCount(cfg, shape), landed);
    }

    static void remove(Rig rig) {
        if (rig == null) return;
        for (Seg segment : rig.leaves) removeBestEffort(segment.display);
        for (Seg segment : rig.logs) removeBestEffort(segment.display);
        rig.leaves.clear();
        rig.logs.clear();
    }

    private static void removeBestEffort(BlockDisplay display) {
        try {
            if (display != null && display.isValid()) display.remove();
        } catch (RuntimeException | LinkageError ignored) {
            // Startup/shutdown orphan sweeping is the final cleanup backstop.
        }
    }

    private static BlockDisplay spawnOne(TimberConfig cfg, World world, Location at,
                                         TreeShape.Node n, Transformation start) {
        return world.spawn(at, BlockDisplay.class, d -> {
            d.setBlock(n.data);
            d.setTransformation(start);
            d.setPersistent(false);
            d.setViewRange(cfg.viewRange);
            d.addScoreboardTag(Tags.ANIM);
        });
    }

    /** Evenly sample {@code src} down to at most {@code budget} entries (keeps spatial spread). */
    static List<TreeShape.Node> decimate(List<TreeShape.Node> src, int budget) {
        if (budget <= 0) return new ArrayList<>();
        if (src.size() <= budget) return src;
        List<TreeShape.Node> out = new ArrayList<>(budget);
        double stride = (double) src.size() / budget;
        for (int i = 0; i < budget; i++) out.add(src.get((int) Math.floor(i * stride)));
        return out;
    }
}
