package com.asketmc.timber;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Compensating transaction for destructive world-block mutations. */
final class WorldMutationJournal {
    interface BlockAccess {
        BlockData read(int x, int y, int z);
        void clear(int x, int y, int z);
        boolean isAir(int x, int y, int z);
        void restore(int x, int y, int z, BlockData data);
    }

    private final BlockAccess blocks;
    private final List<TreeShape.Node> removed = new ArrayList<>();
    private boolean closed;
    private int rollbackFailures;

    WorldMutationJournal(World world) {
        this(new BukkitBlockAccess(world));
    }

    WorldMutationJournal(BlockAccess blocks) {
        this.blocks = blocks;
    }

    boolean preflight(List<TreeShape.Node> nodes) {
        if (closed) return false;
        for (TreeShape.Node node : nodes) {
            if (!sameSnapshot(node.data, blocks.read(node.x, node.y, node.z))) return false;
        }
        return true;
    }

    boolean remove(TreeShape.Node node) {
        if (closed || !sameSnapshot(node.data, blocks.read(node.x, node.y, node.z))) return false;
        removed.add(node);
        blocks.clear(node.x, node.y, node.z);
        return true;
    }

    void commit() {
        closed = true;
        removed.clear();
    }

    int rollback() {
        if (closed) return 0;
        int restored = 0;
        List<TreeShape.Node> reverse = new ArrayList<>(removed);
        Collections.reverse(reverse);
        for (TreeShape.Node node : reverse) {
            try {
                if (!blocks.isAir(node.x, node.y, node.z)) continue;
                blocks.restore(node.x, node.y, node.z, node.data);
                restored++;
            } catch (RuntimeException failure) {
                rollbackFailures++;
            }
        }
        removed.clear();
        closed = true;
        return restored;
    }

    int rollbackFailures() {
        return rollbackFailures;
    }

    static boolean sameSnapshot(BlockData expected, BlockData actual) {
        return expected != null && actual != null && expected.equals(actual);
    }

    private static final class BukkitBlockAccess implements BlockAccess {
        private final World world;

        private BukkitBlockAccess(World world) {
            this.world = world;
        }

        @Override
        public BlockData read(int x, int y, int z) {
            return world.getBlockAt(x, y, z).getBlockData();
        }

        @Override
        public void clear(int x, int y, int z) {
            world.getBlockAt(x, y, z).setType(Material.AIR, false);
        }

        @Override
        public boolean isAir(int x, int y, int z) {
            return world.getBlockAt(x, y, z).getType().isAir();
        }

        @Override
        public void restore(int x, int y, int z, BlockData data) {
            Block block = world.getBlockAt(x, y, z);
            block.setBlockData(data, false);
        }
    }
}
