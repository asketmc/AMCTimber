package com.asketmc.timber;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Flood-fill tree detection. Runs synchronously on the main thread inside the break event (you cannot
 * read block state off-thread on Paper), but only ever <em>reads</em> blocks into {@link TreeShape.Node}
 * snapshots — it never mutates the world. The verdict ({@code isTree}) and geometry live on the pure
 * {@link TreeShape}, so the dangerous "false-positive on a player's log cabin" logic is unit-tested.
 *
 * <p><b>Stump mode</b> (default): the cut happens AT the axed block — only the connected section above
 * the cut topples; everything below (and the axed block itself) stays in the world as a chop-able stump,
 * exactly like Valheim. Cutting near the top of a tall tree prunes it; the remainder still stands.
 * With {@code leave-stump: false} the scan first walks down to the true base and the whole tree topples.
 */
final class TreeScanner {
    private final TimberConfig cfg;

    TreeScanner(TimberConfig cfg) { this.cfg = cfg; }

    static boolean isLog(Material m) { return Tag.LOGS.isTagged(m); }
    static boolean isLeaf(Material m) { return Tag.LEAVES.isTagged(m); }

    /**
     * Wood species key for a log-tagged material: OAK_LOG / STRIPPED_OAK_WOOD → OAK,
     * CRIMSON_STEM → CRIMSON, BAMBOO_BLOCK → BAMBOO. Felling only spreads through ONE species, so a
     * naturally-generated fallen oak log touching a birch trunk can no longer drag the birch down.
     */
    static String speciesOf(Material m) {
        String n = m.name();
        if (n.startsWith("STRIPPED_")) n = n.substring("STRIPPED_".length());
        for (String suffix : new String[]{"_LOG", "_WOOD", "_STEM", "_HYPHAE", "_BLOCK"}) {
            if (n.endsWith(suffix)) return n.substring(0, n.length() - suffix.length());
        }
        return n;
    }

    /** Leaves belonging to a species: <SPECIES>_LEAVES, plus azalea canopies on oak trunks. */
    static boolean leafMatchesSpecies(String species, Material leaf) {
        if (leaf.name().equals(species + "_LEAVES")) return true;
        return "OAK".equals(species)
                && (leaf == Material.AZALEA_LEAVES || leaf == Material.FLOWERING_AZALEA_LEAVES);
    }

    /** True if the leaf block is a natural (vanilla-grown) leaf — placed leaves carry persistent=true. */
    private static boolean naturalLeaf(BlockData d) {
        return d instanceof Leaves l && !l.isPersistent();
    }

    /**
     * Block types that may legitimately border a WILD tree (deny-by-default). Built via matchMaterial so an
     * unknown name on an older/newer server is skipped, not a compile/load break. Anything NOT natural —
     * planks, fences, glass, doors, beds, slabs, stairs, wool, torches, crafting blocks — reads as part of a
     * player build, so a trunk log touching it is never auto-toppled (treehouse / log-cabin guard).
     */
    private static final Set<Material> EXTRA_NATURAL = new HashSet<>();
    static {
        for (String n : new String[]{
            "WATER", "LAVA", "GRAVEL", "CLAY", "GRASS_BLOCK", "MYCELIUM", "DIRT_PATH",
            "MOSS_CARPET", "PALE_MOSS_CARPET", "PALE_HANGING_MOSS",
            "SHORT_GRASS", "GRASS", "TALL_GRASS", "FERN", "LARGE_FERN", "DEAD_BUSH",
            "VINE", "GLOW_LICHEN", "HANGING_ROOTS", "MANGROVE_ROOTS", "MANGROVE_PROPAGULE",
            "BAMBOO", "BAMBOO_SAPLING", "SWEET_BERRY_BUSH", "COCOA", "BEE_NEST",
            "PUMPKIN", "SUGAR_CANE", "LILY_PAD", "CACTUS",
            "BIG_DRIPLEAF", "BIG_DRIPLEAF_STEM", "SMALL_DRIPLEAF", "SPORE_BLOSSOM",
            "CAVE_VINES", "CAVE_VINES_PLANT", "WEEPING_VINES", "WEEPING_VINES_PLANT",
            "TWISTING_VINES", "TWISTING_VINES_PLANT", "SHROOMLIGHT", "NETHER_WART",
            "BROWN_MUSHROOM", "RED_MUSHROOM", "BROWN_MUSHROOM_BLOCK", "RED_MUSHROOM_BLOCK", "MUSHROOM_STEM",
            "SOUL_SAND", "SOUL_SOIL", "AZALEA", "FLOWERING_AZALEA",
            "CALCITE", "DRIPSTONE_BLOCK", "POINTED_DRIPSTONE",
            "SEAGRASS", "TALL_SEAGRASS", "KELP", "KELP_PLANT",
            "CREAKING_HEART", "OPEN_EYEBLOSSOM", "CLOSED_EYEBLOSSOM"
        }) {
            Material m = Material.matchMaterial(n);
            if (m != null) EXTRA_NATURAL.add(m);
        }
    }

