package com.asketmc.timber;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LandingPlanTest {
    @Test
    @Tag("P0")
    void destinationPlanCoversRenderedNodesHitboxesAndBothYieldSinksWithoutDuplicates() {
        World world = world();
        TreeShape shape = new TreeShape(world,
                List.of(node(0, 65, 0, Material.OAK_LOG, true),
                        node(0, 66, 0, Material.OAK_LOG, true)),
                List.of(),
                List.of(node(0, 67, 0, Material.OAK_LEAVES, false)),
                List.of(), 0, 64, 0, Material.OAK_LOG,
                0.5, 65, 0.5, 1, 0, 4, true, true, null);
        LandingPlan plan = LandingPlan.compute(shape, new TimberConfig(new YamlConfiguration()), 0);

        assertEquals(FelledTrunk.hitboxCount(shape.height), plan.hitboxLocations.size());
        assertEquals(1, plan.leafImpactPositions.size());
        Set<String> cells = new HashSet<>();
        Set<String> locations = new HashSet<>();
        plan.entityCells.forEach(entityCell -> {
            cells.add(cell(entityCell.location()) + ':' + entityCell.material());
            locations.add(cell(entityCell.location()));
        });
        assertEquals(plan.entityCells.size(), cells.size());
        assertEquals(0.5, plan.recoveryLocation.getX() - Math.floor(plan.recoveryLocation.getX()));
        assertEquals(0.5, plan.logDropLocation.getZ() - Math.floor(plan.logDropLocation.getZ()));
        plan.hitboxLocations.forEach(location -> assertTrue(locations.contains(cell(location))));
        assertTrue(plan.entityCells.size() > shape.logs.size() + shape.leaves.size()
                + plan.hitboxLocations.size(), "rotated cubes and hitbox AABBs must cover boundary cells");
    }

    private static String cell(org.bukkit.Location location) {
        return location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
    }

    private static TreeShape.Node node(int x, int y, int z, Material material, boolean log) {
        BlockData data = (BlockData) Proxy.newProxyInstance(LandingPlanTest.class.getClassLoader(),
                new Class<?>[]{BlockData.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getMaterial" -> material;
                    case "clone" -> proxy;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> null;
                });
        return new TreeShape.Node(x, y, z, data, log);
    }

    private static World world() {
        UUID id = UUID.randomUUID();
        return (World) Proxy.newProxyInstance(LandingPlanTest.class.getClassLoader(),
                new Class<?>[]{World.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUID" -> id;
                    case "toString" -> "landing-plan-world";
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        return '\0';
    }
}
