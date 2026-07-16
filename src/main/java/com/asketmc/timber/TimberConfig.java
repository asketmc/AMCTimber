package com.asketmc.timber;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Collections;
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
    static final int MAX_TREE_BLOCKS_CAP = 5_000;
    static final int MAX_DISPLAY_ENTITIES_CAP = 1_000;
    static final int MAX_CONCURRENT_FELLS_CAP = 32;
    static final int MAX_LIVE_TRUNKS_CAP = 512;
    static final int MAX_TOTAL_ENTITIES_CAP = 50_000;
    static final int MAX_SCAN_READS_CAP = 250_000;
    static final int MAX_DESPAWN_SECONDS_CAP = 3_600;
    static final int MAX_PROTECTION_HOOK_CALLS_CAP = 20_000;
    static final int MAX_ATTEMPT_MICROS_CAP = 10_000;
    static final int MAX_LAUNCH_BLOCK_OPS_CAP = 50_000;
    static final int MAX_ENTITY_OPERATIONS_CAP = 4_096;
    static final int MAX_QUEUED_ENTITY_OPERATIONS_CAP = 65_536;
    static final int MAX_YIELD_DELIVERY_STEPS_CAP = 64;
    static final int MAX_RECOVERY_RECORDS_CAP = PendingYieldFile.MAX_ENTRIES;

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
    final boolean protectionFailClosed;
    final boolean sameSpeciesOnly;
    final boolean wildOnly;             // reject felling a "tree" wired into a player build (cabin/treehouse)
    final int maxHorizontalLogSpan;
    final int maxScanReads;
    final int maxAttemptMicros;
    final int maxProtectionHookCalls;
    final int maxScanAttemptsPerTick;
    final int scanAttemptCooldownTicks;
    final Set<Material> extraNatural;   // operator escape hatch: extra block types to treat as natural

    final int creakTicks;
    final int fallDurationTicks;
    final int bounceTicks;
    final double fallRestDegrees;
    final double overshootDegrees;
    final int maxDisplayEntities;
    final int maxConcurrentFells;
    final int maxLiveTrunks;
    final int maxTotalEntities;
    final float viewRange;

    final int launchBlockOpsPerTick;
    final int entityOperationsPerTick;
    final int maxQueuedEntityOperations;
    final int crushQueriesPerTick;
    final int crushTargetsPerTick;
    final int crushMaxMicrosPerTick;
    final int maxCrushCandidates;
    final int yieldDeliveryStepsPerTick;
    final int maxRecoveryRecords;

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
    final boolean qaCommandsEnabled;

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
        this.tooWeakAction = choice(c.getString("tool-scaling.too-weak-action", "vanilla"), "vanilla", "cancel");
        this.tierMaxLogs = parseTiers(c.getConfigurationSection("tool-scaling.tiers"));

        this.minTreeHeight = clampInt(c.getInt("detection.min-tree-height", 4), 2, 100);
        this.maxTreeBlocks = clampInt(c.getInt("detection.max-tree-blocks", 1500), 50, MAX_TREE_BLOCKS_CAP);
        this.leafAttachRadius = clampInt(c.getInt("detection.leaf-attach-radius", 5), 1, 16);
        this.minNaturalLeaves = clampInt(c.getInt("detection.min-natural-leaves", 8), 0, MAX_TREE_BLOCKS_CAP);
        this.respectBuilds = c.getBoolean("detection.respect-builds", true);
        this.protectionFailClosed = !"allow".equalsIgnoreCase(
                c.getString("detection.protection-error-policy", "deny"));
        this.sameSpeciesOnly = c.getBoolean("detection.same-species", true);
        this.wildOnly = c.getBoolean("detection.wild-only", true);
        this.maxHorizontalLogSpan = clampInt(c.getInt("detection.max-horizontal-log-span", 16), 4, 64);
        this.maxScanReads = clampInt(c.getInt("detection.max-scan-reads", 100_000), 1_000, MAX_SCAN_READS_CAP);
        this.maxAttemptMicros = clampInt(c.getInt("detection.max-attempt-micros", 2_500),
                250, MAX_ATTEMPT_MICROS_CAP);
        this.maxProtectionHookCalls = clampInt(c.getInt("detection.max-protection-hook-calls", 4_096),
                1, MAX_PROTECTION_HOOK_CALLS_CAP);
        this.maxScanAttemptsPerTick = clampInt(c.getInt("detection.max-attempts-per-tick", 4), 1, 64);
        this.scanAttemptCooldownTicks = clampInt(c.getInt("detection.attempt-cooldown-ticks", 4), 0, 100);
        this.extraNatural = parseMaterials(c.getStringList("detection.extra-natural"));

        this.creakTicks = (int) clamp(c.getInt("animation.creak-ticks", 5), 0, 20);
        this.fallDurationTicks = clampInt(c.getInt("animation.fall-duration-ticks", 26), 2, 200);
        this.bounceTicks = clampInt(c.getInt("animation.bounce-ticks", 5), 0, 100);
        this.fallRestDegrees = clamp(c.getDouble("animation.fall-rest-degrees", 88), 60, 90);
        this.overshootDegrees = clamp(c.getDouble("animation.overshoot-degrees", 3), 0, 10);
        this.maxDisplayEntities = clampInt(c.getInt("animation.max-display-entities", 400), 16, MAX_DISPLAY_ENTITIES_CAP);
        this.maxConcurrentFells = clampInt(c.getInt("animation.max-concurrent-fells", 8), 1, MAX_CONCURRENT_FELLS_CAP);
        this.maxLiveTrunks = clampInt(c.getInt("animation.max-live-trunks", 64), 1, MAX_LIVE_TRUNKS_CAP);
        this.maxTotalEntities = clampInt(c.getInt("animation.max-total-entities", 5_000), 64, MAX_TOTAL_ENTITIES_CAP);
        this.viewRange = (float) clamp(c.getDouble("animation.view-range", 1.2), 0.1, 5.0);

        this.launchBlockOpsPerTick = clampInt(c.getInt("runtime-work.launch-block-ops-per-tick", 10_000),
                256, MAX_LAUNCH_BLOCK_OPS_CAP);
        this.entityOperationsPerTick = clampInt(c.getInt("runtime-work.entity-operations-per-tick", 1_024),
                64, MAX_ENTITY_OPERATIONS_CAP);
        this.maxQueuedEntityOperations = clampInt(c.getInt("runtime-work.max-queued-entity-operations", 8_192),
                64, MAX_QUEUED_ENTITY_OPERATIONS_CAP);
        this.crushQueriesPerTick = clampInt(c.getInt("runtime-work.crush-queries-per-tick", 16), 1, 128);
        this.crushTargetsPerTick = clampInt(c.getInt("runtime-work.crush-targets-per-tick", 64), 1, 512);
        this.crushMaxMicrosPerTick = clampInt(c.getInt("runtime-work.crush-max-micros-per-tick", 1_000),
                100, MAX_ATTEMPT_MICROS_CAP);
        this.maxCrushCandidates = clampInt(c.getInt("runtime-work.max-crush-candidates-per-fell", 256),
                16, 1_024);
        this.yieldDeliveryStepsPerTick = clampInt(c.getInt("runtime-work.yield-delivery-steps-per-tick", 8),
                1, MAX_YIELD_DELIVERY_STEPS_CAP);
        this.maxRecoveryRecords = clampInt(c.getInt("runtime-work.max-recovery-records", 4_096),
                64, MAX_RECOVERY_RECORDS_CAP);

        this.leaveStump = c.getBoolean("trunk.leave-stump", true);
        this.hitsToFell = clampInt(c.getInt("trunk.hits-to-fell", 3), 1, 100);
        this.hitsPerLog = clamp(c.getDouble("trunk.hits-per-log", 0.15), 0.0, 2.0);
        this.maxHits = clampInt(c.getInt("trunk.max-hits", 28), 1, 100);
        this.chopCooldownTicks = (int) clamp(c.getInt("trunk.chop-cooldown-ticks", 7), 0, 100);
        this.logYieldMultiplier = clamp(c.getDouble("trunk.log-yield-multiplier", 0.8), 0.0, 4.0);
        this.leafLoot = c.getBoolean("trunk.leaf-loot", true);
        this.despawnSeconds = clampInt(c.getInt("trunk.despawn-seconds", 300), 10, MAX_DESPAWN_SECONDS_CAP);
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

        this.xpEnabled = c.getBoolean("xp.enabled", false);
        this.xpMode = choice(c.getString("xp.mode", "command"), "none", "command");
        this.xpPerLog = clampInt(c.getInt("xp.per-log", 2), 0, 100_000);
        this.xpSkill = c.getString("xp.skill", "foraging");
        this.xpCommand = c.getString("xp.command", "skillsadmin xp %player% %skill% %amount%");

        this.sounds = c.getBoolean("fx.sounds", true);
        this.particles = c.getBoolean("fx.particles", true);
        this.fallingLeaves = c.getBoolean("fx.falling-leaves", true);
        this.qaCommandsEnabled = c.getBoolean("qa.commands-enabled", false);

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

    /** Axe durability charged when a tree of {@code logs} toppled logs is felled. */
    int durabilityForFell(int logs) {
        if (durabilityPerFellLog <= 0 || logs <= 0) return 0;
        return (int) Math.ceil(logs * durabilityPerFellLog);
    }

    /** Crush damage a toppling tree of {@code logs} logs deals on impact — scales with size, capped. Pure. */
    double crushDamageFor(int logs) {
        if (!crushEnabled || logs <= 0) return 0;
        return Math.min(crushMaxDamage, crushBaseDamage + logs * crushPerLogDamage);
    }

    private static Set<Material> parseMaterials(List<String> names) {
        if (names == null || names.isEmpty()) return Collections.emptySet();  // no Material init for the common case
        Set<Material> set = EnumSet.noneOf(Material.class);
        for (String n : names) {
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

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String choice(String value, String fallback, String... allowed) {
        if (value == null) return fallback;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (String candidate : allowed) if (candidate.equals(normalized)) return normalized;
        return fallback;
    }
}
