package com.asketmc.timber;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldMutationJournalTest {
    @Test
    @Tag("P0")
    void rollbackRestoresOnlyBlocksRemovedByThisTransaction() {
        FakeBlocks blocks = new FakeBlocks();
        TreeShape.Node first = node(0, Material.OAK_LOG);
        TreeShape.Node second = node(1, Material.OAK_LEAVES);
        blocks.put(first);
        blocks.put(second);
        WorldMutationJournal journal = new WorldMutationJournal(blocks);

        assertTrue(journal.preflight(List.of(first, second)));
        assertTrue(journal.remove(first));
        assertTrue(journal.remove(second));
        assertEquals(2, journal.rollback());
        assertEquals(first.data, blocks.read(0, 0, 0));
        assertEquals(second.data, blocks.read(1, 0, 0));
        assertEquals(0, journal.rollback());
    }

    @Test
    void changedSnapshotAbortsWithoutDeletingReplacementBlocks() {
        FakeBlocks blocks = new FakeBlocks();
        TreeShape.Node expected = node(0, Material.OAK_LOG);
        blocks.data.put(0, blockData(Material.BIRCH_LOG));
        WorldMutationJournal journal = new WorldMutationJournal(blocks);

        assertFalse(journal.preflight(List.of(expected)));
        assertFalse(journal.remove(expected));
        assertEquals(Material.BIRCH_LOG, blocks.read(0, 0, 0).getMaterial());
    }

    @Test
    @Tag("P0")
    void clearFailureAfterMutationRemainsRollbackable() {
        FakeBlocks blocks = new FakeBlocks();
        TreeShape.Node expected = node(0, Material.OAK_LOG);
        blocks.put(expected);
        blocks.failClearAfterMutation.add(0);
        WorldMutationJournal journal = new WorldMutationJournal(blocks);

        assertThrows(IllegalStateException.class, () -> journal.remove(expected));
        assertEquals(1, journal.rollback());
        assertEquals(Material.OAK_LOG, blocks.read(0, 0, 0).getMaterial());
    }

    @Test
    void rollbackContinuesAfterOneRestoreFails() {
        FakeBlocks blocks = new FakeBlocks();
        TreeShape.Node first = node(0, Material.OAK_LOG);
        TreeShape.Node second = node(1, Material.OAK_LEAVES);
        blocks.put(first);
        blocks.put(second);
        WorldMutationJournal journal = new WorldMutationJournal(blocks);
        assertTrue(journal.remove(first));
        assertTrue(journal.remove(second));
        blocks.failRestore.add(1);

        assertEquals(1, journal.rollback());
        assertEquals(1, journal.rollbackFailures());
        assertEquals(Material.OAK_LOG, blocks.read(0, 0, 0).getMaterial());
        assertTrue(blocks.isAir(1, 0, 0));
    }

    private static TreeShape.Node node(int x, Material material) {
        return new TreeShape.Node(x, 0, 0, blockData(material), material == Material.OAK_LOG);
    }

    private static BlockData blockData(Material material) {
        return (BlockData) Proxy.newProxyInstance(WorldMutationJournalTest.class.getClassLoader(),
                new Class<?>[]{BlockData.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getMaterial" -> material;
                    case "clone" -> proxy;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString", "getAsString" -> material.name();
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        return 0D;
    }

    private static final class FakeBlocks implements WorldMutationJournal.BlockAccess {
        private final Map<Integer, BlockData> data = new HashMap<>();
        private final Set<Integer> failClearAfterMutation = new HashSet<>();
        private final Set<Integer> failRestore = new HashSet<>();

        void put(TreeShape.Node node) {
            data.put(node.x, node.data);
        }

        @Override
        public BlockData read(int x, int y, int z) {
            return data.computeIfAbsent(x, ignored -> blockData(Material.AIR));
        }

        @Override
        public void clear(int x, int y, int z) {
            data.put(x, blockData(Material.AIR));
            if (failClearAfterMutation.contains(x)) throw new IllegalStateException("clear failed");
        }

        @Override
        public boolean isAir(int x, int y, int z) {
            Material material = read(x, y, z).getMaterial();
            return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR;
        }

        @Override
        public void restore(int x, int y, int z, BlockData blockData) {
            if (failRestore.contains(x)) throw new IllegalStateException("restore failed");
            data.put(x, blockData);
        }
    }
}
