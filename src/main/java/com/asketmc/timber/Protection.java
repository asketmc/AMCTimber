package com.asketmc.timber;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Composes optional protection hooks with an explicit error policy. */
final class Protection {
    private static final long WARNING_INTERVAL_MILLIS = 60_000L;

    private final List<ProtectionHook> hooks;
    private final boolean respectBuilds;
    private final boolean failClosed;
    private final Consumer<String> warningSink;
    private long lastWarningMillis;

    Protection(boolean respectBuilds, boolean failClosed, Consumer<String> warningSink) {
        this(respectBuilds, failClosed, warningSink, new WorldGuardBridge(), new TownyBridge());
    }

    Protection(boolean respectBuilds, boolean failClosed, Consumer<String> warningSink, ProtectionHook... hooks) {
        this.respectBuilds = respectBuilds;
        this.failClosed = failClosed;
        this.warningSink = Objects.requireNonNull(warningSink, "warningSink");
        this.hooks = Arrays.asList(hooks);
    }

    void init() {
        for (ProtectionHook hook : hooks) hook.init();
    }

    boolean active() {
        if (!respectBuilds) return false;
        for (ProtectionHook hook : hooks) if (hook.present()) return true;
        return false;
    }

    String hooks() {
        StringBuilder names = new StringBuilder();
        for (ProtectionHook hook : hooks) {
            if (!hook.present()) continue;
            if (!names.isEmpty()) names.append('+');
            names.append(hook.name());
        }
        return names.toString();
    }

    boolean canBreak(Player player, Location location) {
        return canBreak(player, location, location.getBlock().getType());
    }

    boolean canBreak(Player player, Location location, Material material) {
        return decision(player, location, material) == ProtectionHook.Decision.ALLOW;
    }

    ProtectionHook.Decision decision(Player player, Location location, Material material) {
        if (!respectBuilds) return ProtectionHook.Decision.ALLOW;
        boolean error = false;
        for (ProtectionHook hook : hooks) {
            if (!hook.present()) continue;
            ProtectionHook.Decision decision = hook.canBreak(player, location, material);
            if (decision == ProtectionHook.Decision.DENY) return ProtectionHook.Decision.DENY;
            if (decision == ProtectionHook.Decision.ERROR) {
                error = true;
                warnThrottled(hook.name());
            }
        }
        if (!error) return ProtectionHook.Decision.ALLOW;
        return failClosed ? ProtectionHook.Decision.DENY : ProtectionHook.Decision.ALLOW;
    }

    boolean canFell(Player player, TreeShape shape) {
        if (!respectBuilds || !active()) return true;
        Location base = new Location(shape.world, shape.cutX, shape.cutY, shape.cutZ);
        if (!canBreak(player, base, shape.baseMaterial)) return false;
        if (!allBreakable(player, shape, shape.logs)) return false;
        return allBreakable(player, shape, shape.leaves);
    }

    private boolean allBreakable(Player player, TreeShape shape, List<TreeShape.Node> nodes) {
        for (TreeShape.Node node : nodes) {
            Location location = new Location(shape.world, node.x, node.y, node.z);
            if (!canBreak(player, location, node.data.getMaterial())) return false;
        }
        return true;
    }

    private void warnThrottled(String hook) {
        long now = System.currentTimeMillis();
        if (now - lastWarningMillis < WARNING_INTERVAL_MILLIS) return;
        lastWarningMillis = now;
        warningSink.accept(hook + " permission check failed; protection error policy is "
                + (failClosed ? "deny" : "allow") + '.');
    }
}
