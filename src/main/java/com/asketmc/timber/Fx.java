package com.asketmc.timber;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.List;
import java.util.Map;

/**
 * The atmosphere engine: layered vanilla sounds + particles for every phase of a fell — axe bite, the
 * pre-fall creak, leaves rushing past mid-fall, a ground-shaking impact line, canopy crash, chop hits
 * with rising pitch, and the final break. Everything is config-gated (fx.sounds / fx.particles /
 * fx.falling-leaves) and scales with tree size ({@code sizeT} = 0 small … 1 giant). Vanilla resource pack
 * only — no custom assets required.
 *
 * <p><b>Cross-version safety.</b> Sounds are played by their stable namespaced keys (immune to the
 * Sound enum → registry change in newer versions), and version-specific particles/materials
 * (TINTED_LEAVES, PALE_OAK_LEAVES — added 1.21.4) are resolved by name with graceful fallbacks, so one
 * jar behaves correctly across 1.20.6 → 1.21.x.
 */
final class Fx {

    // Stable namespaced sound keys (work on every supported version, no Sound enum dependency).
    private static final String S_AXE_STRIP = "minecraft:item.axe.strip";
    private static final String S_WOOD_BREAK = "minecraft:block.wood.break";
    private static final String S_CHEST_OPEN = "minecraft:block.chest.open";
    private static final String S_DOOR_OPEN = "minecraft:block.wooden_door.open";
    private static final String S_AZALEA_BREAK = "minecraft:block.azalea_leaves.break";
    private static final String S_RAVAGER_STEP = "minecraft:entity.ravager.step";
    private static final String S_WOOD_FALL = "minecraft:block.wood.fall";
    private static final String S_ROOTED_DIRT_BREAK = "minecraft:block.rooted_dirt.break";
    private static final String S_WOOD_HIT = "minecraft:block.wood.hit";
    private static final String S_BUNDLE_DROP = "minecraft:item.bundle.drop_contents";
    private static final String S_GRASS_BREAK = "minecraft:block.grass.break";

    // Version-resolved particles/materials (null on versions that predate them).
    private static final Particle P_TINTED_LEAVES = particle("TINTED_LEAVES");      // 1.21.4+
    private static final Particle P_CHERRY_LEAVES = particle("CHERRY_LEAVES");      // 1.20+
    private static final Particle P_PALE_OAK_LEAVES = particle("PALE_OAK_LEAVES");  // 1.21.4+
    private static final Material M_PALE_OAK_LEAVES = Material.matchMaterial("PALE_OAK_LEAVES");

    private final boolean sounds;
    private final boolean particles;
    private final boolean fallingLeaves;

    Fx(boolean sounds, boolean particles, boolean fallingLeaves) {
        this.sounds = sounds;
        this.particles = particles;
        this.fallingLeaves = fallingLeaves;
    }

    /** 0..1 weight for sound volume / particle counts from the toppled log count. */
    static float sizeT(int logs) {
        return (float) Math.max(0.0, Math.min(1.0, logs / 40.0));
    }

    // --- stage 1: the fell -------------------------------------------------------

    /** The axe bites through — played the instant the fell is confirmed. */
    void crackStart(Location at) {
        if (!sounds) return;
        World w = at.getWorld();
        w.playSound(at, S_AXE_STRIP, 1.0f, 0.9f);
        w.playSound(at, S_WOOD_BREAK, 1.0f, 0.7f);
    }

    /** Pre-fall wooden groan while the trunk starts leaning. */
    void creak(Location at, float sizeT) {
        if (!sounds) return;
        World w = at.getWorld();
        w.playSound(at, S_CHEST_OPEN, 0.9f + 0.7f * sizeT, 0.5f);
        w.playSound(at, S_DOOR_OPEN, 0.7f, 0.45f);
    }

    /** Mid-fall: the canopy rushes through the air. */
    void fallRustle(Location at, float sizeT) {
        if (!sounds) return;
        at.getWorld().playSound(at, S_AZALEA_BREAK, 0.9f + 0.5f * sizeT, 0.75f);
    }

    /** Tinted leaves drifting off the canopy while it falls (cherry/pale oak use their own particles). */
    void fallingLeaves(World w, List<double[]> positions, Material leafMat) {
        if (!particles || !fallingLeaves || positions == null) return;
        Particle special = null;
        if (leafMat == Material.CHERRY_LEAVES && P_CHERRY_LEAVES != null) special = P_CHERRY_LEAVES;
        else if (M_PALE_OAK_LEAVES != null && leafMat == M_PALE_OAK_LEAVES && P_PALE_OAK_LEAVES != null) special = P_PALE_OAK_LEAVES;
        Color tint = LEAF_TINTS.getOrDefault(leafMat, DEFAULT_TINT);
        for (double[] p : positions) {
            if (special != null) {
                w.spawnParticle(special, p[0], p[1], p[2], 2, 0.4, 0.3, 0.4, 0.0);
            } else if (P_TINTED_LEAVES != null) {
                w.spawnParticle(P_TINTED_LEAVES, p[0], p[1], p[2], 2, 0.4, 0.3, 0.4, 0.0, tint);
            } else {
                // pre-1.21.4: no tinted-leaf particle — a coloured dust fleck in the leaf's tint stands in.
                w.spawnParticle(Particle.DUST, p[0], p[1], p[2], 2, 0.4, 0.3, 0.4, 0.0,
                        new Particle.DustOptions(tint, 1.4f));
            }
        }
    }

