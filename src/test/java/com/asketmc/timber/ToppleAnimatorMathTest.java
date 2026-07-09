package com.asketmc.timber;

import org.bukkit.util.Transformation;
import org.bukkit.configuration.file.YamlConfiguration;
import org.joml.Quaternionf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ToppleAnimator — rotation axis, transform invariants, decimation")
class ToppleAnimatorMathTest {

    @Test
    void axis_isUnitLengthHorizontalAndPerpendicularToFall() {
        for (double[] d : new double[][]{{1, 0}, {0, 1}, {0.6, 0.8}, {-0.7071, 0.7071}}) {
            float[] a = ToppleAnimator.axis(d[0], d[1]);
            assertEquals(1.0, Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]), 1e-3);
            assertEquals(0.0, a[0] * d[0] + a[2] * d[1], 1e-3); // perpendicular to the fall direction
            assertEquals(0f, a[1]);                              // horizontal axis
        }
    }

    @Test
    void transform_atRestZeroIsIdentityPlacement() {
        TreeShape.Node node = new TreeShape.Node(0, 5, 0, null, true);
        Transformation t = ToppleAnimator.transform(node, 0, 0, 0, new Quaternionf(), 0);
        assertEquals(5f, t.getTranslation().y, 1e-3); // block sits exactly where it was
        assertEquals(0f, t.getTranslation().x, 1e-3);
    }

    @Test
    void transform_topplesTowardPlusXAndGroundDropLowersIt() {
        TreeShape.Node node = new TreeShape.Node(0, 5, 0, null, true);
        Quaternionf q = ToppleAnimator.quat(ToppleAnimator.axis(1, 0), Math.toRadians(88));
        Transformation flat = ToppleAnimator.transform(node, 0, 0, 0, q, 0);
        assertTrue(flat.getTranslation().x > 0.9f, "should fall toward +x");
        assertTrue(flat.getTranslation().y < 0.2f, "should lie near the ground");
        Transformation dropped = ToppleAnimator.transform(node, 0, 0, 0, q, 3.0);
        assertEquals(3.0f, flat.getTranslation().y - dropped.getTranslation().y, 1e-3); // yDrop == 3
    }

    @Test
    void landedPos_tracksTheTransform() {
        TreeShape.Node node = new TreeShape.Node(0, 5, 0, null, true);
        Quaternionf q = ToppleAnimator.quat(ToppleAnimator.axis(1, 0), Math.toRadians(88));
        double[] lp = ToppleAnimator.landedPos(node, 0, 0, 0, q, 3.0);
        assertTrue(lp[0] > 5.0 && lp[0] < 6.0, "x ~ 5.5");
        assertTrue(lp[1] > -3.6 && lp[1] < -3.0, "y dropped");
        assertEquals(0.5, lp[2], 1e-3);
    }

    @Test
    void decimate_capsLargeListsKeepsSmallOnesAndHandlesZeroBudget() {
        assertEquals(400, ToppleAnimator.decimate(nodes(2000), 400).size()); // capped to budget
        assertEquals(3, ToppleAnimator.decimate(nodes(3), 400).size());      // small list untouched
        assertTrue(ToppleAnimator.decimate(nodes(50), 0).isEmpty());          // zero budget
        List<TreeShape.Node> exact = nodes(10);
        assertSame(exact, ToppleAnimator.decimate(exact, 10));                // at/under budget -> same list
    }

    @Test
    void canRenderLogs_rejectsTreesWhoseLogsAloneExceedTheDisplayCap() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("animation.max-display-entities", 16);
        TimberConfig cfg = new TimberConfig(yml);

        assertTrue(ToppleAnimator.canRenderLogs(cfg, shapeWithLogs(16)));
        assertTrue(!ToppleAnimator.canRenderLogs(cfg, shapeWithLogs(17)));
    }

    @Test
    @Tag("P0")
    void plannedPeakAccountsForLeavesAndLandedHitboxes() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("animation.max-display-entities", 16);
        TimberConfig cfg = new TimberConfig(yml);
        TreeShape shape = shape(10, 100, 20);

        assertEquals(16, ToppleAnimator.renderedCount(cfg, shape));
        assertEquals(18, ToppleAnimator.plannedPeakEntities(cfg, shape));
    }

    private static List<TreeShape.Node> nodes(int n) {
        List<TreeShape.Node> list = new ArrayList<>();
        for (int i = 0; i < n; i++) list.add(new TreeShape.Node(i, 0, 0, null, true));
        return list;
    }

    private static TreeShape shapeWithLogs(int logs) {
        return shape(logs, 0, logs);
    }

    private static TreeShape shape(int logs, int leaves, int height) {
        return new TreeShape(null, nodes(logs), nodes(leaves), 0, 0, 0, null,
                0, 0, 0, 1, 0, height, true, true, null);
    }
}
