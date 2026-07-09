package com.asketmc.timber;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.util.List;

/**
 * Immutable result of a tree scan: the snapshotted log + leaf blocks (type/data/position only — no live
 * Block or entity references held), the pivot the topple rotates about, the fall direction, and the
 * {@code isTree} verdict. Pure data, so the classification in {@link TreeScanner} is independently
 * testable and there is a clean boundary if the BFS is ever moved off-thread.
 *
 * <p>In stump mode the block the player axed is NOT part of {@code logs} — it stays in the world as the
 * stump top (the break event is cancelled), and only the section above the cut topples.
 */
final class TreeShape {

    /** One snapshotted block. Holds a cloned BlockData + world position; never a live Block. */
    static final class Node {
        final int x, y, z;
        final BlockData data;
        final boolean log;
        Node(int x, int y, int z, BlockData data, boolean log) {
            this.x = x; this.y = y; this.z = z; this.data = data; this.log = log;
        }
    }

    final World world;
    final List<Node> logs;           // the toppling section (excludes the kept stump block in stump mode)
    final List<Node> leaves;
    final int cutX, cutY, cutZ;      // the block the player axed (de-dup key; kept in stump mode)
    final Material baseMaterial;     // log material at the cut — what the felled trunk drops
    final double pivotX, pivotY, pivotZ; // rotation pivot: centroid of the lowest collected log level
    final double dirX, dirZ;         // unit fall direction (away from player)
    final int height;                // vertical span of the toppling section, in blocks
    final boolean stump;             // true = the cut block stays in the world as a stump
    final boolean isTree;
    final String rejectReason;       // null when isTree

    TreeShape(World world, List<Node> logs, List<Node> leaves,
              int cutX, int cutY, int cutZ, Material baseMaterial,
              double pivotX, double pivotY, double pivotZ, double dirX, double dirZ,
              int height, boolean stump, boolean isTree, String rejectReason) {
        this.world = world;
        this.logs = logs;
        this.leaves = leaves;
        this.cutX = cutX; this.cutY = cutY; this.cutZ = cutZ;
        this.baseMaterial = baseMaterial;
        this.pivotX = pivotX; this.pivotY = pivotY; this.pivotZ = pivotZ;
        this.dirX = dirX; this.dirZ = dirZ;
        this.height = height;
        this.stump = stump;
        this.isTree = isTree;
        this.rejectReason = rejectReason;
    }

    int logCount() { return logs.size(); }
    /** Biological tree size used for tool-tier gating: toppling logs plus the kept stump block. */
    int effectiveLogCount() { return logs.size() + (stump ? 1 : 0); }
}
