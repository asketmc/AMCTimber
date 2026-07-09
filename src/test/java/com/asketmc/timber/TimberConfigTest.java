package com.asketmc.timber;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TimberConfig — yield / xp / hits / durability / crush maths (defaults)")
class TimberConfigTest {

    private TimberConfig cfg;

    @BeforeEach
    void setUp() {
        cfg = new TimberConfig(new YamlConfiguration()); // empty config => all documented defaults
    }

    @Test
    void yield_isReducedRoundedAndNonNegative() {
        assertEquals(16, cfg.logsYielded(20));           // round(20 * 0.8)
        assertEquals(0, cfg.logsYielded(0));
        assertEquals(0, cfg.logsYielded(-5));            // never negative
        assertTrue(cfg.logsYielded(10) <= cfg.logsYielded(20)); // monotonic
    }

    @Test
    void xp_isPerLogTimesYield() {
        assertEquals(16 * cfg.xpPerLog, cfg.xpFor(16));
        assertEquals(0, cfg.xpFor(0));
        assertEquals(0, cfg.xpFor(-3));
    }

    @Test
    void hits_scaleWithTreeSizeAndAreCapped() {
        assertEquals(3, cfg.hitsRequiredFor(1));         // base
        assertEquals(4, cfg.hitsRequiredFor(6));         // round(3 + 6*0.15)
        assertEquals(9, cfg.hitsRequiredFor(40));        // 3 + 40*0.15
        assertEquals(cfg.maxHits, cfg.hitsRequiredFor(500)); // capped at max-hits
        assertTrue(cfg.hitsRequiredFor(0) >= 1);         // floor
        assertTrue(cfg.hitsRequiredFor(5) <= cfg.hitsRequiredFor(30)); // monotonic
    }

    @Test
    void efficiency_addsProgressSafely() {
        assertEquals(1, TimberConfig.progressPerHit(0));
        assertEquals(2, TimberConfig.progressPerHit(2));
        assertEquals(3, TimberConfig.progressPerHit(5));
        assertEquals(1, TimberConfig.progressPerHit(-3)); // negative level is safe
    }

    @Test
    void durability_isCeilOfLogsTimesRate() {
        assertEquals(2, cfg.durabilityForFell(6));       // ceil(6 * 0.25) — stump mode charges in full
        assertEquals(10, cfg.durabilityForFell(40));     // ceil(40 * 0.25)
        assertEquals(0, cfg.durabilityForFell(0));

        YamlConfiguration nonStump = new YamlConfiguration();
        nonStump.set("trunk.leave-stump", false);
        assertEquals(2, new TimberConfig(nonStump).durabilityForFell(6));
    }

    @Test
    void crush_scalesIsCappedAndZeroForNothing() {
        assertEquals(0.0, cfg.crushDamageFor(0));
        assertEquals(Math.min(cfg.crushMaxDamage, cfg.crushBaseDamage + 10 * cfg.crushPerLogDamage),
                cfg.crushDamageFor(10));
        assertEquals(cfg.crushMaxDamage, cfg.crushDamageFor(100_000)); // capped
        assertTrue(cfg.crushDamageFor(5) <= cfg.crushDamageFor(50));   // monotonic
    }

    @Test
    void emptyWorldWhitelistAllowsEverywhere() {
        assertTrue(cfg.worldAllowed("anything"));
    }

    @Test
    void toolScaling_defaultTiersAreLoaded() {
        assertTrue(cfg.toolScalingEnabled);
        assertEquals(12, cfg.tierMaxLogs.get("WOODEN"));
        assertEquals(12, cfg.tierMaxLogs.get("GOLDEN"));
        assertEquals(30, cfg.tierMaxLogs.get("STONE"));
        assertEquals(120, cfg.tierMaxLogs.get("IRON"));
        assertEquals(600, cfg.tierMaxLogs.get("DIAMOND"));
        assertEquals(-1, cfg.tierMaxLogs.get("NETHERITE")); // unlimited
    }

