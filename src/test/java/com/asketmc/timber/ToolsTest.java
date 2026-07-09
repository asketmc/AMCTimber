package com.asketmc.timber;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Tools — axe tier detection + too-weak gate")
class ToolsTest {

    private final TimberConfig cfg = new TimberConfig(new YamlConfiguration());

    @Test
    void tierOfName_matchesVanillaPrefixesCaseInsensitively() {
        assertEquals("WOODEN", Tools.tierOfName("WOODEN_AXE"));
        assertEquals("GOLDEN", Tools.tierOfName("GOLDEN_AXE"));
        assertEquals("NETHERITE", Tools.tierOfName("NETHERITE_AXE"));
        assertEquals("DIAMOND", Tools.tierOfName("diamond_axe")); // case-insensitive
        assertEquals("", Tools.tierOfName("STICK"));              // custom / no tier
        assertEquals("", Tools.tierOfName(""));
    }

    @Test
    void tierOf_materialDelegatesToName() {
        assertEquals("WOODEN", Tools.tierOf(Material.WOODEN_AXE));
        assertEquals("NETHERITE", Tools.tierOf(Material.NETHERITE_AXE));
    }

    @Test
    void tooWeak_onlyWithAFiniteCapThatIsExceeded() {
        assertTrue(Tools.tooWeak(12, 100));     // cap 12 < 100 logs
        assertFalse(Tools.tooWeak(12, 12));     // exactly at the cap is allowed
        assertFalse(Tools.tooWeak(12, 8));
        assertFalse(Tools.tooWeak(-1, 999_999)); // -1 == unlimited
        assertTrue(Tools.tooWeak(0, 1));        // a 0 cap blocks everything
    }

    @Test
    @Tag("P0")
    void capInteraction_woodenCantFellGiantsNetheriteCan() {
        int wooden = cfg.tierMaxLogs.get(Tools.tierOfName("WOODEN_AXE"));
        int netherite = cfg.tierMaxLogs.get(Tools.tierOfName("NETHERITE_AXE"));
        assertTrue(Tools.tooWeak(wooden, 100), "wooden axe should not fell a 100-log giant");
        assertFalse(Tools.tooWeak(wooden, 8), "wooden axe should fell a small tree");
        assertFalse(Tools.tooWeak(netherite, 5000), "netherite is unlimited");
    }

    @Test
    void isAxeHandlesNullAirVanillaAndConfiguredExtras() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("axes.extra-items", java.util.List.of("NETHERITE_HOE"));
        TimberConfig custom = new TimberConfig(yml);

        assertFalse(Tools.isAxe(null, custom));
        assertFalse(Tools.isAxe(new ItemStack(Material.AIR), custom));
        assertTrue(Tools.isAxe(new ItemStack(Material.NETHERITE_HOE), custom));
    }

    @Test
    void maxLogsForUsesScalingDefaultForCustomToolsAndUnlimitedWhenDisabled() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("tool-scaling.default-max", 7);
        yml.set("axes.extra-items", java.util.List.of("NETHERITE_HOE"));
        TimberConfig custom = new TimberConfig(yml);

        assertEquals(7, Tools.maxLogsFor(new ItemStack(Material.STICK), custom));

        yml.set("tool-scaling.enabled", false);
        TimberConfig disabled = new TimberConfig(yml);
        assertEquals(-1, Tools.maxLogsFor(new ItemStack(Material.WOODEN_AXE), disabled));
        assertEquals(-1, Tools.maxLogsFor(null, custom));
    }
}
