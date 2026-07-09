package com.asketmc.timber;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtectionTest {
    @Test
    @Tag("P0")
    void installedHookErrorsFailClosedByDefaultAndEmitEvidence() {
        List<String> warnings = new ArrayList<>();
        Protection protection = new Protection(true, true, warnings::add,
                hook("WorldGuard", ProtectionHook.Decision.ERROR));
        protection.init();

        assertEquals(ProtectionHook.Decision.DENY,
                protection.decision(null, null, Material.OAK_LOG));
        assertFalse(protection.canBreak(null, null, Material.OAK_LOG));
        assertEquals(1, warnings.size());
    }

    @Test
    void explicitAllowPolicyCanKeepADegradedHookFailOpen() {
        Protection protection = new Protection(true, false, ignored -> {},
                hook("Towny", ProtectionHook.Decision.ERROR));
        protection.init();

        assertTrue(protection.canBreak(null, null, Material.OAK_LOG));
    }

    @Test
    void anyDenyWinsAndAbsentHooksDoNotBlock() {
        Protection denied = new Protection(true, true, ignored -> {},
                hook("allow", ProtectionHook.Decision.ALLOW), hook("deny", ProtectionHook.Decision.DENY));
        denied.init();
        assertFalse(denied.canBreak(null, null, Material.OAK_LOG));

        Protection absent = new Protection(true, true, ignored -> {}, new FakeHook("absent", false,
                ProtectionHook.Decision.ERROR));
        absent.init();
        assertTrue(absent.canBreak(null, null, Material.OAK_LOG));
    }

    @Test
    @Tag("P0")
    void wholeTreeCheckIncludesBaseLogsAndEveryRemovedLeaf() {
        CountingHook hook = new CountingHook();
        Protection protection = new Protection(true, true, ignored -> {}, hook);
        protection.init();
        TreeShape shape = new TreeShape(null,
                List.of(node(0, true), node(1, true)), List.of(node(2, false), node(3, false)),
                0, 0, 0, Material.OAK_LOG, 0, 0, 0, 1, 0, 4, true, true, null);

        assertTrue(protection.canFell(null, shape));
        assertEquals(5, hook.calls);
    }

    private static ProtectionHook hook(String name, ProtectionHook.Decision decision) {
        return new FakeHook(name, true, decision);
    }

    private record FakeHook(String name, boolean present, ProtectionHook.Decision decision)
            implements ProtectionHook {
        @Override public void init() {}
        @Override public Decision canBreak(Player player, Location location, Material material) { return decision; }
    }

    private static TreeShape.Node node(int x, boolean log) {
        org.bukkit.block.data.BlockData data = (org.bukkit.block.data.BlockData) Proxy.newProxyInstance(
                ProtectionTest.class.getClassLoader(), new Class<?>[]{org.bukkit.block.data.BlockData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMaterial" -> log ? Material.OAK_LOG : Material.OAK_LEAVES;
                    case "clone" -> proxy;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
        return new TreeShape.Node(x, 0, 0, data, log);
    }

    private static final class CountingHook implements ProtectionHook {
        private int calls;
        @Override public String name() { return "counting"; }
        @Override public void init() {}
        @Override public boolean present() { return true; }
        @Override public Decision canBreak(Player player, Location location, Material material) {
            calls++;
            return Decision.ALLOW;
        }
    }
}
