package com.asketmc.timber;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Immutable typed snapshot of config.yml. Rebuilt on enable and on {@code /amctimber reload} so the rest
 * of the plugin never touches the live FileConfiguration (and so values are validated/clamped once).
 * The pure maths helpers (yield, hits, durability, crush) live here and are covered by {@link SelfTest}.
 */
final class TimberConfig {
    final String debug;                 // off | info | full

    final boolean enabled;
    final List<String> worlds;          // empty = all worlds
    final boolean requireAxe;
    final boolean allowCreative;
    final boolean sneakBypass;          // sneaking + breaking = vanilla single-block break (builder bypass)

    // what counts as an axe
    final boolean axeUseVanillaTag;
    final Set<Material> axeExtraItems;

    // tool tier → max fellable tree size
    final boolean toolScalingEnabled;
    final int toolScalingDefaultMax;    // for axes matching no tier (custom tools); -1 = unlimited
    final String tooWeakAction;         // vanilla | cancel
    final Map<String, Integer> tierMaxLogs;

    final int minTreeHeight;
    final int maxTreeBlocks;
    final int leafAttachRadius;
    final int minNaturalLeaves;
    final boolean respectBuilds;
    final boolean sameSpeciesOnly;
    final boolean wildOnly;             // reject felling a "tree" wired into a player build (cabin/treehouse)
    final Set<Material> extraNatural;   // operator escape hatch: extra block types to treat as natural

    final int creakTicks;
    final int fallDurationTicks;
    final int bounceTicks;
    final double fallRestDegrees;
    final double overshootDegrees;
    final int maxDisplayEntities;
    final int maxConcurrentFells;
    final float viewRange;

    final boolean leaveStump;
    final int hitsToFell;
    final double hitsPerLog;
    final int maxHits;
    final int chopCooldownTicks;
    final double logYieldMultiplier;
    final boolean leafLoot;
    final int despawnSeconds;
    final boolean ownerLock;

    final double durabilityPerFellLog;
    final int durabilityPerChopHit;

    final boolean crushEnabled;
    final double crushBaseDamage;
    final double crushPerLogDamage;
    final double crushMaxDamage;
    final double crushRadius;
    final boolean crushHitPlayers;
    final boolean crushHitMobs;
    final boolean crushPvp;

    // pluggable skill XP
    final boolean xpEnabled;
    final String xpMode;                // command | none
    final int xpPerLog;
    final String xpSkill;
    final String xpCommand;

    final boolean sounds;
    final boolean particles;
    final boolean fallingLeaves;

    final boolean metricsEnabled;

    TimberConfig(FileConfiguration c) {
        this.debug = c.getString("debug", "info");

        this.enabled = c.getBoolean("enabled", true);
        this.worlds = c.getStringList("worlds");
        this.requireAxe = c.getBoolean("require-axe", true);
        this.allowCreative = c.getBoolean("allow-creative", false);
        this.sneakBypass = c.getBoolean("bypass.sneak", true);

        this.axeUseVanillaTag = c.getBoolean("axes.use-vanilla-tag", true);
        this.axeExtraItems = parseMaterials(c.getStringList("axes.extra-items"));

        this.toolScalingEnabled = c.getBoolean("tool-scaling.enabled", true);
        this.toolScalingDefaultMax = c.getInt("tool-scaling.default-max", -1);
        this.tooWeakAction = c.getString("tool-scaling.too-weak-action", "vanilla");
        this.tierMaxLogs = parseTiers(c.getConfigurationSection("tool-scaling.tiers"));

        this.minTreeHeight = Math.max(2, c.getInt("detection.min-tree-height", 4));
        this.maxTreeBlocks = Math.max(50, c.getInt("detection.max-tree-blocks", 1500));
        this.leafAttachRadius = Math.max(1, c.getInt("detection.leaf-attach-radius", 5));
        this.minNaturalLeaves = Math.max(0, c.getInt("detection.min-natural-leaves", 8));
        this.respectBuilds = c.getBoolean("detection.respect-builds", true);
        this.sameSpeciesOnly = c.getBoolean("detection.same-species", true);
        this.wildOnly = c.getBoolean("detection.wild-only", true);
        this.extraNatural = parseMaterials(c.getStringList("detection.extra-natural"));

        this.creakTicks = (int) clamp(c.getInt("animation.creak-ticks", 5), 0, 20);
        this.fallDurationTicks = Math.max(2, c.getInt("animation.fall-duration-ticks", 26));
        this.bounceTicks = Math.max(0, c.getInt("animation.bounce-ticks", 5));
        this.fallRestDegrees = clamp(c.getDouble("animation.fall-rest-degrees", 88), 60, 90);
        this.overshootDegrees = clamp(c.getDouble("animation.overshoot-degrees", 3), 0, 10);
        this.maxDisplayEntities = Math.max(16, c.getInt("animation.max-display-entities", 400));
        this.maxConcurrentFells = Math.max(1, c.getInt("animation.max-concurrent-fells", 8));
        this.viewRange = (float) clamp(c.getDouble("animation.view-range", 1.2), 0.1, 5.0);

        this.leaveStump = c.getBoolean("trunk.leave-stump", true);
        this.hitsToFell = Math.max(1, c.getInt("trunk.hits-to-fell", 3));
        this.hitsPerLog = clamp(c.getDouble("trunk.hits-per-log", 0.15), 0.0, 2.0);
        this.maxHits = Math.max(1, c.getInt("trunk.max-hits", 28));
        this.chopCooldownTicks = (int) clamp(c.getInt("trunk.chop-cooldown-ticks", 7), 0, 100);
        this.logYieldMultiplier = clamp(c.getDouble("trunk.log-yield-multiplier", 0.8), 0.0, 4.0);
        this.leafLoot = c.getBoolean("trunk.leaf-loot", true);
        this.despawnSeconds = Math.max(10, c.getInt("trunk.despawn-seconds", 300));
        this.ownerLock = c.getBoolean("trunk.owner-lock", false);

        this.durabilityPerFellLog = clamp(c.getDouble("durability.per-fell-log", 0.25), 0.0, 10.0);
        this.durabilityPerChopHit = (int) clamp(c.getInt("durability.per-chop-hit", 1), 0, 100);

        this.crushEnabled = c.getBoolean("crush.enabled", true);
        this.crushBaseDamage = clamp(c.getDouble("crush.base-damage", 6.0), 0.0, 1000.0);
        this.crushPerLogDamage = clamp(c.getDouble("crush.per-log-damage", 0.4), 0.0, 100.0);
        this.crushMaxDamage = clamp(c.getDouble("crush.max-damage", 20.0), 0.0, 1000.0);
        this.crushRadius = clamp(c.getDouble("crush.radius", 1.6), 0.5, 8.0);
        this.crushHitPlayers = c.getBoolean("crush.hit-players", true);
        this.crushHitMobs = c.getBoolean("crush.hit-mobs", true);
        this.crushPvp = c.getBoolean("crush.pvp", false);

        this.xpEnabled = c.getBoolean("xp.enabled", true);
        this.xpMode = c.getString("xp.mode", "command");
        this.xpPerLog = Math.max(0, c.getInt("xp.per-log", 2));
        this.xpSkill = c.getString("xp.skill", "foraging");
        this.xpCommand = c.getString("xp.command", "skillsadmin xp %player% %skill% %amount%");

        this.sounds = c.getBoolean("fx.sounds", true);
        this.particles = c.getBoolean("fx.particles", true);
        this.fallingLeaves = c.getBoolean("fx.falling-leaves", true);

        this.metricsEnabled = c.getBoolean("metrics", true);
    }

