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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Flood-fill tree detection. Runs synchronously on the main thread inside the break event (you cannot
 * read block state off-thread on Paper), but only ever <em>reads</em> blocks into {@link TreeShape.Node}
 * snapshots — it never mutates the world. The verdict ({@code isTree}) and geometry live on the pure
 * {@link TreeShape}, so the dangerous "false-positive on a player's log cabin" logic is unit-tested.
 *
 * <p><b>Stump mode</b> (default): the cut happens AT the axed block — only the connected section above
 * the cut topples; everything below and the detected 1x1/2x2 cut-level footprint stays in the world as a
 * chop-able stump, exactly like Valheim. Cutting near the top of a tall tree prunes it; the remainder
 * still stands.
 * With {@code leave-stump: false} the scan first walks down to the true base and the whole tree topples.
 */
final class TreeScanner {
    private static final int[][] FACE_OFFSETS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    record FootprintCell(int x, int z) {}

    private final TimberConfig cfg;

    TreeScanner(TimberConfig cfg) { this.cfg = cfg; }

    static boolean isLog(Material m) { return Tag.LOGS.isTagged(m); }
    static boolean isLeaf(Material m) { return Tag.LEAVES.isTagged(m); }

    /**
     * Wood species key for a log-tagged material: OAK_LOG / STRIPPED_OAK_WOOD → OAK,
     * CRIMSON_STEM → CRIMSON, BAMBOO_BLOCK → BAMBOO. Felling only spreads through ONE species, so a
     * naturally-generated fallen oak log touching a birch trunk can no longer drag the birch down.
     */
    static String speciesOf(Material m) { return speciesOfName(m.name()); }

    /** Wood species from a material name — the pure (unit-tested) seam behind {@link #speciesOf(Material)}. */
    static String speciesOfName(String name) {
        String n = name;
        if (n.startsWith("STRIPPED_")) n = n.substring("STRIPPED_".length());
        for (String suffix : new String[]{"_LOG", "_WOOD", "_STEM", "_HYPHAE", "_BLOCK"}) {
            if (n.endsWith(suffix)) return n.substring(0, n.length() - suffix.length());
        }
        return n;
    }

    /** Leaves belonging to a species: <SPECIES>_LEAVES, plus azalea canopies on oak trunks. */
    static boolean leafMatchesSpecies(String species, Material leaf) {
        return leafMatchesSpeciesName(species, leaf.name());
    }

    /** Name-based leaf match — the pure (unit-tested) seam behind {@link #leafMatchesSpecies}. */
    static boolean leafMatchesSpeciesName(String species, String leafName) {
        if (leafName.equals(species + "_LEAVES")) return true;
        return "OAK".equals(species)
                && (leafName.equals("AZALEA_LEAVES") || leafName.equals("FLOWERING_AZALEA_LEAVES"));
    }

    /** True if the leaf block is a natural (vanilla-grown) leaf — placed leaves carry persistent=true. */
    static boolean naturalLeaf(BlockData d) {
        return d instanceof Leaves l && !l.isPersistent();
    }

    /**
     * Keep a complete 2x2 trunk footprint when the cut block is one of its corners. A partial square or
     * horizontal log line is not widened into a stump. Unioning every complete square that contains the
     * cut also behaves conservatively for unusual larger trunks without flood-filling branches.
     */
    static Set<FootprintCell> stumpFootprint(
            int cutX, int cutZ, BiPredicate<Integer, Integer> matchingLogAt) {
        Set<FootprintCell> footprint = new LinkedHashSet<>();
        for (int minX = cutX - 1; minX <= cutX; minX++) {
            for (int minZ = cutZ - 1; minZ <= cutZ; minZ++) {
                boolean complete = true;
                for (int dx = 0; dx <= 1 && complete; dx++) {
                    for (int dz = 0; dz <= 1; dz++) {
                        if (!matchingLogAt.test(minX + dx, minZ + dz)) {
                            complete = false;
                            break;
                        }
                    }
                }
                if (!complete) continue;
                for (int dx = 0; dx <= 1; dx++) {
                    for (int dz = 0; dz <= 1; dz++) {
                        footprint.add(new FootprintCell(minX + dx, minZ + dz));
                    }
                }
            }
        }
        if (footprint.isEmpty()) footprint.add(new FootprintCell(cutX, cutZ));
        return footprint;
    }

