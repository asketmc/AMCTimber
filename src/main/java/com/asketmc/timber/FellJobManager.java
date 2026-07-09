package com.asketmc.timber;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gatekeeps and launches topples: enforces the global concurrent-fell cap, de-dups two players hitting the
 * same cut, harvests the canopy's vanilla loot (saplings/sticks/apples — keeps forests replantable),
 * removes the tree's blocks from the world (with physics, so vines/cocoa/snow pop naturally), samples the
 * ground under the landing line, and hands off to a {@link FellJob}. A caller whose fell is rejected
 * (cap/dup) simply lets the vanilla break proceed — felling is best-effort.
 */
final class FellJobManager {
    private final TimberPlugin plugin;
    private final Set<Long> activeCuts = ConcurrentHashMap.newKeySet();

    FellJobManager(TimberPlugin plugin) { this.plugin = plugin; }

    int activeCount() { return activeCuts.size(); }

    /**
     * Attempt to fell the scanned tree. Returns true if a topple was started (the caller then cancels the
     * break in stump mode, or suppresses the base drop otherwise). Returns false if capped/duplicate.
     */
    boolean tryFell(Player owner, TreeShape shape) {
        TimberConfig cfg = plugin.cfg();
        long cutKey = key(shape.cutX, shape.cutY, shape.cutZ);
        if (activeCuts.size() >= cfg.maxConcurrentFells) return false;
        if (!activeCuts.add(cutKey)) return false;         // already toppling this cut

        boolean handedOff = false;
        try {
            // Canopy loot rolls against the LIVE leaf blocks, so it must happen before removal.
            List<ItemStack> leafLoot = cfg.leafLoot ? collectLeafLoot(shape) : List.of();

            // Remove every collected block (leaves first so log removal doesn't schedule decay on blocks we
            // are about to clear anyway). Physics on: attached vines/cocoa/snow pop with natural drops.
            World w = shape.world;
            for (TreeShape.Node n : shape.leaves) removeBlock(w, n);
            for (TreeShape.Node n : shape.logs) removeBlock(w, n);

            // How far the rig must drop after rotating, so a mid-trunk cut still lands on the ground.
            double yDrop = groundDrop(shape);

            plugin.fx().crackStart(new Location(w, shape.cutX + 0.5, shape.cutY, shape.cutZ + 0.5));

            ToppleAnimator.Rig rig = ToppleAnimator.spawn(cfg, shape);
            UUID ownerId = owner != null ? owner.getUniqueId() : null;
            new FellJob(plugin, this, shape, rig, ownerId, cutKey, yDrop, leafLoot).start();
            handedOff = true;
            return true;
        } finally {
            if (!handedOff) release(cutKey);
        }
    }

    /** Called by a FellJob when it lands — frees the cut slot. */
    void release(long cutKey) { activeCuts.remove(cutKey); }

    /** Vanilla loot-table drops for every collected leaf (rolled per block, merged into stacks). */
    private static List<ItemStack> collectLeafLoot(TreeShape shape) {
        Map<Material, Integer> counts = new HashMap<>();
        World w = shape.world;
        for (TreeShape.Node n : shape.leaves) {
            Block b = w.getBlockAt(n.x, n.y, n.z);
            if (!TreeScanner.isLeaf(b.getType())) continue;
            for (ItemStack it : b.getDrops()) {
                if (it != null && it.getAmount() > 0) counts.merge(it.getType(), it.getAmount(), Integer::sum);
            }
        }
        List<ItemStack> out = new ArrayList<>();
        for (Map.Entry<Material, Integer> e : counts.entrySet()) {
            int left = e.getValue();
            while (left > 0) {
                int stack = Math.min(64, left);
                out.add(new ItemStack(e.getKey(), stack));
                left -= stack;
            }
        }
        return out;
    }

    /**
     * Vertical distance from the pivot down to the ground under the landing line's midpoint (0 for a
     * normal base cut). Sampled after removal so the tree's own blocks can't catch the ray; neighbouring
     * canopies are skipped so trunks don't come to rest on treetops. Water counts as ground — trunks float.
     */
    private static double groundDrop(TreeShape shape) {
        World w = shape.world;
        int mx = (int) Math.floor(shape.pivotX + shape.dirX * shape.height * 0.5);
        int mz = (int) Math.floor(shape.pivotZ + shape.dirZ * shape.height * 0.5);
        int startY = (int) Math.floor(shape.pivotY) - 1;
        int floor = Math.max(w.getMinHeight(), startY - 48);
        for (int y = startY; y >= floor; y--) {
            Block b = w.getBlockAt(mx, y, mz);
            Material m = b.getType();
            if (b.isLiquid() || (m.isSolid() && !TreeScanner.isLeaf(m))) {
                return Math.max(0, shape.pivotY - (y + 1));
            }
        }
        return 0;
    }

    private static void removeBlock(World w, TreeShape.Node n) {
        Block b = w.getBlockAt(n.x, n.y, n.z);
        Material now = b.getType();
        // only clear if it's still the log/leaf we snapshotted (chunk may have changed)
        if (TreeScanner.isLog(now) || TreeScanner.isLeaf(now)) b.setType(Material.AIR, true);
    }

    static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) | (((long) z & 0x3FFFFFF) << 26) | (((long) (y + 2048) & 0xFFF) << 52);
    }
}
