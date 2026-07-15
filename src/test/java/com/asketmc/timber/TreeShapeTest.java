package com.asketmc.timber;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TreeShapeTest {
    @Test
    @Tag("P0")
    void effectiveSizeCountsTheWholeKept2x2StumpFootprint() {
        TreeShape shape = new TreeShape(null, nodes(12), nodes(4), List.of(), List.of(),
                0, 0, 0, null, 0, 1, 0, 1, 0, 4, true, true, null);

        assertEquals(12, shape.logCount());
        assertEquals(4, shape.stumpCount());
        assertEquals(16, shape.effectiveLogCount());
    }

    private static List<TreeShape.Node> nodes(int count) {
        List<TreeShape.Node> nodes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            nodes.add(new TreeShape.Node(i, 0, 0, null, true));
        }
        return nodes;
    }
}
