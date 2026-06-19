package com.asketmc.timber;

import org.bukkit.Location;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TreeScanner — species fencing, leaf matching, fall direction")
class TreeScannerLogicTest {

    @Test
    void speciesOfName_stripsLogWoodStemHyphaeBlockAndStripped() {
        assertEquals("OAK", TreeScanner.speciesOfName("OAK_LOG"));
        assertEquals("OAK", TreeScanner.speciesOfName("OAK_WOOD"));
        assertEquals("DARK_OAK", TreeScanner.speciesOfName("STRIPPED_DARK_OAK_WOOD"));
        assertEquals("CRIMSON", TreeScanner.speciesOfName("CRIMSON_STEM"));
        assertEquals("WARPED", TreeScanner.speciesOfName("WARPED_HYPHAE"));
        assertEquals("BAMBOO", TreeScanner.speciesOfName("BAMBOO_BLOCK"));
    }

    @Test
    void speciesOf_materialDelegates_andFencesDifferentWoods() {
        assertEquals("OAK", TreeScanner.speciesOf(Material.OAK_LOG));
        assertNotEquals(TreeScanner.speciesOf(Material.OAK_LOG),
                TreeScanner.speciesOf(Material.BIRCH_LOG)); // never spread oak -> birch
    }

    @Test
    void leafMatches_sameSpeciesAndAzaleaCountsAsOakOnly() {
        assertTrue(TreeScanner.leafMatchesSpeciesName("OAK", "OAK_LEAVES"));
        assertTrue(TreeScanner.leafMatchesSpeciesName("OAK", "AZALEA_LEAVES"));
        assertTrue(TreeScanner.leafMatchesSpeciesName("OAK", "FLOWERING_AZALEA_LEAVES"));
        assertTrue(TreeScanner.leafMatchesSpeciesName("BIRCH", "BIRCH_LEAVES"));
        assertFalse(TreeScanner.leafMatchesSpeciesName("OAK", "BIRCH_LEAVES"));
        assertFalse(TreeScanner.leafMatchesSpeciesName("SPRUCE", "AZALEA_LEAVES")); // azalea is oak-only
    }

    @Test
    void fallDir_pointsFromPlayerToTrunk_unitLength() {
        double[] east = TreeScanner.fallDir(new Location(null, 0.5, 0, 0.5), 5, 0, 0);
        assertEquals(1.0, east[0], 1e-6);
        assertEquals(0.0, east[1], 1e-6);

        double[] north = TreeScanner.fallDir(new Location(null, 0.5, 0, 0.5), 0, 0, 5);
        assertEquals(0.0, north[0], 1e-6);
        assertEquals(1.0, north[1], 1e-6);

        double[] diag = TreeScanner.fallDir(new Location(null, 0, 0, 0), 3, 0, 4);
        assertEquals(1.0, Math.hypot(diag[0], diag[1]), 1e-6); // always normalised
    }

    @Test
    void fallDir_playerInsideTrunk_fallsAlongFacingStillUnit() {
        Location loc = new Location(null, 0.5, 0, 0.5, 0f, 0f); // dead centre -> uses yaw
        double[] d = TreeScanner.fallDir(loc, 0, 0, 0);
        assertEquals(1.0, Math.hypot(d[0], d[1]), 1e-6);
    }
}
