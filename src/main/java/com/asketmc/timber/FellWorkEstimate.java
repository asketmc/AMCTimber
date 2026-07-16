package com.asketmc.timber;

/** Conservative logical-operation estimates used before an atomic fell launch. */
final class FellWorkEstimate {
    private FellWorkEstimate() {}

    static int launchBlockOps(TreeShape shape, boolean leafLoot) {
        int mutable = shape.logs.size() + shape.leaves.size() + shape.attachments.size();
        int snapshots = mutable + shape.stumps.size();
        int lootReads = leafLoot ? shape.leaves.size() + shape.attachments.size() : 0;
        long groundProbe = Math.max(0L, (long) Math.floor(shape.pivotY) - shape.world.getMinHeight());
        long estimate = (long) snapshots + (long) mutable * 4L + lootReads + groundProbe;
        return estimate > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) estimate;
    }

    static int launchEntityOps(TimberConfig cfg, TreeShape shape) {
        return Math.multiplyExact(ToppleAnimator.renderedCount(cfg, shape), 2);
    }

    static int phaseEntityOps(ToppleAnimator.Rig rig) {
        return Math.max(1, rig.logs.size() + rig.leaves.size());
    }

    static int landingEntityOps(ToppleAnimator.Rig rig, LandingPlan landing) {
        return Math.max(1, rig.logs.size() + rig.leaves.size() + landing.hitboxLocations.size());
    }

    static int queuedEntityOps(TimberConfig cfg, TreeShape shape) {
        long rig = Math.max(1, ToppleAnimator.renderedCount(cfg, shape));
        long landing = rig + FelledTrunk.hitboxCount(shape.height);
        long phases = (cfg.creakTicks > 0 ? rig : 0) + rig + 24L + rig + landing;
        long cleanup = ToppleAnimator.plannedPeakEntities(cfg, shape);
        long total = phases + cleanup;
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }
}