    /** Safe, stateless tree attachments. Content-bearing block entities such as bee nests are excluded. */
    static boolean isSafeAttachmentName(String name) {
        return switch (name) {
            case "VINE", "COCOA", "MANGROVE_PROPAGULE", "PALE_HANGING_MOSS",
                    "SNOW", "MOSS_CARPET", "PALE_MOSS_CARPET" -> true;
            default -> false;
        };
    }

    /** Whether an attachment at the given face offset is owned by a snapshotted log/stump or leaf. */
    static boolean attachmentBelongs(String name, boolean sourceLog, int dx, int dy, int dz) {
        if (!isSafeAttachmentName(name)) return false;
        return switch (name) {
            case "VINE" -> true;
            case "COCOA" -> sourceLog && dy == 0;
            case "MANGROVE_PROPAGULE", "PALE_HANGING_MOSS" ->
                    !sourceLog && dx == 0 && dy == -1 && dz == 0;
            case "SNOW", "MOSS_CARPET", "PALE_MOSS_CARPET" ->
                    dx == 0 && dy == 1 && dz == 0;
            default -> false;
        };
    }

    static boolean isVerticalAttachmentName(String name) {
        return name.equals("VINE") || name.equals("PALE_HANGING_MOSS");
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
        if (m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR) return true;
        if (isKnownPlayerBuildMaterialName(m.name())) return false;
        return Tag.LOGS.isTagged(m) || Tag.LEAVES.isTagged(m) || Tag.SAPLINGS.isTagged(m)
                || Tag.DIRT.isTagged(m) || Tag.SAND.isTagged(m)
                || Tag.BASE_STONE_OVERWORLD.isTagged(m) || Tag.BASE_STONE_NETHER.isTagged(m)
                || Tag.NYLIUM.isTagged(m) || Tag.WART_BLOCKS.isTagged(m)
                || Tag.SNOW.isTagged(m) || Tag.ICE.isTagged(m)
                || Tag.SMALL_FLOWERS.isTagged(m)
                || EXTRA_NATURAL.contains(m);
    }

    static boolean isKnownPlayerBuildMaterialName(String name) {
        return name.endsWith("_PLANKS")
                || name.endsWith("_BED")
                || name.endsWith("_DOOR")
                || name.endsWith("_FENCE")
                || name.endsWith("_FENCE_GATE")
                || name.endsWith("_STAIRS")
                || name.endsWith("_SLAB")
                || name.endsWith("_WOOL")
                || name.endsWith("_CARPET")
                || name.equals("GLASS")
                || name.endsWith("_STAINED_GLASS")
                || name.equals("TORCH")
                || name.endsWith("_TORCH")
                || name.equals("CRAFTING_TABLE")
                || name.equals("CHEST")
                || name.endsWith("_CHEST")
                || name.equals("FURNACE");
    }

    /**
     * Scan from a broken log. {@code dirX/dirZ} come from the player so the tree falls away from them.
     * Returns a {@link TreeShape} whose {@code isTree} flag is the go/no-go for felling.
     */
    TreeShape scan(Block broken, double dirX, double dirZ) {
        return scan(broken, dirX, dirZ, FellAttemptBudget.from(cfg));
    }