    @Test
    void sensibleDefaults() {
        assertEquals(0.8, cfg.logYieldMultiplier);
        assertEquals(2, cfg.xpPerLog);
        assertTrue(!cfg.xpEnabled);
        assertTrue(cfg.maxHits >= cfg.hitsToFell);
        assertTrue(cfg.leaveStump && cfg.leafLoot && cfg.sneakBypass && cfg.crushEnabled);
    }

    @Test
    @Tag("P0")
    void expensiveSettingsHaveUpperBounds() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("detection.max-tree-blocks", 1_000_000);
        yml.set("animation.max-display-entities", 1_000_000);
        yml.set("animation.max-concurrent-fells", 1_000_000);
        yml.set("trunk.despawn-seconds", 1_000_000);

        TimberConfig capped = new TimberConfig(yml);

        assertEquals(TimberConfig.MAX_TREE_BLOCKS_CAP, capped.maxTreeBlocks);
        assertEquals(TimberConfig.MAX_DISPLAY_ENTITIES_CAP, capped.maxDisplayEntities);
        assertEquals(TimberConfig.MAX_CONCURRENT_FELLS_CAP, capped.maxConcurrentFells);
        assertEquals(TimberConfig.MAX_DESPAWN_SECONDS_CAP, capped.despawnSeconds);
    }

    @Test
    void worldWhitelistIsCaseInsensitive() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("worlds", java.util.List.of("world", "resource_nether"));

        TimberConfig whitelist = new TimberConfig(yml);

        assertTrue(whitelist.worldAllowed("WORLD"));
        assertTrue(whitelist.worldAllowed("Resource_Nether"));
        assertTrue(!whitelist.worldAllowed("arena"));
    }

    @Test
    void lowerBoundsProtectTinyOrNegativeExpensiveSettings() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("detection.max-tree-blocks", -1);
        yml.set("animation.max-display-entities", -1);
        yml.set("animation.max-concurrent-fells", -1);
        yml.set("trunk.despawn-seconds", -1);

        TimberConfig bounded = new TimberConfig(yml);

        assertEquals(50, bounded.maxTreeBlocks);
        assertEquals(16, bounded.maxDisplayEntities);
        assertEquals(1, bounded.maxConcurrentFells);
        assertEquals(10, bounded.despawnSeconds);
    }

    @Test
    void customTiersOverrideDefaultsAndUnknownTiersArePreserved() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("tool-scaling.tiers.WOODEN", 3);
        yml.set("tool-scaling.tiers.CUSTOM", 44);

        TimberConfig custom = new TimberConfig(yml);

        assertEquals(3, custom.tierMaxLogs.get("WOODEN"));
        assertEquals(44, custom.tierMaxLogs.get("CUSTOM"));
        assertEquals(-1, custom.tierMaxLogs.get("NETHERITE"));
    }

    @Test
    @Tag("P0")
    void xpBridgeCanBeDisabledInConfig() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("xp.enabled", false);
        yml.set("xp.mode", "none");
        yml.set("xp.command", "");

        TimberConfig disabled = new TimberConfig(yml);

        assertTrue(!disabled.xpEnabled);
        assertEquals("none", disabled.xpMode);
        assertEquals("", disabled.xpCommand);
        assertEquals(0, disabled.xpFor(-1));
    }

    @Test
    void invalidMaterialListsAreIgnoredAndValidExtrasAreKept() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("axes.extra-items", java.util.List.of("NETHERITE_HOE", "NOT_A_REAL_MATERIAL"));
        yml.set("detection.extra-natural", java.util.List.of("COBBLESTONE", "NOPE"));

        TimberConfig parsed = new TimberConfig(yml);

        assertTrue(parsed.axeExtraItems.contains(org.bukkit.Material.NETHERITE_HOE));
        assertEquals(1, parsed.axeExtraItems.size());
        assertTrue(parsed.extraNatural.contains(org.bukkit.Material.COBBLESTONE));
        assertEquals(1, parsed.extraNatural.size());
    }
}
