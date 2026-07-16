package com.asketmc.timber;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FellSessionSecurityTest {
    @Test
    @Tag("P0")
    void delayedCrushTargetMustRemainInsideItsSampledImpactVolume() {
        World first = world("first");
        World second = world("second");
        Location sample = new Location(first, 10, 65, -4);

        assertTrue(FellSession.withinCrushSample(new Location(first, 11.59, 67.19, -5.59), sample, 1.6));
        assertFalse(FellSession.withinCrushSample(new Location(first, 11.61, 65, -4), sample, 1.6));
        assertFalse(FellSession.withinCrushSample(new Location(first, 10, 67.21, -4), sample, 1.6));
        assertFalse(FellSession.withinCrushSample(new Location(second, 10, 65, -4), sample, 1.6));
    }

    private static World world(String name) {
        return (World) Proxy.newProxyInstance(FellSessionSecurityTest.class.getClassLoader(),
                new Class<?>[]{World.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getName", "toString" -> name;
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