    /**
     * True if {@code m} is a block that can naturally surround a wild tree. Pure + unit-tested ({@link
     * SelfTest}) — the dangerous "is this a real tree or a player's log cabin?" call, kept testable.
     */
    static boolean isNaturalNeighbor(Material m) {
        if (m.isAir()) return true;
        return Tag.LOGS.isTagged(m) || Tag.LEAVES.isTagged(m) || Tag.SAPLINGS.isTagged(m)
                || Tag.DIRT.isTagged(m) || Tag.SAND.isTagged(m)
                || Tag.BASE_STONE_OVERWORLD.isTagged(m) || Tag.BASE_STONE_NETHER.isTagged(m)
                || Tag.NYLIUM.isTagged(m) || Tag.WART_BLOCKS.isTagged(m)
                || Tag.SNOW.isTagged(m) || Tag.ICE.isTagged(m)
                || Tag.SMALL_FLOWERS.isTagged(m)
                || EXTRA_NATURAL.contains(m);
    }

    /**
     * Scan from a broken log. {@code dirX/dirZ} come from the player so the tree falls away from them.
     * Returns a {@link TreeShape} whose {@code isTree} flag is the go/no-go for felling.
     */
    TreeShape scan(Block broken, double dirX, double dirZ) {
        World w = broken.getWorld();
        final boolean stump = cfg.leaveStump;
        final String species = speciesOf(broken.getType());
        final boolean sameSpecies = cfg.sameSpeciesOnly;

        // 1) Pick the cut origin. Stump mode cuts AT the axed block; legacy mode walks down to the base.
        Block origin = broken;
        if (!stump) {
            while (true) {
                Block below = origin.getRelative(0, -1, 0);
                if (below.getY() < w.getMinHeight()) break;
                if (!logMatches(below.getType(), species, sameSpecies)) break;
                origin = below;
                if (broken.getY() - origin.getY() > cfg.maxTreeBlocks) break;
            }
        }
        final int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        final Material baseMat = origin.getType();

        // 2) BFS the connected logs over a full 3x3x3 neighbourhood (captures branches + 2x2 trunks),
        //    never walking below the cut level. In stump mode the origin block itself is the seed but is
        //    NOT collected — it stays in the world as the stump top.
        List<TreeShape.Node> logs = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Deque<int[]> q = new ArrayDeque<>();
        q.add(new int[]{ox, oy, oz});
        seen.add(key(ox, oy, oz));
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        while (!q.isEmpty() && logs.size() < cfg.maxTreeBlocks) {
            int[] c = q.poll();
            Block b = w.getBlockAt(c[0], c[1], c[2]);
            if (!logMatches(b.getType(), species, sameSpecies)) continue;
            boolean isOrigin = c[0] == ox && c[1] == oy && c[2] == oz;
            if (!(stump && isOrigin)) {
                logs.add(new TreeShape.Node(c[0], c[1], c[2], b.getBlockData().clone(), true));
                minY = Math.min(minY, c[1]);
                maxY = Math.max(maxY, c[1]);
            }
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        int nx = c[0] + dx, ny = c[1] + dy, nz = c[2] + dz;
                        if (ny < oy) continue;                 // never descend below the cut
                        long k = key(nx, ny, nz);
                        if (seen.contains(k)) continue;
                        if (logMatches(w.getBlockAt(nx, ny, nz).getType(), species, sameSpecies)) {
                            seen.add(k);
                            q.add(new int[]{nx, ny, nz});
                        }
                    }
        }
        if (logs.isEmpty()) {
            return rejected(w, ox, oy, oz, baseMat, dirX, dirZ, stump, "nothing-above-cut");
        }

