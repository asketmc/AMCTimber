package com.asketmc.timber;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

/**
 * What counts as an axe, and how strong it is.
 *
 * <p><b>Axe detection</b> is configurable ({@code axes.use-vanilla-tag} + {@code axes.extra-items}) so a
 * server can let a custom/modded tool fell trees too.
 *
 * <p><b>Tier scaling</b> ({@code tool-scaling}) caps how big a tree each axe material may fell, keyed by
 * the vanilla tier prefix (WOODEN / STONE / GOLDEN / IRON / DIAMOND / NETHERITE). A wooden axe drops
 * saplings; only a diamond/netherite axe brings down a 2×2 jungle giant. Items that match no tier (custom
 * tools) fall back to {@code tool-scaling.default-max}.
 */
final class Tools {
    private Tools() {}

    /** Vanilla material tiers, strongest last. The prefix of e.g. DIAMOND_AXE / GOLDEN_AXE. */
    static final String[] TIERS = {"WOODEN", "STONE", "GOLDEN", "IRON", "DIAMOND", "NETHERITE"};

    /** True if {@code item} counts as an axe under the current config. */
    static boolean isAxe(ItemStack item, TimberConfig cfg) {
        if (item == null) return false;
        Material m = item.getType();
        if (m.isAir()) return false;
        if (cfg.axeUseVanillaTag && Tag.ITEMS_AXES.isTagged(m)) return true;
        return cfg.axeExtraItems.contains(m);
    }

    /** Vanilla tier prefix for a material (WOODEN_AXE → "WOODEN"); "" if it matches none (custom tool). */
    static String tierOf(Material m) { return tierOfName(m.name()); }

    /** Tier prefix from a material name — the pure (unit-tested) seam behind {@link #tierOf(Material)}. */
    static String tierOfName(String materialName) {
        String n = materialName.toUpperCase(Locale.ROOT);
        for (String t : TIERS) {
            if (n.startsWith(t + "_")) return t;
        }
        return "";
    }

    /**
     * Max tree size (in logs) this item is allowed to fell, per {@code tool-scaling}. Returns a negative
     * number for "unlimited" (scaling disabled, or the tier/default is configured to -1).
     */
    static int maxLogsFor(ItemStack item, TimberConfig cfg) {
        if (!cfg.toolScalingEnabled || item == null) return -1;
        Integer max = cfg.tierMaxLogs.get(tierOf(item.getType()));
        if (max == null) max = cfg.toolScalingDefaultMax;
        return max;
    }

    /** True if {@code item} is too weak to fell a tree of {@code logs} logs. */
    static boolean tooWeakFor(ItemStack item, int logs, TimberConfig cfg) {
        return tooWeak(maxLogsFor(item, cfg), logs);
    }

    /** Pure gate (unit-tested): too weak iff a finite cap is set and the tree exceeds it. */
    static boolean tooWeak(int maxLogs, int logs) {
        return maxLogs >= 0 && logs > maxLogs;
    }
}
