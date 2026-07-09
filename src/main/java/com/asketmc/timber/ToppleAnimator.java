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
 * <p><b>Math.</b> A block originally at world offset {@code o = blockPos - P} is made to orbit pivot
 * {@code P} by angle θ about a horizontal axis {@code a} perpendicular to the fall direction. With a
 * quaternion {@code q = rot(a, θ)}, its desired corner is {@code P + q·o - (0, yDrop, 0)}. Each display's
 * logical entity position is kept at a light-sampling anchor above its visible model, and its translation
 * compensates for that anchor. This preserves the same world-space vertices while preventing Minecraft
 * from sampling display lighting inside the stump or terrain. The client interpolates the fall between
 * the few transforms sent by the server.
 */
final class ToppleAnimator {
    static final double LIGHT_SAMPLE_LIFT = 1.25;

    record LightAnchor(double x, double y, double z) {}

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

    /** Entity position above the rendered block, used by vanilla and shader light sampling. */
    static LightAnchor lightAnchor(TreeShape.Node node, double px, double py, double pz,
                                   Quaternionf q, double yDrop) {
        Transformation pivotTransform = transform(node, px, py, pz, q, yDrop);
        Vector3f centerOffset = new Quaternionf(q).transform(new Vector3f(0.5f, 0.5f, 0.5f));
        Vector3f corner = pivotTransform.getTranslation();
        return new LightAnchor(
                px + corner.x + centerOffset.x,
                py + corner.y + centerOffset.y + LIGHT_SAMPLE_LIFT,
                pz + corner.z + centerOffset.z);
    }

    /** Same visual transform as {@link #transform}, expressed relative to a logical entity anchor. */
    static Transformation transformFromAnchor(TreeShape.Node node, double px, double py, double pz,
                                              Quaternionf q, double yDrop,
                                              double anchorX, double anchorY, double anchorZ) {
        Transformation pivotTransform = transform(node, px, py, pz, q, yDrop);
        Vector3f corner = pivotTransform.getTranslation();
        Vector3f translation = new Vector3f(
                (float) (px + corner.x - anchorX),
                (float) (py + corner.y - anchorY),
                (float) (pz + corner.z - anchorZ));
        return new Transformation(translation, new Quaternionf(q),
                new Vector3f(1, 1, 1), new Quaternionf());
    }

    /** World position of a node under rotation {@code q} and drop {@code yDrop} (for particles/loot). */
    static double[] landedPos(TreeShape.Node node, double px, double py, double pz,
                              Quaternionf q, double yDrop) {
        Vector3f off = new Vector3f((float) (node.x - px + 0.5), (float) (node.y - py + 0.5), (float) (node.z - pz + 0.5));
        Vector3f t = new Quaternionf(q).transform(off);
        return new double[]{px + t.x, py + t.y - yDrop, pz + t.z};
    }

    /**
     * Rebase a landed display onto a light-sampling anchor above its final visible block. The compensating
     * translation keeps every world-space vertex unchanged, so landing has no visual pop. Leaving the
     * brightness override unset lets vanilla lighting and client shader packs use that real anchor rather
     * than a point buried in the terrain.
     */
    static void rebaseLanded(BlockDisplay d, TreeShape.Node node, double px, double py, double pz,
                             Quaternionf qRest, double yDrop) {
        LightAnchor anchor = lightAnchor(node, px, py, pz, qRest, yDrop);
        boolean moved = d.teleport(new Location(d.getWorld(), anchor.x, anchor.y, anchor.z));
        if (!moved) throw new IllegalStateException("landed display teleport was rejected");
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(0);
        d.setTransformation(transformFromAnchor(node, px, py, pz, qRest, yDrop,
                anchor.x, anchor.y, anchor.z));
        d.setBrightness(null);
    }

    /**
     * Spawn the rig at θ=0 (everything sitting exactly where the real blocks were). Each display is
     * logically anchored above its own source block for native light sampling. Leaves are decimated to fit
     * the per-fell display cap. Caller drives the fall by interpolating to the rest transform.
     */
    static Rig spawn(TimberConfig cfg, TreeShape shape) {
        World world = shape.world;
        double px = shape.pivotX, py = shape.pivotY, pz = shape.pivotZ;
        Quaternionf identity = new Quaternionf();
        Rig rig = new Rig();
        rig.pivotX = px; rig.pivotY = py; rig.pivotZ = pz;

        try {
            for (TreeShape.Node n : shape.logs) {
                LightAnchor anchor = lightAnchor(n, px, py, pz, identity, 0);
                Transformation start = transformFromAnchor(n, px, py, pz, identity, 0,
                        anchor.x, anchor.y, anchor.z);
                rig.logs.add(new Seg(spawnOne(cfg, world, anchor, n, start), n));
            }

            int leafBudget = Math.max(0, cfg.maxDisplayEntities - shape.logs.size());
            List<TreeShape.Node> leaves = decimate(shape.leaves, leafBudget);
            for (TreeShape.Node n : leaves) {
                LightAnchor anchor = lightAnchor(n, px, py, pz, identity, 0);
                Transformation start = transformFromAnchor(n, px, py, pz, identity, 0,
                        anchor.x, anchor.y, anchor.z);
                rig.leaves.add(new Seg(spawnOne(cfg, world, anchor, n, start), n));
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

    private static BlockDisplay spawnOne(TimberConfig cfg, World world, LightAnchor anchor,
                                         TreeShape.Node n, Transformation start) {
        Location at = new Location(world, anchor.x, anchor.y, anchor.z);
        return world.spawn(at, BlockDisplay.class, d -> {
            d.setBlock(n.data);
            d.setTransformation(start);
            d.setBrightness(null);
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
