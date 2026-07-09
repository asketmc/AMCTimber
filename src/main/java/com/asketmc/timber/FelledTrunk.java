package com.asketmc.timber;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A landed, choppable trunk: the rotated log {@link BlockDisplay}s (kept from the topple, lying flat,
 * ordered far-end-first so chopping shortens the trunk toward the base) plus a ROW of {@link Interaction}
 * hitboxes spaced along the whole length, so swinging anywhere on the trunk works. Owner rides a
 * scoreboard tag, never PDC. Yields logs only when fully chopped ("reduced yield, must chop fully");
 * auto-despawns past its deadline, but every chop refreshes the timer so nobody loses a trunk mid-work.
 */
final class FelledTrunk {

    /** One hitbox segment + its distance from the trunk base along the fall direction. */
    static final class Hitbox {
        final Interaction entity;
        final double dist;
        Hitbox(Interaction entity, double dist) { this.entity = entity; this.dist = dist; }
    }

    private final World world;
    private final List<BlockDisplay> displays;   // far end first — shrinks toward the base
    private final List<Hitbox> hitboxes;         // base→far order; index 0 always survives until done
    private final Set<UUID> ids;                 // every hitbox UUID, snapshotted for store (de)registration
    final UUID owner;
    private final Material dropMaterial;
    private final int yieldLogs;
    private final int required;
    private final int initialDisplays;
    private int progress;
    private final double bx, by, bz;             // trunk base at ground level (pivot after the landing drop)
    private final double dirX, dirZ;
    private final double height;
    private volatile long despawnAtMillis;
    private final int despawnSeconds;
    private final Map<UUID, Long> lastChop = new HashMap<>();  // bounded by trunk lifetime — no quit-leak
    private final Map<UUID, Long> lastHint = new HashMap<>();

    FelledTrunk(World world, List<BlockDisplay> displaysFarFirst, List<Hitbox> hitboxes, UUID owner,
                Material dropMaterial, int yieldLogs, int required,
                double bx, double by, double bz, double dirX, double dirZ, double height,
                int despawnSeconds) {
        this.world = world;
        this.displays = displaysFarFirst;
        this.hitboxes = hitboxes;
        this.ids = new HashSet<>();
        for (Hitbox h : hitboxes) ids.add(h.entity.getUniqueId());
        this.owner = owner;
        this.dropMaterial = dropMaterial;
        this.yieldLogs = yieldLogs;
        this.required = Math.max(1, required);
        this.initialDisplays = Math.max(1, displaysFarFirst.size());
        this.bx = bx; this.by = by; this.bz = bz;
        this.dirX = dirX; this.dirZ = dirZ;
        this.height = height;
        this.despawnSeconds = despawnSeconds;
        this.despawnAtMillis = System.currentTimeMillis() + despawnSeconds * 1000L;
    }

    Set<UUID> allIds() { return ids; }

    long despawnAtMillis() { return despawnAtMillis; }

    boolean valid() {
        for (Hitbox h : hitboxes) if (h.entity.isValid()) return true;
        return false;
    }

    /** A non-owner is blocked only when owner-lock is on. */
    boolean blockedFor(Player p, boolean ownerLock) {
        return ownerLock && owner != null && !owner.equals(p.getUniqueId());
    }

    /** Mid-trunk point (for nearest-lookup and final fx). */
    Location center() {
        return new Location(world, bx + dirX * height * 0.5, by + 0.5, bz + dirZ * height * 0.5);
    }

    /** First still-valid hitbox (for the /amctimber test attack|use hooks). */
    Interaction anyHitbox() {
        for (Hitbox h : hitboxes) if (h.entity.isValid()) return h.entity;
        return null;
    }

    /** Anti-spam gate: true once per cooldown window per player (and records the new hit time). */
    boolean chopReady(UUID player, long cooldownMs) {
        long now = System.currentTimeMillis();
        Long last = lastChop.get(player);
        if (last != null && now - last < cooldownMs) return false;
        lastChop.put(player, now);
        return true;
    }

    /** Rate-limits the "need an axe" / "owner locked" action-bar hints. */
    boolean hintReady(UUID player) {
        long now = System.currentTimeMillis();
        Long last = lastHint.get(player);
        if (last != null && now - last < 1500L) return false;
        lastHint.put(player, now);
        return true;
    }

    /**
     * Register chop progress. Plays feedback, proportionally shortens the lying trunk (and trims hitboxes
     * past the new end), refreshes the despawn timer; on completion drops the yield spread along the trunk
     * line, grants XP, toasts the chopper. Returns true when fully chopped (caller drops it from the store).
     */
    boolean chop(Player p, int amount, Fx fx, XpBridge xpBridge, Messages msgs, TimberConfig cfg) {
        progress = Math.min(required, progress + Math.max(1, amount));
        despawnAtMillis = System.currentTimeMillis() + despawnSeconds * 1000L;

        double frac = progress / (double) required;
        fx.chopHit(pointAt(Math.max(0.5, height * (1.0 - frac) * 0.9)), dropMaterial.createBlockData(), frac);
        shrinkTo(keepCount(initialDisplays, required, progress));

        if (progress < required) {
            msgs.progress(p, progress, required);
            return false;
        }

        // Fully chopped → drop the yield spread along where the trunk lay, grant XP, toast.
        int remaining = yieldLogs;
        int k = 0;
        while (remaining > 0) {
            int stack = Math.min(64, remaining);
            double d = Math.min(height - 0.5, 1.0 + k * 2.0);
            world.dropItemNaturally(pointAt(Math.max(0.5, d)), new ItemStack(dropMaterial, stack));
            remaining -= stack;
            k++;
        }
        int xp = cfg.xpFor(yieldLogs);
        xpBridge.grant(p, xp);
        fx.chopBreak(center(), dropMaterial.createBlockData());
        msgs.yield(p, dropMaterial, yieldLogs, xp, xpBridge.present());
        remove();
        return true;
    }

    /** Displays kept after {@code progress} of {@code required} chops — hits exactly 0 on the last hit. */
    static int keepCount(int displays, int required, int progress) {
        if (progress >= required) return 0;
        return (int) Math.ceil(displays * (double) (required - progress) / required);
    }

    /** Hitbox segments for a trunk of the given length: one per ~2.5 blocks, 1..16. */
    static int hitboxCount(double height) {
        return Math.max(1, Math.min(16, (int) Math.ceil(height / 2.5)));
    }

    private Location pointAt(double dist) {
        return new Location(world, bx + dirX * dist, by + 0.6, bz + dirZ * dist);
    }

    private void shrinkTo(int keep) {
        while (displays.size() > keep) {
            BlockDisplay seg = displays.remove(0);             // far end first
            if (seg != null && seg.isValid()) seg.remove();
        }
        double keptLen = height * displays.size() / (double) initialDisplays;
        for (int i = hitboxes.size() - 1; i > 0; i--) {        // never trim the base hitbox
            Hitbox h = hitboxes.get(i);
            if (h.dist > keptLen + 1.0) {
                if (h.entity.isValid()) h.entity.remove();
                hitboxes.remove(i);
            }
        }
    }

    /** Kill every entity backing this trunk (displays + hitboxes). Idempotent. */
    void remove() {
        for (BlockDisplay d : displays) if (d != null && d.isValid()) d.remove();
        displays.clear();
        for (Hitbox h : new ArrayList<>(hitboxes)) if (h.entity.isValid()) h.entity.remove();
        hitboxes.clear();
    }

    int yieldLogs() { return yieldLogs; }
}
