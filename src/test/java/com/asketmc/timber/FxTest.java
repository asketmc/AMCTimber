package com.asketmc.timber;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("Fx — tree-size weighting + leaf tints")
class FxTest {

    @Test
    void sizeT_clampedToZeroOne() {
        assertEquals(0f, Fx.sizeT(0));
        assertEquals(0.5f, Fx.sizeT(20), 1e-6);
        assertEquals(1f, Fx.sizeT(40));
        assertEquals(1f, Fx.sizeT(4000)); // clamped high
        assertEquals(0f, Fx.sizeT(-10));  // clamped low
    }

    @Test
    void tintFor_alwaysResolvesIncludingUnknownLeaves() {
        assertNotNull(Fx.tintFor(Material.OAK_LEAVES));
        assertNotNull(Fx.tintFor(Material.SPRUCE_LEAVES));
        assertNotNull(Fx.tintFor(Material.JUNGLE_LEAVES));
        assertNotNull(Fx.tintFor(Material.STONE)); // unknown -> default tint, never null
    }
}
