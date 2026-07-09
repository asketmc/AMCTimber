package com.asketmc.timber;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Composes the WorldGuard + Towny soft bridges into one predicate: "may this player fell this whole tree?".
 * The base block's break was already permitted by vanilla (we listen ignoreCancelled), so this only has to
 * catch the edge case of a tree straddling a protection boundary. We check every <em>log</em> (not leaves —
 * leaves are low-value and attached to logs we're allowed to break); if any log is protected we abort the
 * fell entirely (clean all-or-nothing) and the player just breaks the base vanilla-style.
 *
 * <p>Null-safe and fail-open by construction (see the bridges), so {@link #canFell} is also unit-tested.
 */
final class Protection {
    private final WorldGuardBridge wg = new WorldGuardBridge();
    private final TownyBridge towny = new TownyBridge();
    private final boolean respectBuilds;

    Protection(boolean respectBuilds) {
        this.respectBuilds = respectBuilds;
    }

    void init() {
        wg.init();
        towny.init();
    }

    boolean active() { return respectBuilds && (wg.present() || towny.present()); }

    /** Compact one-line hook summary for the enable log, e.g. "WorldGuard+Towny" (empty when none). */
    String hooks() {
        StringBuilder sb = new StringBuilder();
        if (wg.present()) sb.append("WorldGuard");
        if (towny.present()) { if (sb.length() > 0) sb.append("+"); sb.append("Towny"); }
        return sb.toString();
    }

    /** True if the player may break a single block at loc (used by the per-block path + tests). */
    boolean canBreak(Player player, Location loc) {
        return canBreak(player, loc, loc.getBlock().getType());
    }

    /** True if the player may break a block of {@code material} at loc (WorldGuard BUILD + Towny DESTROY). */
    boolean canBreak(Player player, Location loc, Material material) {
        if (!respectBuilds) return true;
        return wg.canBuild(player, loc) && towny.canDestroy(player, loc, material);
    }

    /**
     * True only if the player may break the WHOLE tree — the cut/base block AND every toppling log. All-or-
     * nothing: a tree rooted in (or straddling) a WorldGuard region or Towny claim the player can't build in
     * is never dragged down. "Can't interact here → no fell." Beds and other player structures are caught
     * separately, before this, by the wild-only block guard in {@link TreeScanner}.
     */
    boolean canFell(Player player, TreeShape shape) {
        if (!respectBuilds) return true;
        if (!wg.present() && !towny.present()) return true;
        Location base = new Location(shape.world, shape.cutX, shape.cutY, shape.cutZ);
        if (!canBreak(player, base, shape.baseMaterial)) return false;
        for (TreeShape.Node n : shape.logs) {
            Location loc = new Location(shape.world, n.x, n.y, n.z);
            if (!canBreak(player, loc, n.data.getMaterial())) return false;
        }
        return true;
    }
}