    TreeShape scan(Block broken, double dirX, double dirZ, FellAttemptBudget budget) {
        World w = broken.getWorld();
        ScanContext scan = new ScanContext(w, budget);
        final boolean stump = cfg.leaveStump;
        final String species = speciesOf(broken.getType());
        final boolean sameSpecies = cfg.sameSpeciesOnly;

        // 1) Pick the cut origin. Stump mode cuts AT the axed block; legacy mode walks down to the base.
        int ox = broken.getX(), oy = broken.getY(), oz = broken.getZ();
        if (!stump) {
            while (true) {
                if (!budget.checkpoint()) break;
                int belowY = oy - 1;
                if (belowY < w.getMinHeight()) break;
                Material below = scan.material(ox, belowY, oz);
                if (scan.blocked() || !logMatches(below, species, sameSpecies)) break;
                oy = belowY;
                if (broken.getY() - oy > cfg.maxTreeBlocks) break;
            }
        }
        if (scan.blocked()) return rejected(w, ox, oy, oz, broken.getType(), dirX, dirZ, stump, scan.reason());
        final BlockData baseData = scan.data(ox, oy, oz);
        if (baseData == null) return rejected(w, ox, oy, oz, broken.getType(), dirX, dirZ, stump, scan.reason());
        final Material baseMat = baseData.getMaterial();

        // Preserve the whole natural giant-trunk footprint, not only the one corner the player clicked.
        List<TreeShape.Node> stumps = new ArrayList<>();
        Set<Long> stumpKeys = new HashSet<>();
        if (stump) {
            final int stumpY = oy;
            Set<FootprintCell> footprint = stumpFootprint(ox, oz,
                    (x, z) -> logMatches(scan.material(x, stumpY, z), species, sameSpecies));
            if (scan.blocked()) {
                return rejected(w, ox, oy, oz, baseMat, dirX, dirZ, true, scan.reason());
            }
            for (FootprintCell cell : footprint) {
                BlockData data = scan.data(cell.x(), oy, cell.z());
                if (data == null) {
                    return rejected(w, ox, oy, oz, baseMat, dirX, dirZ, true, scan.reason());
                }
                stumps.add(new TreeShape.Node(cell.x(), oy, cell.z(), data.clone(), true));
                stumpKeys.add(key(cell.x(), oy, cell.z()));
            }
        }

        // 2) BFS the connected logs over a full 3x3x3 neighbourhood (captures branches + 2x2 trunks),
        //    never walking below the cut level. Kept footprint blocks remain BFS bridges into every trunk
        //    column but are not collected into the toppling section.
        List<TreeShape.Node> logs = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Deque<int[]> q = new ArrayDeque<>();
        q.add(new int[]{ox, oy, oz});
        seen.add(key(ox, oy, oz));
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        boolean logLimitHit = false;
        while (!q.isEmpty()) {
            if (!budget.checkpoint()) break;
            if (logs.size() >= cfg.maxTreeBlocks) {
                logLimitHit = true;
                break;
            }
            int[] c = q.poll();
            BlockData data = scan.data(c[0], c[1], c[2]);
            if (data == null || !logMatches(data.getMaterial(), species, sameSpecies)) continue;
            if (!stumpKeys.contains(key(c[0], c[1], c[2]))) {
                logs.add(new TreeShape.Node(c[0], c[1], c[2], data.clone(), true));
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
                        if (logMatches(scan.material(nx, ny, nz), species, sameSpecies)) {
                            seen.add(k);
                            q.add(new int[]{nx, ny, nz});
                        }
                    }
        }
        if (scan.blocked()) return rejected(w, ox, oy, oz, baseMat, dirX, dirZ, stump, scan.reason());
        if (logLimitHit) return rejected(w, ox, oy, oz, baseMat, dirX, dirZ, stump, "too-large");
        if (logs.isEmpty()) {
            return rejected(w, ox, oy, oz, baseMat, dirX, dirZ, stump, "nothing-above-cut");
        }

        // 3) Collect natural leaves attached to the canopy: BFS over leaf blocks adjacent to any collected
        //    log, bounded by leaf-attach-radius and the display budget. A leaf whose vanilla distance
        //    property says it sits closer to SOME log than our BFS reached it belongs to a neighbouring
        //    tree — skip it, so dense forests don't get bald patches around every fell.
        List<TreeShape.Node> leaves = new ArrayList<>();
        int naturalLeafCount = 0;
        boolean persistentLeafSeen = false;
        int leafBudget = Math.max(0, cfg.maxTreeBlocks - logs.size());
        Set<Long> leafSeen = new HashSet<>();
        Deque<int[]> lq = new ArrayDeque<>();
        for (TreeShape.Node ln : logs) {
            if (!budget.checkpoint()) break;
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        int nx = ln.x + dx, ny = ln.y + dy, nz = ln.z + dz;
                        long k = key(nx, ny, nz);
                        if (leafSeen.add(k)) lq.add(new int[]{nx, ny, nz, 0});
                    }
        }
        while (!lq.isEmpty() && leaves.size() < leafBudget) {
            if (!budget.checkpoint()) break;
            int[] c = lq.poll();
            BlockData d = scan.data(c[0], c[1], c[2]);
            if (d == null || !leafMatches(d.getMaterial(), species, sameSpecies)) continue;
            int ownDistance = d instanceof Leaves l ? l.getDistance() : 7;
            if (c[3] + 1 > ownDistance) continue;              // a closer trunk owns this leaf
            if (!naturalLeaf(d)) {
                persistentLeafSeen = true;
                continue;
            }
            naturalLeafCount++;
            leaves.add(new TreeShape.Node(c[0], c[1], c[2], d.clone(), false));
            int dist = c[3];
            if (dist >= cfg.leafAttachRadius) continue;
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        int nx = c[0] + dx, ny = c[1] + dy, nz = c[2] + dz;
                        long k = key(nx, ny, nz);
                        if (leafSeen.add(k) && leafMatches(scan.material(nx, ny, nz), species, sameSpecies))
                            lq.add(new int[]{nx, ny, nz, dist + 1});
                    }
        }
        if (scan.blocked()) return rejected(w, ox, oy, oz, baseMat, dirX, dirZ, stump, scan.reason());

        // 4) Collect only stateless attachments that are confidently supported by this tree. Direct
        //    sources are face-neighbours; vines and pale hanging moss then extend only vertically in the
        //    same column. This removes long jungle curtains without spreading sideways into another tree.
        int attachmentBudget = Math.max(0, cfg.maxTreeBlocks - logs.size() - leaves.size());
        AttachmentScan attachmentScan = collectAttachments(scan, logs, stumps, leaves, attachmentBudget);
        if (scan.blocked()) return rejected(w, ox, oy, oz, baseMat, dirX, dirZ, stump, scan.reason());
        if (attachmentScan.limitHit()) {
            return rejected(w, ox, oy, oz, baseMat, dirX, dirZ, stump, "too-many-attachments");
        }
        List<TreeShape.Node> attachments = attachmentScan.nodes();

        // 5) Pivot = centroid of the lowest collected log level. For a 1x1 trunk in stump mode that is the
        //    block right on top of the stump; for 2x2 trunks it is the footprint centre.
        double sumX = 0, sumZ = 0; int lowLevel = 0;
        for (TreeShape.Node n : logs) {
            if (n.y == minY) { sumX += n.x + 0.5; sumZ += n.z + 0.5; lowLevel++; }
        }
        double pivotX = lowLevel > 0 ? sumX / lowLevel : ox + 0.5;
        double pivotZ = lowLevel > 0 ? sumZ / lowLevel : oz + 0.5;
        double pivotY = minY;

        int height = maxY - minY + 1;

        // 6) Verdict — distinguish a tree from a log build / pillar. Every stump-footprint block is still
        //    part of the biological tree, so it counts toward size thresholds even though it doesn't topple.
        int effLogs = logs.size() + stumps.size();
        int effHeight = height + (stump ? 1 : 0);
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (TreeShape.Node node : logs) {
            minX = Math.min(minX, node.x); maxX = Math.max(maxX, node.x);
            minZ = Math.min(minZ, node.z); maxZ = Math.max(maxZ, node.z);
        }
        int horizontalSpan = Math.max(maxX - minX + 1, maxZ - minZ + 1);
        String reject = null;
        if (effLogs < cfg.minTreeHeight) reject = "too-few-logs(" + effLogs + ")";
        else if (effHeight < cfg.minTreeHeight) reject = "too-short(" + effHeight + ")";
        else if (naturalLeafCount < cfg.minNaturalLeaves) reject = "no-canopy(" + naturalLeafCount + ")";
        else if (cfg.wildOnly && persistentLeafSeen) reject = "player-placed-leaves";
        else if (cfg.wildOnly && horizontalSpan > cfg.maxHorizontalLogSpan) reject = "suspicious-log-span";
        else if (cfg.wildOnly && touchesBuild(logs, stumps, scan)) reject = "player-build";
        if (scan.blocked()) reject = scan.reason();
        boolean isTree = reject == null;

        return new TreeShape(w, logs, stumps, leaves, attachments, ox, oy, oz, baseMat,
                pivotX, pivotY, pivotZ, dirX, dirZ, height, stump, isTree, reject);
    }

    private record AttachmentScan(List<TreeShape.Node> nodes, boolean limitHit) {}

    private static AttachmentScan collectAttachments(
            ScanContext scan, List<TreeShape.Node> logs, List<TreeShape.Node> stumps,
            List<TreeShape.Node> leaves, int budget) {
        List<TreeShape.Node> nodes = new ArrayList<>();
        Set<Long> added = new HashSet<>();
        List<TreeShape.Node> sources = new ArrayList<>(logs.size() + stumps.size() + leaves.size());
        sources.addAll(logs);
        sources.addAll(stumps);
        sources.addAll(leaves);

        for (TreeShape.Node source : sources) {
            if (!scan.checkpoint()) break;
            for (int[] face : FACE_OFFSETS) {
                int x = source.x + face[0], y = source.y + face[1], z = source.z + face[2];
                long blockKey = key(x, y, z);
                if (added.contains(blockKey)) continue;
                BlockData data = scan.data(x, y, z);
                if (data == null) return new AttachmentScan(nodes, false);
                String name = data.getMaterial().name();
                if (!attachmentBelongs(name, source.log, face[0], face[1], face[2])) continue;
                if (nodes.size() >= budget) return new AttachmentScan(nodes, true);
                nodes.add(new TreeShape.Node(x, y, z, data.clone(), false));
                added.add(blockKey);
            }
        }

        int directCount = nodes.size();
        for (int index = 0; index < directCount; index++) {
            if (!scan.checkpoint()) break;
            TreeShape.Node seed = nodes.get(index);
            String name = seed.data.getMaterial().name();
            if (!isVerticalAttachmentName(name)) continue;
            for (int direction : new int[]{-1, 1}) {
                int y = seed.y + direction;
                while (true) {
                    long blockKey = key(seed.x, y, seed.z);
                    if (added.contains(blockKey)) break;
                    BlockData data = scan.data(seed.x, y, seed.z);
                    if (data == null) return new AttachmentScan(nodes, false);
                    if (!data.getMaterial().name().equals(name)) break;
                    if (nodes.size() >= budget) return new AttachmentScan(nodes, true);
                    nodes.add(new TreeShape.Node(seed.x, y, seed.z, data.clone(), false));
                    added.add(blockKey);
                    y += direction;
                }
            }
        }
        return new AttachmentScan(nodes, false);
    }

    /**
     * True if any face-neighbour of a collected trunk log is a non-natural (player-placed) block — i.e. the
     * "tree" is wired into a structure (cabin / treehouse). Reads cached snapshots; runs only for trees that
     * already passed the size/canopy checks, and remains inside the configured global scan-read budget.
     */
    private boolean touchesBuild(
            List<TreeShape.Node> logs, List<TreeShape.Node> stumps, ScanContext scan) {
        List<TreeShape.Node> trunk = new ArrayList<>(logs.size() + stumps.size());
        trunk.addAll(logs);
        trunk.addAll(stumps);
        for (TreeShape.Node n : trunk) {
            if (!scan.checkpoint()) return true;
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        Material material = scan.material(n.x + dx, n.y + dy, n.z + dz);
                        if (!isNaturalNeighbor(material) && !cfg.extraNatural.contains(material)) return true;
                    }
        }
        return false;
    }

    private static boolean logMatches(Material m, String species, boolean sameSpecies) {
        return isLog(m) && (!sameSpecies || species.equals(speciesOf(m)));
    }

    private static boolean leafMatches(Material m, String species, boolean sameSpecies) {
        return isLeaf(m) && (!sameSpecies || leafMatchesSpecies(species, m));
    }

    private static TreeShape rejected(World w, int x, int y, int z, Material mat,
                                      double dirX, double dirZ, boolean stump, String reason) {
        return new TreeShape(w, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>(), x, y, z, mat,
                x + 0.5, y, z + 0.5, dirX, dirZ, 0, stump, false, reason);
    }

    /** Fall direction (unit, horizontal) pointing away from the player toward/over the trunk. */
    static double[] fallDir(Location player, int bx, int bz) {
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

    /** Cached, bounded access to already-loaded chunks only. */
    private static final class ScanContext {
        private final World world;
        private final FellAttemptBudget budget;
        private final Map<Long, BlockData> cache = new HashMap<>();
        private final BlockData air = Material.AIR.createBlockData();
        private String blockedReason;

        private ScanContext(World world, FellAttemptBudget budget) {
            this.world = world;
            this.budget = budget;
        }

        private boolean checkpoint() {
            if (blockedReason != null) return false;
            if (budget.checkpoint()) return true;
            blockedReason = budget.reason();
            return false;
        }

        private Material material(int x, int y, int z) {
            BlockData data = data(x, y, z);
            return data == null ? Material.AIR : data.getMaterial();
        }

        private BlockData data(int x, int y, int z) {
            if (!checkpoint()) return null;
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) return air;
            long key = key(x, y, z);
            BlockData cached = cache.get(key);
            if (cached != null) return cached;
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                blockedReason = "unloaded-chunk-boundary";
                return null;
            }
            if (!budget.takeRead()) {
                blockedReason = budget.reason();
                return null;
            }
            BlockData loaded = world.getBlockAt(x, y, z).getBlockData();
            if (!checkpoint()) return null;
            cache.put(key, loaded);
            return loaded;
        }

        private boolean blocked() {
            return blockedReason != null;
        }

        private String reason() {
            return blockedReason == null ? "scan-failed" : blockedReason;
        }
    }
}
