package com.asketmc.timber;

import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;

/**
 * Console-safe pure-logic self-check ({@code /amctimber selftest}). Exercises the env-independent maths —
 * yield/XP, hits scaling, durability, crush, tool-tier caps, display decimation + trunk shrink, hitbox
 * layout, the rotation axis + transform (incl. the ground-drop), leaf tints, message glyphs — and prints
 * a single {@code PASS n/n} / {@code FAIL ...} line. No world mutation, no entities; safe to run any time.
 */
final class SelfTest {
    private SelfTest() {}

    static String run(CommandSender out) {
        List<String> fails = new ArrayList<>();
        int[] tally = {0, 0};                                  // pass, fail

        TimberConfig cfg = new TimberConfig(new YamlConfiguration()); // all defaults

        // --- yield maths: reduced (0.8), must-chop-fully, monotonic, non-negative ---
        check(tally, fails, "yield(20)==16", cfg.logsYielded(20) == 16);
        check(tally, fails, "yield(20) formula", cfg.logsYielded(20) == (int) Math.round(20 * 0.8));
        check(tally, fails, "yield monotonic", cfg.logsYielded(10) <= cfg.logsYielded(20));
        check(tally, fails, "yield non-negative", cfg.logsYielded(0) == 0 && cfg.logsYielded(-5) == 0);

        // --- xp maths ---
        check(tally, fails, "xp(16)", cfg.xpFor(16) == 16 * cfg.xpPerLog);
        check(tally, fails, "xp(0)==0", cfg.xpFor(0) == 0);

        // --- hits scale with tree size (base 3 + 0.15/log, 1..28) ---
        check(tally, fails, "hits(6)==4", cfg.hitsRequiredFor(6) == 4);
        check(tally, fails, "hits(1)==3", cfg.hitsRequiredFor(1) == 3);
        check(tally, fails, "hits(40)==9", cfg.hitsRequiredFor(40) == 9);
        check(tally, fails, "hits(200) scales past old 10-cap", cfg.hitsRequiredFor(200) > 10);
        check(tally, fails, "hits(500) capped", cfg.hitsRequiredFor(500) == cfg.maxHits);
        check(tally, fails, "hits monotonic", cfg.hitsRequiredFor(5) <= cfg.hitsRequiredFor(30));

        // --- Efficiency: 1 + level/2 progress per hit ---
        check(tally, fails, "eff0->1", TimberConfig.progressPerHit(0) == 1);
        check(tally, fails, "eff2->2", TimberConfig.progressPerHit(2) == 2);
        check(tally, fails, "eff5->3", TimberConfig.progressPerHit(5) == 3);
        check(tally, fails, "eff-neg safe", TimberConfig.progressPerHit(-3) == 1);

        // --- durability: ceil(logs*0.25); stump mode charges in full (event is cancelled) ---
        check(tally, fails, "wear(6)==2", cfg.durabilityForFell(6) == 2);
        check(tally, fails, "wear(40)==10", cfg.durabilityForFell(40) == 10);
        check(tally, fails, "wear(0)==0", cfg.durabilityForFell(0) == 0);

        // --- crush: scales with size, capped, off→0; default enabled ---
        check(tally, fails, "crush(0)==0", cfg.crushDamageFor(0) == 0);
        check(tally, fails, "crush(10) formula",
                cfg.crushDamageFor(10) == Math.min(cfg.crushMaxDamage, cfg.crushBaseDamage + 10 * cfg.crushPerLogDamage));
        check(tally, fails, "crush capped at max", cfg.crushDamageFor(100000) == cfg.crushMaxDamage);
        check(tally, fails, "crush monotonic", cfg.crushDamageFor(5) <= cfg.crushDamageFor(50));
        check(tally, fails, "crush enabled default", cfg.crushEnabled);

        // --- tool-tier scaling: tier detection, default caps, too-weak gate ---
        check(tally, fails, "tier wooden", Tools.tierOf(Material.WOODEN_AXE).equals("WOODEN"));
        check(tally, fails, "tier golden", Tools.tierOf(Material.GOLDEN_AXE).equals("GOLDEN"));
        check(tally, fails, "tier netherite", Tools.tierOf(Material.NETHERITE_AXE).equals("NETHERITE"));
        check(tally, fails, "tier custom empty", Tools.tierOf(Material.STICK).isEmpty());
        check(tally, fails, "scaling on by default", cfg.toolScalingEnabled);
        check(tally, fails, "tier caps loaded",
                cfg.tierMaxLogs.get("WOODEN") == 12 && cfg.tierMaxLogs.get("NETHERITE") == -1);
        ItemStack woodAxe = new ItemStack(Material.WOODEN_AXE);
        ItemStack diamondAxe = new ItemStack(Material.DIAMOND_AXE);
        ItemStack netheriteAxe = new ItemStack(Material.NETHERITE_AXE);
        check(tally, fails, "wooden caps at 12", Tools.maxLogsFor(woodAxe, cfg) == 12);
        check(tally, fails, "wooden too weak for 100", Tools.tooWeakFor(woodAxe, 100, cfg));
        check(tally, fails, "wooden ok for 8", !Tools.tooWeakFor(woodAxe, 8, cfg));
        check(tally, fails, "diamond fells a giant", !Tools.tooWeakFor(diamondAxe, 500, cfg));
        check(tally, fails, "netherite unlimited", Tools.maxLogsFor(netheriteAxe, cfg) < 0);
        check(tally, fails, "vanilla axe detected", Tools.isAxe(diamondAxe, cfg));
        check(tally, fails, "stick is not an axe", !Tools.isAxe(new ItemStack(Material.STICK), cfg));

        // --- wild-only guard: the treehouse / log-cabin protector (natural vs player-build neighbours) ---
        check(tally, fails, "wild-only default on", cfg.wildOnly);
        check(tally, fails, "natural neighbours pass",
                TreeScanner.isNaturalNeighbor(Material.AIR) && TreeScanner.isNaturalNeighbor(Material.DIRT)
                        && TreeScanner.isNaturalNeighbor(Material.GRASS_BLOCK) && TreeScanner.isNaturalNeighbor(Material.STONE)
                        && TreeScanner.isNaturalNeighbor(Material.OAK_LOG) && TreeScanner.isNaturalNeighbor(Material.OAK_LEAVES)
                        && TreeScanner.isNaturalNeighbor(Material.WATER) && TreeScanner.isNaturalNeighbor(Material.SNOW));
        check(tally, fails, "player builds rejected (incl. bed)",
                !TreeScanner.isNaturalNeighbor(Material.OAK_PLANKS) && !TreeScanner.isNaturalNeighbor(Material.GLASS)
                        && !TreeScanner.isNaturalNeighbor(Material.OAK_FENCE) && !TreeScanner.isNaturalNeighbor(Material.WHITE_BED)
                        && !TreeScanner.isNaturalNeighbor(Material.OAK_DOOR) && !TreeScanner.isNaturalNeighbor(Material.TORCH)
                        && !TreeScanner.isNaturalNeighbor(Material.COBBLESTONE) && !TreeScanner.isNaturalNeighbor(Material.WHITE_WOOL));

        // --- world whitelist ---
        check(tally, fails, "empty whitelist allows all", cfg.worldAllowed("AnyWorld"));

        // --- species fencing: felling never spreads across wood types ---
        check(tally, fails, "species OAK_LOG", TreeScanner.speciesOf(Material.OAK_LOG).equals("OAK"));
        check(tally, fails, "species stripped dark oak wood",
                TreeScanner.speciesOf(Material.STRIPPED_DARK_OAK_WOOD).equals("DARK_OAK"));
        check(tally, fails, "species crimson stem", TreeScanner.speciesOf(Material.CRIMSON_STEM).equals("CRIMSON"));
        check(tally, fails, "species bamboo block", TreeScanner.speciesOf(Material.BAMBOO_BLOCK).equals("BAMBOO"));
        check(tally, fails, "oak != birch",
                !TreeScanner.speciesOf(Material.OAK_LOG).equals(TreeScanner.speciesOf(Material.BIRCH_LOG)));
        check(tally, fails, "oak leaves match oak", TreeScanner.leafMatchesSpecies("OAK", Material.OAK_LEAVES));
        check(tally, fails, "azalea canopy counts as oak",
                TreeScanner.leafMatchesSpecies("OAK", Material.AZALEA_LEAVES)
                        && TreeScanner.leafMatchesSpecies("OAK", Material.FLOWERING_AZALEA_LEAVES));
        check(tally, fails, "birch leaves don't match oak",
                !TreeScanner.leafMatchesSpecies("OAK", Material.BIRCH_LEAVES)
                        && TreeScanner.leafMatchesSpecies("BIRCH", Material.BIRCH_LEAVES));
        check(tally, fails, "same-species default on", cfg.sameSpeciesOnly);

        // --- display decimation caps ---
        check(tally, fails, "decimate 2000->400", ToppleAnimator.decimate(dummyNodes(2000), 400).size() == 400);
        check(tally, fails, "decimate 3->3", ToppleAnimator.decimate(dummyNodes(3), 400).size() == 3);
        check(tally, fails, "decimate budget 0", ToppleAnimator.decimate(dummyNodes(50), 0).isEmpty());

        // --- trunk shrink: proportional, never empty before done, exactly 0 on the last hit ---
        check(tally, fails, "keep(12,4,1)==9", FelledTrunk.keepCount(12, 4, 1) == 9);
        check(tally, fails, "keep(12,4,3)==3", FelledTrunk.keepCount(12, 4, 3) == 3);
        check(tally, fails, "keep(12,4,4)==0", FelledTrunk.keepCount(12, 4, 4) == 0);
        boolean shrinkOk = true;
        for (int p = 1; p < 10; p++) shrinkOk &= FelledTrunk.keepCount(3, 10, p) >= 1;
        check(tally, fails, "small trunk never empties early", shrinkOk && FelledTrunk.keepCount(3, 10, 10) == 0);

        // --- hitbox layout: one per ~2.5 blocks, 1..16, strictly inside the trunk line ---
        check(tally, fails, "boxes(6)==3", FelledTrunk.hitboxCount(6) == 3);
        check(tally, fails, "boxes(30)==12", FelledTrunk.hitboxCount(30) == 12);
        check(tally, fails, "boxes(100) capped 16", FelledTrunk.hitboxCount(100) == 16);
        check(tally, fails, "boxes(1)>=1", FelledTrunk.hitboxCount(1) == 1);
        boolean spreadOk = true;
        int n = FelledTrunk.hitboxCount(12);
        double prev = -1;
        for (int k = 0; k < n; k++) {
            double d = (k + 0.5) * 12.0 / n;
            spreadOk &= d > prev && d > 0 && d < 12;
            prev = d;
        }
        check(tally, fails, "boxes spread inside trunk", spreadOk);

        // --- rotation axis: unit length and perpendicular to the fall direction ---
        for (double[] d : new double[][]{{1, 0}, {0, 1}, {0.6, 0.8}, {-0.7071, 0.7071}}) {
            float[] a = ToppleAnimator.axis(d[0], d[1]);
            double len = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
            double dot = a[0] * d[0] + a[2] * d[1];
            check(tally, fails, "axis(" + d[0] + "," + d[1] + ") unit & perp",
                    Math.abs(len - 1.0) < 1e-3 && Math.abs(dot) < 1e-3 && a[1] == 0f);
        }

        // --- transform at theta=0 is the identity placement ---
        TreeShape.Node node = new TreeShape.Node(0, 5, 0, Material.OAK_LOG.createBlockData(), true);
        Transformation t0 = ToppleAnimator.transform(node, 0, 0, 0, new Quaternionf(), 0);
        check(tally, fails, "transform@0 places block in situ",
                Math.abs(t0.getTranslation().y - 5f) < 1e-3 && Math.abs(t0.getTranslation().x) < 1e-3);

        // --- transform at rest falls toward +x for d=(1,0), and the ground-drop lowers it ---
        float[] axis = ToppleAnimator.axis(1, 0);
        Quaternionf q = ToppleAnimator.quat(axis, Math.toRadians(88));
        Transformation tr = ToppleAnimator.transform(node, 0, 0, 0, q, 0);
        check(tally, fails, "topple falls toward +x", tr.getTranslation().x > 0.9f && tr.getTranslation().y < 0.2f);
        Transformation td = ToppleAnimator.transform(node, 0, 0, 0, q, 3.0);
        check(tally, fails, "yDrop lowers the rig by 3",
                Math.abs((tr.getTranslation().y - td.getTranslation().y) - 3.0f) < 1e-3);
        // node (0,5,0) about origin at 88° toward +x, dropped 3: centre lands near (5.5, -3.3, 0.5)
        double[] lp = ToppleAnimator.landedPos(node, 0, 0, 0, q, 3.0);
        check(tally, fails, "landedPos tracks transform",
                lp[0] > 5.0 && lp[0] < 6.0 && lp[1] > -3.6 && lp[1] < -3.0 && Math.abs(lp[2] - 0.5) < 1e-3);

        // --- leaf tints: every overworld leaf resolves (cherry/pale oak ride dedicated particles) ---
        boolean tintsOk = true;
        for (String name : new String[]{"OAK_LEAVES", "SPRUCE_LEAVES", "BIRCH_LEAVES", "JUNGLE_LEAVES",
                "ACACIA_LEAVES", "DARK_OAK_LEAVES", "MANGROVE_LEAVES", "AZALEA_LEAVES",
                "FLOWERING_AZALEA_LEAVES", "CHERRY_LEAVES", "PALE_OAK_LEAVES"}) {
            Material m = Material.matchMaterial(name);          // PALE_OAK_LEAVES is null pre-1.21.4 — skipped
            if (m != null) tintsOk &= Fx.tintFor(m) != null;
        }
        check(tally, fails, "leaf tints resolve", tintsOk);
        check(tally, fails, "sizeT clamps", Fx.sizeT(0) == 0f && Fx.sizeT(40) == 1f && Fx.sizeT(4000) == 1f);

        // --- progress bar glyphs ---
        check(tally, fails, "bar(2,4)", Messages.bar(2, 4).equals("■■□□"));
        check(tally, fails, "bar clamps", Messages.bar(9, 4).equals("■■■■") && Messages.bar(-1, 2).equals("□□"));

        // --- config defaults in range ---
        check(tally, fails, "config defaults in range",
                cfg.logYieldMultiplier == 0.8 && cfg.fallRestDegrees >= 60 && cfg.fallRestDegrees <= 90
                        && cfg.maxHits >= cfg.hitsToFell && cfg.chopCooldownTicks >= 0
                        && cfg.leaveStump && cfg.leafLoot && cfg.sneakBypass);

        int total = tally[0] + tally[1];
        String verdict = tally[1] == 0
                ? "PASS " + tally[0] + "/" + total + " AMCTimber selftest ok"
                : "FAIL " + tally[1] + "/" + total + " AMCTimber: " + String.join(", ", fails);
        if (out != null) out.sendMessage(verdict);
        return verdict;
    }

    private static void check(int[] tally, List<String> fails, String name, boolean ok) {
        if (ok) tally[0]++;
        else { tally[1]++; fails.add(name); }
    }

    private static List<TreeShape.Node> dummyNodes(int count) {
        List<TreeShape.Node> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) list.add(new TreeShape.Node(i, 0, 0, null, true));
        return list;
    }
}