        // 3) Collect natural leaves attached to the canopy: BFS over leaf blocks adjacent to any collected
        //    log, bounded by leaf-attach-radius and the display budget. A leaf whose vanilla distance
        //    property says it sits closer to SOME log than our BFS reached it belongs to a neighbouring
        //    tree — skip it, so dense forests don't get bald patches around every fell.
        List<TreeShape.Node> leaves = new ArrayList<>();
        int naturalLeafCount = 0;
        int leafBudget = Math.max(0, cfg.maxTreeBlocks - logs.size());
        Set<Long> leafSeen = new HashSet<>();
        Deque<int[]> lq = new ArrayDeque<>();
        for (TreeShape.Node ln : logs) {
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        int nx = ln.x + dx, ny = ln.y + dy, nz = ln.z + dz;
                        long k = key(nx, ny, nz);
                        if (leafSeen.add(k)) lq.add(new int[]{nx, ny, nz, 0});
                    }
        }
        while (!lq.isEmpty() && leaves.size() < leafBudget) {
            int[] c = lq.poll();
            Block b = w.getBlockAt(c[0], c[1], c[2]);
            if (!leafMatches(b.getType(), species, sameSpecies)) continue;
            BlockData d = b.getBlockData();
            int ownDistance = d instanceof Leaves l ? l.getDistance() : 7;
            if (c[3] + 1 > ownDistance) continue;              // a closer trunk owns this leaf
            if (naturalLeaf(d)) naturalLeafCount++;
            leaves.add(new TreeShape.Node(c[0], c[1], c[2], d.clone(), false));
            int dist = c[3];
            if (dist >= cfg.leafAttachRadius) continue;
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        int nx = c[0] + dx, ny = c[1] + dy, nz = c[2] + dz;
                        long k = key(nx, ny, nz);
                        if (leafSeen.add(k) && leafMatches(w.getBlockAt(nx, ny, nz).getType(), species, sameSpecies))
                            lq.add(new int[]{nx, ny, nz, dist + 1});
                    }
        }

        // 4) Pivot = centroid of the lowest collected log level. For a 1x1 trunk in stump mode that is the
        //    block right on top of the stump; for 2x2 trunks it is the footprint centre.
        double sumX = 0, sumZ = 0; int lowLevel = 0;
        for (TreeShape.Node n : logs) {
            if (n.y == minY) { sumX += n.x + 0.5; sumZ += n.z + 0.5; lowLevel++; }
        }
        double pivotX = lowLevel > 0 ? sumX / lowLevel : ox + 0.5;
        double pivotZ = lowLevel > 0 ? sumZ / lowLevel : oz + 0.5;
        double pivotY = minY;

        int height = maxY - minY + 1;

        // 5) Verdict — distinguish a tree from a log build / pillar. The stump block is still part of the
        //    biological tree, so it counts toward the size thresholds even though it doesn't topple.
        int effLogs = logs.size() + (stump ? 1 : 0);
        int effHeight = height + (stump ? 1 : 0);
        String reject = null;
        if (effLogs < cfg.minTreeHeight) reject = "too-few-logs(" + effLogs + ")";
        else if (effHeight < cfg.minTreeHeight) reject = "too-short(" + effHeight + ")";
        else if (naturalLeafCount < cfg.minNaturalLeaves) reject = "no-canopy(" + naturalLeafCount + ")";
        else if (cfg.wildOnly && touchesBuild(w, logs)) reject = "player-build";
        boolean isTree = reject == null;

        return new TreeShape(w, logs, leaves, ox, oy, oz, baseMat,
                pivotX, pivotY, pivotZ, dirX, dirZ, height, stump, isTree, reject);
    }

    /**
     * True if any face-neighbour of a collected trunk log is a non-natural (player-placed) block — i.e. the
     * "tree" is wired into a structure (cabin / treehouse). Reads live blocks; runs only for trees that have
     * already passed the size/canopy checks, so it costs at most ~6 lookups per trunk log on a real fell.
     */
    private boolean touchesBuild(World w, List<TreeShape.Node> logs) {
        for (TreeShape.Node n : logs) {
            for (int[] f : FACES) {
                Material m = w.getBlockAt(n.x + f[0], n.y + f[1], n.z + f[2]).getType();
                if (!isNaturalNeighbor(m) && !cfg.extraNatural.contains(m)) return true;
            }
        }
        return false;
    }

    private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private static boolean logMatches(Material m, String species, boolean sameSpecies) {
        return isLog(m) && (!sameSpecies || species.equals(speciesOf(m)));
    }

    private static boolean leafMatches(Material m, String species, boolean sameSpecies) {
        return isLeaf(m) && (!sameSpecies || leafMatchesSpecies(species, m));
    }

    private static TreeShape rejected(World w, int x, int y, int z, Material mat,
                                      double dirX, double dirZ, boolean stump, String reason) {
        return new TreeShape(w, new ArrayList<>(), new ArrayList<>(), x, y, z, mat,
                x + 0.5, y, z + 0.5, dirX, dirZ, 0, stump, false, reason);
    }

    /** Fall direction (unit, horizontal) pointing away from the player toward/over the trunk. */
    static double[] fallDir(Location player, int bx, int by, int bz) {
        double dx = (bx + 0.5) - player.getX();
        double dz = (bz + 0.5) - player.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0e-4) {
            // player standing in the trunk — fall along their facing instead
            double yaw = Math.toRadians(player.getYaw());
            dx = -Math.sin(yaw); dz = Math.cos(yaw);
            len = Math.sqrt(dx * dx + dz * dz);
        }
        return new double[]{dx / len, dz / len};
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) | (((long) z & 0x3FFFFFF) << 26) | (((long) (y + 2048) & 0xFFF) << 52);
    }
}