    /** Ground-shaking landing: thud + dust kicked up along the whole trunk line. */
    void impact(World w, List<Location> line, BlockData logData, float sizeT) {
        if (line.isEmpty()) return;
        Location mid = line.get(line.size() / 2);
        if (sounds) {
            w.playSound(mid, S_RAVAGER_STEP, 0.9f + 0.8f * sizeT, 0.7f);
            w.playSound(mid, S_WOOD_FALL, 1.3f, 0.6f);
            w.playSound(line.get(line.size() - 1), S_ROOTED_DIRT_BREAK, 1.0f + 0.5f * sizeT, 0.75f);
        }
        if (particles) {
            int per = 6 + Math.round(8 * sizeT);
            for (int i = 0; i < line.size(); i++) {
                Location at = line.get(i);
                w.spawnParticle(Particle.BLOCK, at, per, 0.9, 0.25, 0.9, logData);
                Block ground = w.getBlockAt(at.getBlockX(), at.getBlockY() - 1, at.getBlockZ());
                if (ground.getType().isSolid()) {
                    w.spawnParticle(Particle.BLOCK, at, per, 0.9, 0.2, 0.9, ground.getBlockData());
                }
                if (i % 2 == 0) w.spawnParticle(Particle.GUST, at, 1, 0.3, 0.1, 0.3, 0.0);
            }
        }
    }

    /** Canopy crash on landing: leaf debris at each landed leaf position. */
    void leafCrash(World w, List<double[]> positions, BlockData leafData) {
        if (positions.isEmpty()) return;
        double[] mid = positions.get(positions.size() / 2);
        if (sounds) w.playSound(new Location(w, mid[0], mid[1], mid[2]), S_AZALEA_BREAK, 1.0f, 1.0f);
        if (particles && leafData != null) {
            for (double[] p : positions) w.spawnParticle(Particle.BLOCK, p[0], p[1], p[2], 4, 0.5, 0.4, 0.5, leafData);
        }
    }

    // --- stage 2: chopping the trunk ----------------------------------------------

    /** One chop hit — pitch rises with progress so finishing a trunk feels like work paying off. */
    void chopHit(Location at, BlockData logData, double progress01) {
        World w = at.getWorld();
        if (sounds) {
            w.playSound(at, S_AXE_STRIP, 1.0f, (float) (0.85 + 0.35 * progress01));
            w.playSound(at, S_WOOD_HIT, 0.8f, 0.8f);
        }
        if (particles && logData != null) w.spawnParticle(Particle.BLOCK, at, 14, 0.6, 0.4, 0.6, logData);
    }

    /** The trunk gives way — logs spill out. */
    void chopBreak(Location at, BlockData logData) {
        World w = at.getWorld();
        if (sounds) {
            w.playSound(at, S_WOOD_BREAK, 1.2f, 0.75f);
            w.playSound(at, S_WOOD_BREAK, 1.0f, 0.95f);
            w.playSound(at, S_BUNDLE_DROP, 0.9f, 0.9f);
        }
        if (particles && logData != null) w.spawnParticle(Particle.BLOCK, at, 28, 1.2, 0.5, 1.2, logData);
    }

    /** An abandoned trunk rots away. */
    void leafRustle(Location at) {
        World w = at.getWorld();
        if (sounds) w.playSound(at, S_GRASS_BREAK, 0.5f, 0.8f);
        if (particles) w.spawnParticle(Particle.CLOUD, at, 8, 1.0, 0.4, 1.0, 0.01);
    }

    // --- leaf tints (vanilla foliage-ish greens; cherry/pale oak use dedicated particles) ---

    private static final Color DEFAULT_TINT = Color.fromRGB(0x77AB2F);
    private static final Map<Material, Color> LEAF_TINTS = Map.ofEntries(
            Map.entry(Material.OAK_LEAVES, Color.fromRGB(0x77AB2F)),
            Map.entry(Material.SPRUCE_LEAVES, Color.fromRGB(0x619961)),
            Map.entry(Material.BIRCH_LEAVES, Color.fromRGB(0x80A755)),
            Map.entry(Material.JUNGLE_LEAVES, Color.fromRGB(0x30BB0B)),
            Map.entry(Material.ACACIA_LEAVES, Color.fromRGB(0xAEA42A)),
            Map.entry(Material.DARK_OAK_LEAVES, Color.fromRGB(0x507A32)),
            Map.entry(Material.MANGROVE_LEAVES, Color.fromRGB(0x8DB127)),
            Map.entry(Material.AZALEA_LEAVES, Color.fromRGB(0x6FA242)),
            Map.entry(Material.FLOWERING_AZALEA_LEAVES, Color.fromRGB(0x6FA242))
    );

    /** Exposed for SelfTest: every leaf material resolves to a non-null tint (or a dedicated particle). */
    static Color tintFor(Material leafMat) {
        return LEAF_TINTS.getOrDefault(leafMat, DEFAULT_TINT);
    }

    private static Particle particle(String name) {
        try {
            return Particle.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
