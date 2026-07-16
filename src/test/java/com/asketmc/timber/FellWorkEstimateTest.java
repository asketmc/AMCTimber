package com.asketmc.timber;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FellWorkEstimateTest {
    @Test
    void launchEstimateIncludesPreflightMutationRollbackLootAndGroundHeadroom() {
        World world = (World) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{World.class}, (proxy, method, args) ->
                method.getName().equals("getMinHeight") ? -64 : null);
        TreeShape shape = new TreeShape(world,
                List.of(node(true), node(true)), List.of(node(true)),
                List.of(node(false), node(false)), List.of(node(false)),
                0, 0, 0, Material.OAK_LOG, 0, 64, 0, 1, 0, 4, true, true, null);

        int withoutLoot = FellWorkEstimate.launchBlockOps(shape, false);
        int withLoot = FellWorkEstimate.launchBlockOps(shape, true);
        assertEquals(3, withLoot - withoutLoot);
        assertEquals(6 + 5 * 4 + 128, withoutLoot);
    }

    private static TreeShape.Node node(boolean log) {
        Material material = log ? Material.OAK_LOG : Material.OAK_LEAVES;
        BlockData data = (BlockData) Proxy.newProxyInstance(FellWorkEstimateTest.class.getClassLoader(),
                new Class<?>[]{BlockData.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getMaterial" -> material;
                    case "clone" -> proxy;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> null;
                });
        return new TreeShape.Node(0, 0, 0, data, log);
    }
}
