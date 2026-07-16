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

import java.util.IdentityHashMap;
import java.util.Map;

/** The plugin's bounded two-stage block-break and landed-trunk event surface. */
final class BlockBreakListener implements Listener {
    private final TimberPlugin plugin;
    private final Map<BlockBreakEvent, PreparedFell> pending = new IdentityHashMap<>();

    private record PreparedFell(FellRuntime runtime, TreeShape shape) {}

    BlockBreakListener(TimberPlugin plugin) { this.plugin = plugin; }

    /** Read-only preparation. No world/entity/tool/event mutation occurs in this handler. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void prepareBreak(BlockBreakEvent event) {
        TimberConfig cfg = plugin.cfg();
        if (!cfg.enabled) return;
        Block block = event.getBlock();
        if (!TreeScanner.isLog(block.getType())) return;
        if (!event.isDropItems()) return;                    // honor an earlier no-drop/custom-drop policy
        Player player = event.getPlayer();
        if (!cfg.worldAllowed(block.getWorld().getName())) return;
        if (player.getGameMode() == GameMode.CREATIVE && !cfg.allowCreative) return;
        if (!player.hasPermission("amctimber.use")) return;
        if (cfg.sneakBypass && player.isSneaking()) return;
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (cfg.requireAxe && !Tools.isAxe(hand, cfg)) return;
        if (!plugin.attemptAdmission().tryAcquire(player.getUniqueId())) return;

        double[] direction = TreeScanner.fallDir(player.getLocation(), block.getX(), block.getZ());
        TreeShape shape = plugin.scanner().scan(block, direction[0], direction[1],
                FellAttemptBudget.from(cfg));
        if (!shape.isTree) return;
        pending.put(event, new PreparedFell(plugin.runtime(), shape));
    }

    /** Final ordinary-priority policy state is consumed before the first irreversible mutation. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void commitBreak(BlockBreakEvent event) {
        PreparedFell prepared = pending.remove(event);
        if (prepared == null || event.isCancelled() || !event.isDropItems()) return;

        FellRuntime runtime = prepared.runtime();
        TimberConfig cfg = runtime.config();
        TreeShape shape = prepared.shape();
        Block block = event.getBlock();
        Player player = event.getPlayer();
        if (!cfg.enabled || !TreeScanner.isLog(block.getType())
                || block.getType() != shape.baseMaterial
                || !cfg.worldAllowed(block.getWorld().getName())
                || (player.getGameMode() == GameMode.CREATIVE && !cfg.allowCreative)
                || !player.hasPermission("amctimber.use")
                || (cfg.sneakBypass && player.isSneaking())) return;
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (cfg.requireAxe && !Tools.isAxe(hand, cfg)) return;

        if (cfg.toolScalingEnabled && Tools.tooWeakFor(hand, shape.effectiveLogCount(), cfg)) {
            if (player.getGameMode() != GameMode.CREATIVE) {
                runtime.messages().tooWeak(player, hand.getType(), shape.effectiveLogCount());
            }
            if ("cancel".equalsIgnoreCase(cfg.tooWeakAction)) event.setCancelled(true);
            return;
        }

        if (plugin.fellManager().tryFell(player, shape, runtime)) {
            if (shape.stump) {
                event.setCancelled(true);
            } else {
                event.setDropItems(false);
                event.setExpToDrop(0);
            }
            int wear = cfg.durabilityForFell(shape.logCount());
            if (wear > 0 && player.getGameMode() != GameMode.CREATIVE) {
                player.damageItemStack(EquipmentSlot.HAND, wear);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onRightClickTrunk(PlayerInteractEntityEvent event) {
        if (event.isCancelled()) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getRightClicked() instanceof Interaction interaction) {
            if (chop(event.getPlayer(), interaction)) event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onAttackTrunk(EntityDamageByEntityEvent event) {
        if (event.isCancelled()) return;
        if (event.getEntity() instanceof Interaction interaction
                && event.getDamager() instanceof Player player) {
            if (chop(player, interaction)) event.setCancelled(true);
        }
    }

    /** Apply one chop to the trunk backed by this interaction. */
    boolean chop(Player player, Interaction interaction) {
        FelledTrunk trunk = plugin.store().byInteraction(interaction);
        if (trunk == null) return false;
        if (!player.hasPermission("amctimber.use")) return false;
        TimberConfig cfg = trunk.config();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (cfg.requireAxe && !Tools.isAxe(hand, cfg)) {
            if (trunk.hintReady(player.getUniqueId())) trunk.needAxe(player);
            return true;
        }
        if (trunk.blockedFor(player)) {
            if (trunk.hintReady(player.getUniqueId())) trunk.ownerLocked(player);
            return true;
        }
        int progress = TimberConfig.progressPerHit(hand.getEnchantmentLevel(Enchantment.EFFICIENCY));
        if (!trunk.authorized(player, interaction, progress)) return true;
        if (!trunk.chopReady(player.getUniqueId())) return true;
        if (cfg.durabilityPerChopHit > 0 && player.getGameMode() != GameMode.CREATIVE) {
            player.damageItemStack(EquipmentSlot.HAND, cfg.durabilityPerChopHit);
        }
        boolean done = trunk.chop(player, progress);
        if (done) {
            plugin.store().drop(trunk);
            plugin.debug().full("yielded " + trunk.yieldLogs() + " logs for " + player.getName());
        }
        return true;
    }
}
