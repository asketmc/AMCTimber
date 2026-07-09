package com.asketmc.timber;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * AMCTimber — Valheim-style tree felling. Chop a tree and the section above your cut topples (a rig of
 * non-persistent BlockDisplays, client-interpolated), leaving a chop-able stump; the downed trunk must be
 * re-chopped for its logs (reduced yield + skill XP), and the canopy drops its vanilla loot. All visual
 * entities are ephemeral, tagged, capped, swept, and purged on enable AND disable — zero entity bloat.
 *
 * <p>Runs on Paper / Purpur / Pufferfish / <b>Folia</b>, MC 1.20.6 → 1.21.x, with no required
 * dependencies (WorldGuard and Towny are optional, fail-open integrations).
 */
public final class TimberPlugin extends JavaPlugin implements CommandExecutor {

    private TimberConfig cfg;
    private Debug debug;
    private Sched sched;
    private Messages messages;
    private Fx fx;
    private XpBridge xpBridge;
    private Protection protection;
    private TreeScanner scanner;
    private FellJobManager fellManager;
    private FelledTrunkStore store;
    private BlockBreakListener listener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.debug = new Debug(getLogger());
        this.sched = new Sched(this);
        this.messages = new Messages();
        this.store = new FelledTrunkStore(getLogger());
        this.fellManager = new FellJobManager(this);
        applyConfig();
        store.purgeTagged("start");
        this.listener = new BlockBreakListener(this);
        getServer().getPluginManager().registerEvents(listener, this);
        if (getCommand("amctimber") != null) getCommand("amctimber").setExecutor(this);
        // per-second despawn / orphan sweep (global region — Folia-safe)
        sched.globalTimer(() -> store.sweep(fx, sched), 20L, 20L);
        String hooks = protection.hooks();
        debug.info("AMCTimber v" + getPluginMeta().getVersion() + " enabled — "
                + (Sched.FOLIA ? "Folia" : "Paper") + ", felling " + (cfg.enabled ? "on" : "OFF")
                + (hooks.isEmpty() ? "" : ", hooks: " + hooks) + ".");
    }

    @Override
    public void onDisable() {
        if (store != null) store.shutdown();
        if (debug != null) debug.info("AMCTimber v" + getPluginMeta().getVersion() + " disabled.");
    }

    /** (Re)build everything derived from config.yml + messages.yml. Keeps store + manager state intact. */
    private void applyConfig() {
        reloadConfig();
        this.cfg = new TimberConfig(getConfig());
        this.debug.setLevel(cfg.debug);
        this.fx = new Fx(cfg.sounds, cfg.particles, cfg.fallingLeaves);
        this.xpBridge = new XpBridge(getLogger(), sched, cfg);
        this.xpBridge.init(debug);
        this.protection = new Protection(cfg.respectBuilds);
        this.protection.init();
        this.scanner = new TreeScanner(cfg);
        reloadMessages();
    }

    private void reloadMessages() {
        File f = new File(getDataFolder(), "messages.yml");
        if (!f.exists()) saveResource("messages.yml", false);
        messages.load(YamlConfiguration.loadConfiguration(f));
    }

    TimberConfig cfg() { return cfg; }
    Debug debug() { return debug; }
    Sched sched() { return sched; }
    Messages messages() { return messages; }
    Fx fx() { return fx; }
    XpBridge xpBridge() { return xpBridge; }
    Protection protection() { return protection; }
    TreeScanner scanner() { return scanner; }
    FellJobManager fellManager() { return fellManager; }
    FelledTrunkStore store() { return store; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("amctimber.admin")) {
            sender.sendMessage("§cNo permission (amctimber.admin).");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§7/amctimber reload | selftest | info | test fell <x> <y> <z> [player]"
                    + " | test break <x> <y> <z> <player> [sneak] | test attack <player>"
                    + " | test use <player> | test chop <player> [hits]");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> { applyConfig(); sender.sendMessage("§a[AMCTimber] config + messages reloaded."); }
            case "selftest" -> SelfTest.run(sender);
            case "info" -> sender.sendMessage("§7[AMCTimber] active fells: §f" + fellManager.activeCount()
                    + "§7, live trunks: §f" + store.size() + "§7, enabled: §f" + cfg.enabled
                    + "§7, platform: §f" + (Sched.FOLIA ? "Folia" : "Paper")
                    + "§7, stump: §f" + cfg.leaveStump + "§7, yield×: §f" + cfg.logYieldMultiplier
                    + "§7, hits: §f" + cfg.hitsToFell + "+" + cfg.hitsPerLog + "/log (max " + cfg.maxHits + ")"
                    + "§7, tool-scaling: §f" + cfg.toolScalingEnabled + "§7, crush: §f"
                    + (cfg.crushEnabled ? cfg.crushBaseDamage + "+" + cfg.crushPerLogDamage + "/log≤" + cfg.crushMaxDamage : "off"));
            case "test" -> handleTest(sender, args);
            default -> sender.sendMessage("§c[AMCTimber] unknown subcommand. /amctimber");
        }
        return true;
    }

    /**
     * Deterministic command-drivable hooks for QA / server tests:
     * fell    — topple the tree at a log position directly (no event, no durability).
     * break   — {@code Player#breakBlock}: the REAL break pipeline (events, protection, durability).
     * attack  — {@code Player#attack} on the nearest trunk hitbox: the real left-click chop path.
     * use     — dispatches a real PlayerInteractEntityEvent: the right-click chop path.
     * chop    — direct chop calls on the nearest trunk (no cooldown), for fast drain-to-yield.
     */
    private void handleTest(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage("§c/amctimber test fell|break|attack|use|chop ..."); return; }
        String sub = args[1].toLowerCase();
        switch (sub) {
            case "fell" -> {
                if (args.length < 5) { sender.sendMessage("§c/amctimber test fell <x> <y> <z> [player]"); return; }
                Player owner = args.length >= 6 ? Bukkit.getPlayerExact(args[5]) : null;
                Integer x = parseInt(args[2]), y = parseInt(args[3]), z = parseInt(args[4]);
                if (x == null || y == null || z == null) { sender.sendMessage("§c[AMCTimber] x/y/z must be integers."); return; }
                org.bukkit.World w = owner != null ? owner.getWorld() : Bukkit.getWorlds().get(0);
                Block b = w.getBlockAt(x, y, z);
                if (!TreeScanner.isLog(b.getType())) {
                    sender.sendMessage("§c[AMCTimber] block at " + x + "," + y + "," + z + " is " + b.getType() + ", not a log.");
                    return;
                }
                double[] dir = owner != null
                        ? TreeScanner.fallDir(owner.getLocation(), x, y, z)
                        : new double[]{1, 0};
                TreeShape shape = scanner.scan(b, dir[0], dir[1]);
                if (!shape.isTree) {
                    sender.sendMessage("§e[AMCTimber] not a tree: " + shape.rejectReason + " (logs=" + shape.logCount() + ")");
                    return;
                }
                boolean started = fellManager.tryFell(owner, shape);
                sender.sendMessage(started
                        ? "§a[AMCTimber] fell started: logs=" + shape.logCount() + ", yield=" + cfg.logsYielded(shape.logCount())
                        + (shape.stump ? " (cut block stays as stump)" : "")
                        : "§e[AMCTimber] fell rejected (cap/duplicate).");
            }
            case "break" -> {
                if (args.length < 6) { sender.sendMessage("§c/amctimber test break <x> <y> <z> <player> [sneak]"); return; }
                Player p = Bukkit.getPlayerExact(args[5]);
                if (p == null) { sender.sendMessage("§c[AMCTimber] player not online: " + args[5]); return; }
                Integer x = parseInt(args[2]), y = parseInt(args[3]), z = parseInt(args[4]);
                if (x == null || y == null || z == null) { sender.sendMessage("§c[AMCTimber] x/y/z must be integers."); return; }
                Block b = p.getWorld().getBlockAt(x, y, z);
                String was = b.getType().name();
                boolean sneak = args.length >= 7 && args[6].equalsIgnoreCase("sneak");
                boolean broke;
                if (sneak) p.setSneaking(true);
                try {
                    broke = p.breakBlock(b);
                } finally {
                    if (sneak) p.setSneaking(false);
                }
                sender.sendMessage("§7[AMCTimber] breakBlock(" + was + (sneak ? ", sneaking" : "") + ") -> " + broke
                        + " §8(stump mode cancels the event: false + a fell start is green)");
            }
            case "attack", "use" -> {
                if (args.length < 3) { sender.sendMessage("§c/amctimber test " + sub + " <player>"); return; }
                Player p = Bukkit.getPlayerExact(args[2]);
                if (p == null) { sender.sendMessage("§c[AMCTimber] player not online: " + args[2]); return; }
                FelledTrunk trunk = store.nearest(p.getLocation(), 8.0);
                Interaction hb = trunk != null ? trunk.anyHitbox() : null;
                if (hb == null) { sender.sendMessage("§e[AMCTimber] no felled trunk within 8 blocks of " + p.getName() + "."); return; }
                if (sub.equals("attack")) {
                    p.attack(hb);                                  // the real vanilla attack path → our listener
                    sender.sendMessage("§a[AMCTimber] " + p.getName() + " attacked the trunk hitbox (left-click path).");
                } else {
                    PlayerInteractEntityEvent ev = new PlayerInteractEntityEvent(p, hb, EquipmentSlot.HAND);
                    Bukkit.getPluginManager().callEvent(ev);       // real event dispatch → our listener
                    sender.sendMessage("§a[AMCTimber] right-click event dispatched, cancelled=" + ev.isCancelled()
                            + " (cancelled=true means the trunk consumed it).");
                }
            }
            case "chop" -> {
                if (args.length < 3) { sender.sendMessage("§c/amctimber test chop <player> [hits]"); return; }
                Player p = Bukkit.getPlayerExact(args[2]);
                if (p == null) { sender.sendMessage("§c[AMCTimber] player not online: " + args[2]); return; }
                FelledTrunk trunk = store.nearest(p.getLocation(), 8.0);
                if (trunk == null) { sender.sendMessage("§e[AMCTimber] no felled trunk within 8 blocks of " + p.getName() + "."); return; }
                int hits = cfg.maxHits;
                if (args.length >= 4) { Integer h = parseInt(args[3]); if (h != null) hits = h; }
                boolean done = false;
                int applied = 0;
                for (int i = 0; i < hits && !done; i++) {
                    done = trunk.chop(p, 1, fx, xpBridge, messages, cfg);
                    applied++;
                }
                if (done) {
                    store.drop(trunk);
                    sender.sendMessage("§a[AMCTimber] trunk fully chopped after " + applied + " hit(s): yielded "
                            + trunk.yieldLogs() + " logs for " + p.getName() + ".");
                } else {
                    sender.sendMessage("§7[AMCTimber] applied " + applied + " hit(s); trunk not yet fully chopped.");
                }
            }
            default -> sender.sendMessage("§c/amctimber test fell|break|attack|use|chop ...");
        }
    }

    private static Integer parseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
    }
}
