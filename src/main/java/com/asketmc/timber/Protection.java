package com.asketmc.timber;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.ArrayList;
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
        return decision(player, ProtectionHook.Action.SOURCE_BREAK, location, material)
                == ProtectionHook.Decision.ALLOW;
    }

    ProtectionHook.Decision decision(Player player, Location location, Material material) {
        return decision(player, ProtectionHook.Action.SOURCE_BREAK, location, material);
    }

    boolean canPerform(Player player, ProtectionHook.Action action, Location location, Material material) {
        return decision(player, action, location, material) == ProtectionHook.Decision.ALLOW;
    }

    ProtectionHook.Decision decision(Player player, ProtectionHook.Action action,
                                     Location location, Material material) {
        if (!respectBuilds) return ProtectionHook.Decision.ALLOW;
        boolean error = false;
        for (ProtectionHook hook : hooks) {
            if (!hook.present()) continue;
            ProtectionHook.Decision decision = hook.check(player, action, location, material);
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
        return canFell(player, shape,
                new FellAttemptBudget(Integer.MAX_VALUE, Integer.MAX_VALUE, 1_000_000_000L, System::nanoTime));
    }

    boolean canFell(Player player, TreeShape shape, FellAttemptBudget budget) {
        if (!respectBuilds || !active()) return true;
        List<ProtectionHook.Attempt> attempts = beginAttempts(player);
        Location base = new Location(shape.world, shape.cutX, shape.cutY, shape.cutZ);
        if (decision(attempts, ProtectionHook.Action.SOURCE_BREAK,
                base, shape.baseMaterial, budget) != ProtectionHook.Decision.ALLOW) return false;
        if (!allBreakable(attempts, shape, shape.logs, budget)) return false;
        if (!allBreakable(attempts, shape, shape.leaves, budget)) return false;
        return allBreakable(attempts, shape, shape.attachments, budget);
    }

    boolean canLand(Player player, TreeShape shape, LandingPlan landing, int logYield,
                    FellAttemptBudget budget) {
        return canLand(player, shape, landing, List.of(), logYield, budget);
    }

    boolean canLand(Player player, TreeShape shape, LandingPlan landing,
                    List<ItemStack> leafLoot, int logYield, FellAttemptBudget budget) {
        if (!respectBuilds || !active()) return true;
        List<ProtectionHook.Attempt> attempts = beginAttempts(player);
        for (LandingPlan.EntityCell cell : landing.entityCells) {
            if (!budget.checkpoint()
                    || decision(attempts, ProtectionHook.Action.LAND_ENTITY,
                    cell.location(), cell.material(), budget)
                    != ProtectionHook.Decision.ALLOW) return false;
        }
        java.util.Set<Material> leafMaterials = java.util.EnumSet.noneOf(Material.class);
        for (ItemStack item : leafLoot) {
            if (item != null && item.getAmount() > 0) leafMaterials.add(item.getType());
        }
        for (Material material : leafMaterials) {
            if (decision(attempts, ProtectionHook.Action.ITEM_DROP,
                    landing.recoveryLocation, material, budget) != ProtectionHook.Decision.ALLOW) return false;
        }
        if (logYield > 0 && decision(attempts, ProtectionHook.Action.ITEM_DROP,
                landing.logDropLocation, shape.baseMaterial, budget) != ProtectionHook.Decision.ALLOW) return false;
        return true;
    }

    boolean canDamage(Player player, LivingEntity target) {
        if (!respectBuilds) return true;
        boolean error = false;
        for (ProtectionHook hook : hooks) {
            if (!hook.present()) continue;
            ProtectionHook.Decision result = hook.canDamage(player, target, target.getLocation());
            if (result == ProtectionHook.Decision.DENY) return false;
            if (result == ProtectionHook.Decision.ERROR) {
                error = true;
                warnThrottled(hook.name());
            }
        }
        return !error || !failClosed;
    }

    private List<ProtectionHook.Attempt> beginAttempts(Player player) {
        List<ProtectionHook.Attempt> attempts = new ArrayList<>();
        for (ProtectionHook hook : hooks) if (hook.present()) attempts.add(hook.beginAttempt(player));
        return attempts;
    }

    private boolean allBreakable(List<ProtectionHook.Attempt> attempts, TreeShape shape,
                                 List<TreeShape.Node> nodes, FellAttemptBudget budget) {
        for (TreeShape.Node node : nodes) {
            if (!budget.checkpoint()) return false;
            Location location = new Location(shape.world, node.x, node.y, node.z);
            if (decision(attempts, ProtectionHook.Action.SOURCE_BREAK,
                    location, node.data.getMaterial(), budget)
                    != ProtectionHook.Decision.ALLOW) return false;
        }
        return true;
    }

    private ProtectionHook.Decision decision(List<ProtectionHook.Attempt> attempts,
                                               ProtectionHook.Action action, Location location,
                                               Material material, FellAttemptBudget budget) {
        boolean error = false;
        for (ProtectionHook.Attempt attempt : attempts) {
            if (!budget.takeHookCall()) return ProtectionHook.Decision.DENY;
            ProtectionHook.Decision result = attempt.check(action, location, material);
            if (!budget.checkpoint()) return ProtectionHook.Decision.DENY;
            if (result == ProtectionHook.Decision.DENY) return result;
            if (result == ProtectionHook.Decision.ERROR) error = true;
        }
        if (!error) return ProtectionHook.Decision.ALLOW;
        return failClosed ? ProtectionHook.Decision.DENY : ProtectionHook.Decision.ALLOW;
    }

    private void warnThrottled(String hook) {
        long now = System.currentTimeMillis();
        if (now - lastWarningMillis < WARNING_INTERVAL_MILLIS) return;
        lastWarningMillis = now;
        warningSink.accept(hook + " permission check failed; protection error policy is "
                + (failClosed ? "deny" : "allow") + '.');
    }
}