    /** True if felling is allowed in the given world name. */
    boolean worldAllowed(String world) {
        if (worlds == null || worlds.isEmpty()) return true;
        for (String w : worlds) if (w.equalsIgnoreCase(world)) return true;
        return false;
    }

    /** logs dropped from a felled trunk of {@code originalLogs} toppled logs. Pure — unit-tested by SelfTest. */
    int logsYielded(int originalLogs) {
        return (int) Math.round(Math.max(0, originalLogs) * logYieldMultiplier);
    }

    int xpFor(int logsYielded) {
        return Math.max(0, logsYielded) * xpPerLog;
    }

    /** Axe hits to fully chop a downed trunk — scales with tree size so giants feel like giants. */
    int hitsRequiredFor(int logs) {
        int h = (int) Math.round(hitsToFell + Math.max(0, logs) * hitsPerLog);
        return Math.max(1, Math.min(maxHits, h));
    }

    /** Chop progress contributed by one hit: Efficiency speeds the work up. */
    static int progressPerHit(int efficiencyLevel) {
        return 1 + Math.max(0, efficiencyLevel) / 2;
    }

    /**
     * Axe durability charged when a tree of {@code logs} toppled logs is felled. In stump mode the break
     * event is cancelled (no vanilla charge), so the full amount applies; otherwise vanilla already
     * charged 1 for the base block.
     */
    int durabilityForFell(int logs) {
        if (durabilityPerFellLog <= 0 || logs <= 0) return 0;
        int total = (int) Math.ceil(logs * durabilityPerFellLog);
        return Math.max(0, leaveStump ? total : total - 1);
    }

    /** Crush damage a toppling tree of {@code logs} logs deals on impact — scales with size, capped. Pure. */
    double crushDamageFor(int logs) {
        if (!crushEnabled || logs <= 0) return 0;
        return Math.min(crushMaxDamage, crushBaseDamage + logs * crushPerLogDamage);
    }

    private static Set<Material> parseMaterials(List<String> names) {
        Set<Material> set = EnumSet.noneOf(Material.class);
        if (names != null) for (String n : names) {
            Material m = Material.matchMaterial(n);
            if (m != null) set.add(m);
        }
        return set;
    }

    /** Per-tier max fellable size, defaults filled so a partial config still behaves; -1 = unlimited. */
    private static Map<String, Integer> parseTiers(ConfigurationSection sec) {
        Map<String, Integer> m = new HashMap<>();
        m.put("WOODEN", 12);
        m.put("GOLDEN", 12);
        m.put("STONE", 30);
        m.put("IRON", 120);
        m.put("DIAMOND", 600);
        m.put("NETHERITE", -1);
        if (sec != null) {
            for (String k : sec.getKeys(false)) m.put(k.toUpperCase(Locale.ROOT), sec.getInt(k, -1));
        }
        return m;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
