package com.asketmc.timber;

import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * The plugin's only gameplay event surface.
 *
 * <p><b>Stage 1</b> — {@link BlockBreakEvent} at HIGHEST/ignoreCancelled: the cut block's break is already
 * permitted (first protection gate), so if it's a log + the gating passes + it scans as a real tree + the
 * tool is strong enough + the whole section is breakable, we start a topple. In stump mode the event is
 * then CANCELLED — the axed block stays in the world as the stump top (Valheim-style); otherwise only its
 * drop is suppressed. Sneaking bypasses felling entirely so builders can still harvest single logs.
 *
 * <p><b>Stage 2</b> — chopping the felled trunk: left-click ({@link EntityDamageByEntityEvent}) and
 * right-click ({@link PlayerInteractEntityEvent}, main hand only — the offhand duplicate event is
 * ignored) on any of the trunk's hitboxes route through {@link #chop}: axe gate with a localized hint,
 * owner-lock, an anti-spam cooldown, Efficiency-scaled progress, and per-hit durability.
 */
final class BlockBreakListener implements Listener {
    private final TimberPlugin plugin;

    BlockBreakListener(TimberPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        TimberConfig cfg = plugin.cfg();
        if (!cfg.enabled) return;
        Block block = event.getBlock();
        if (!TreeScanner.isLog(block.getType())) return;
        Player p = event.getPlayer();
        if (!cfg.worldAllowed(block.getWorld().getName())) return;
        if (p.getGameMode() == GameMode.CREATIVE && !cfg.allowCreative) return;
        if (!p.hasPermission("amctimber.use")) return;
        if (cfg.sneakBypass && p.isSneaking()) return;       // builder bypass: vanilla single-block break
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (cfg.requireAxe && !Tools.isAxe(hand, cfg)) return;

        double[] dir = TreeScanner.fallDir(p.getLocation(), block.getX(), block.getZ());
        TreeShape shape = plugin.scanner().scan(block, dir[0], dir[1]);
        if (!shape.isTree) return;                           // ordinary single-log break
        if (!plugin.protection().canFell(p, shape)) return;

        // Tool-tier gate: a wooden axe can't bring down a giant. Too weak → no topple.
        if (cfg.toolScalingEnabled && Tools.tooWeakFor(hand, shape.effectiveLogCount(), cfg)) {
            if (p.getGameMode() != GameMode.CREATIVE) {
                plugin.messages().tooWeak(p, hand.getType(), shape.effectiveLogCount());
            }
            if ("cancel".equalsIgnoreCase(cfg.tooWeakAction)) event.setCancelled(true);
            return;                                          // default: let vanilla break the single block
        }

        if (plugin.fellManager().tryFell(p, shape, plugin.runtime())) {
            if (shape.stump) {
                event.setCancelled(true);                    // the axed block stays — it IS the stump
            } else {
                event.setDropItems(false);                   // logs come from the felled trunk, not the base
                event.setExpToDrop(0);
            }
            int wear = cfg.durabilityForFell(shape.logCount());
            if (wear > 0 && p.getGameMode() != GameMode.CREATIVE) {
                p.damageItemStack(EquipmentSlot.HAND, wear);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onRightClickTrunk(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;   // the offhand fires a second event — one chop per click
        if (event.getRightClicked() instanceof Interaction interaction) {
            if (chop(event.getPlayer(), interaction)) event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onAttackTrunk(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Interaction interaction
                && event.getDamager() instanceof Player player) {
            if (chop(player, interaction)) event.setCancelled(true);
        }
    }

    /** Apply one chop to the trunk backed by this interaction. Returns true if it was ours (event handled). */
    boolean chop(Player p, Interaction interaction) {
        FelledTrunk trunk = plugin.store().byInteraction(interaction);
        if (trunk == null) return false;
        if (!p.hasPermission("amctimber.use")) return false;
        TimberConfig cfg = trunk.config();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (cfg.requireAxe && !Tools.isAxe(hand, cfg)) {
            if (trunk.hintReady(p.getUniqueId())) trunk.needAxe(p);
            return true;                                     // handled (no chop without an axe)
        }
        if (trunk.blockedFor(p)) {
            if (trunk.hintReady(p.getUniqueId())) trunk.ownerLocked(p);
            return true;
        }
        if (!trunk.chopReady(p.getUniqueId())) return true;  // swing spam

        int progress = TimberConfig.progressPerHit(hand.getEnchantmentLevel(Enchantment.EFFICIENCY));
        if (cfg.durabilityPerChopHit > 0 && p.getGameMode() != GameMode.CREATIVE) {
            p.damageItemStack(EquipmentSlot.HAND, cfg.durabilityPerChopHit);
        }
        boolean done = trunk.chop(p, progress);
        if (done) {
            plugin.store().drop(trunk);
            plugin.debug().full("yielded " + trunk.yieldLogs() + " logs for " + p.getName());
        }
        return true;
    }
}
