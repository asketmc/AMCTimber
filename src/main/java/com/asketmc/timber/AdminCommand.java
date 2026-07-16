package com.asketmc.timber;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Locale;

/** Handles administration and explicitly gated command-driven QA hooks. */
final class AdminCommand implements CommandExecutor {
    private static final String ADMIN_PERMISSION = "amctimber.admin";
    private static final String QA_PERMISSION = "amctimber.qa";
    private static final String RED = "\u00a7c";
    private static final String GREEN = "\u00a7a";
    private static final String YELLOW = "\u00a7e";
    private static final String GRAY = "\u00a77";
    private static final String DARK_GRAY = "\u00a78";
    private static final String WHITE = "\u00a7f";

    private final TimberPlugin plugin;

    AdminCommand(TimberPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(RED + "No permission (" + ADMIN_PERMISSION + ").");
            return true;
        }
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.applyConfig();
                sender.sendMessage(GREEN + "[AMCTimber] config + messages reloaded.");
            }
            case "selftest" -> SelfTest.run(sender);
            case "info" -> sendInfo(sender);
            case "test" -> handleTest(sender, args);
            default -> sender.sendMessage(RED + "[AMCTimber] unknown subcommand. /amctimber");
        }
        return true;
    }

    private void sendUsage(CommandSender sender) {
        String usage = GRAY + "/amctimber reload | selftest | info";
        if (plugin.cfg().qaCommandsEnabled && sender.hasPermission(QA_PERMISSION)) {
            usage += " | test fell <x> <y> <z> <player> | test break <x> <y> <z> <player> [sneak]"
                    + " | test attack <player> | test use <player> | test chop <player> [hits]";
        }
        sender.sendMessage(usage);
    }

    private void sendInfo(CommandSender sender) {
        TimberConfig cfg = plugin.cfg();
        EntityBudget.Snapshot budget = plugin.budget().snapshot();
        RecoveryBudget.Snapshot recovery = plugin.recoveryBudget().snapshot();
        RuntimeWorkLimiter.Snapshot work = plugin.workLimiter().snapshot();
        sender.sendMessage(GRAY + "[AMCTimber] active fells: " + WHITE + plugin.fellManager().activeCount()
                + GRAY + ", live trunks: " + WHITE + plugin.store().size()
                + GRAY + ", pending yield: " + WHITE + plugin.store().pendingYieldCount()
                + GRAY + ", recovery budget: " + WHITE + (recovery.pending() + recovery.reserved())
                + "/" + recovery.maxEntries()
                + GRAY + ", entity budget: " + WHITE + budget.sessions() + "/" + budget.maxSessions()
                + " sessions, " + budget.entities() + "/" + budget.maxEntities() + " entities"
                + GRAY + ", queued entity work: " + WHITE + work.queuedEntityOps()
                + GRAY + ", enabled: " + WHITE + cfg.enabled
                + GRAY + ", stump: " + WHITE + cfg.leaveStump
                + GRAY + ", yield x: " + WHITE + cfg.logYieldMultiplier
                + GRAY + ", hits: " + WHITE + cfg.hitsToFell + "+" + cfg.hitsPerLog
                + "/log (max " + cfg.maxHits + ")"
                + GRAY + ", tool-scaling: " + WHITE + cfg.toolScalingEnabled
                + GRAY + ", crush: " + WHITE
                + (cfg.crushEnabled
                ? cfg.crushBaseDamage + "+" + cfg.crushPerLogDamage + "/log<=" + cfg.crushMaxDamage
                : "off"));
    }

    private void handleTest(CommandSender sender, String[] args) {
        if (!plugin.cfg().qaCommandsEnabled) {
            sender.sendMessage(RED + "[AMCTimber] QA commands are disabled in config.");
            return;
        }
        if (!sender.hasPermission(QA_PERMISSION)) {
            sender.sendMessage(RED + "No permission (" + QA_PERMISSION + ").");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(RED + "/amctimber test fell|break|attack|use|chop ...");
            return;
        }

        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "fell" -> scheduleTestFell(sender, args);
            case "break" -> scheduleTestBreak(sender, args);
            case "attack", "use" -> scheduleTestClick(sender, sub, args);
            case "chop" -> scheduleTestChop(sender, args);
            default -> sender.sendMessage(RED + "/amctimber test fell|break|attack|use|chop ...");
        }
    }

    private void scheduleTestFell(CommandSender sender, String[] args) {
        if (args.length < 6) {
            sender.sendMessage(RED + "/amctimber test fell <x> <y> <z> <player>");
            return;
        }
        Player owner = onlinePlayer(sender, args[5]);
        if (owner == null) return;
        Integer x = parseInt(args[2]);
        Integer y = parseInt(args[3]);
        Integer z = parseInt(args[4]);
        if (x == null || y == null || z == null) {
            sender.sendMessage(RED + "[AMCTimber] x/y/z must be integers.");
            return;
        }
        World world = owner.getWorld();
        plugin.sched().next(() -> runTestFell(sender, owner, world, x, y, z));
    }

    private void scheduleTestBreak(CommandSender sender, String[] args) {
        if (args.length < 6) {
            sender.sendMessage(RED + "/amctimber test break <x> <y> <z> <player> [sneak]");
            return;
        }
        Player player = onlinePlayer(sender, args[5]);
        if (player == null) return;
        Integer x = parseInt(args[2]);
        Integer y = parseInt(args[3]);
        Integer z = parseInt(args[4]);
        if (x == null || y == null || z == null) {
            sender.sendMessage(RED + "[AMCTimber] x/y/z must be integers.");
            return;
        }
        boolean sneak = args.length >= 7 && args[6].equalsIgnoreCase("sneak");
        World world = player.getWorld();
        plugin.sched().next(() -> runTestBreak(sender, player, world, x, y, z, sneak));
    }

    private void scheduleTestClick(CommandSender sender, String sub, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(RED + "/amctimber test " + sub + " <player>");
            return;
        }
        Player player = onlinePlayer(sender, args[2]);
        if (player == null) return;
        plugin.sched().next(() -> runTestClick(sender, sub, player));
    }

    private void scheduleTestChop(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(RED + "/amctimber test chop <player> [hits]");
            return;
        }
        Player player = onlinePlayer(sender, args[2]);
        if (player == null) return;
        Integer hits = args.length >= 4 ? parseInt(args[3]) : null;
        plugin.sched().next(() -> runTestChop(sender, player, hits));
    }

    private void runTestFell(CommandSender sender, Player owner, World world, int x, int y, int z) {
        if (!owner.isOnline()) {
            sender.sendMessage(RED + "[AMCTimber] player is no longer online: " + owner.getName());
            return;
        }
        Block block = world.getBlockAt(x, y, z);
        if (!TreeScanner.isLog(block.getType())) {
            sender.sendMessage(RED + "[AMCTimber] block at " + x + "," + y + "," + z + " is "
                    + block.getType() + ", not a log.");
            return;
        }
        double[] direction = TreeScanner.fallDir(owner.getLocation(), x, z);
        TreeShape shape = plugin.scanner().scan(block, direction[0], direction[1]);
        if (!shape.isTree) {
            sender.sendMessage(YELLOW + "[AMCTimber] not a tree: " + shape.rejectReason
                    + " (logs=" + shape.logCount() + ")");
            return;
        }
        boolean started = plugin.fellManager().tryFell(owner, shape, plugin.runtime());
        sender.sendMessage(started
                ? GREEN + "[AMCTimber] fell started: logs=" + shape.logCount()
                + ", yield=" + plugin.cfg().logsYielded(shape.logCount())
                + (shape.stump ? " (cut block stays as stump)" : "")
                : YELLOW + "[AMCTimber] fell rejected (cap/duplicate).");
    }

    private void runTestBreak(
            CommandSender sender, Player player, World world, int x, int y, int z, boolean sneak) {
        if (!player.isOnline()) {
            sender.sendMessage(RED + "[AMCTimber] player is no longer online: " + player.getName());
            return;
        }
        Block block = world.getBlockAt(x, y, z);
        String previousType = block.getType().name();
        boolean previousSneaking = player.isSneaking();
        boolean broke;
        try {
            if (sneak) player.setSneaking(true);
            broke = player.breakBlock(block);
        } finally {
            player.setSneaking(previousSneaking);
        }
        sender.sendMessage(GRAY + "[AMCTimber] breakBlock(" + previousType
                + (sneak || previousSneaking ? ", sneaking" : "") + ") -> " + broke
                + " " + DARK_GRAY + "(stump mode cancels the event: false + a fell start is green)");
    }

    private void runTestClick(CommandSender sender, String sub, Player player) {
        if (!player.isOnline()) {
            sender.sendMessage(RED + "[AMCTimber] player is no longer online: " + player.getName());
            return;
        }
        FelledTrunk trunk = plugin.store().nearest(player.getLocation(), 8.0);
        Interaction hitbox = trunk != null ? trunk.anyHitbox() : null;
        if (hitbox == null) {
            sender.sendMessage(YELLOW + "[AMCTimber] no felled trunk within 8 blocks of "
                    + player.getName() + ".");
            return;
        }
        if (sub.equals("attack")) {
            player.attack(hitbox);
            sender.sendMessage(GREEN + "[AMCTimber] " + player.getName()
                    + " attacked the trunk hitbox (left-click path).");
            return;
        }
        PlayerInteractEntityEvent event = new PlayerInteractEntityEvent(player, hitbox, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);
        sender.sendMessage(GREEN + "[AMCTimber] right-click event dispatched, cancelled="
                + event.isCancelled() + " (cancelled=true means the trunk consumed it).");
    }

    private void runTestChop(CommandSender sender, Player player, Integer hitsArg) {
        if (!player.isOnline()) {
            sender.sendMessage(RED + "[AMCTimber] player is no longer online: " + player.getName());
            return;
        }
        FelledTrunk trunk = plugin.store().nearest(player.getLocation(), 8.0);
        if (trunk == null) {
            sender.sendMessage(YELLOW + "[AMCTimber] no felled trunk within 8 blocks of "
                    + player.getName() + ".");
            return;
        }
        TimberConfig cfg = trunk.config();
        int hits = hitsArg != null ? hitsArg : cfg.maxHits;
        boolean done = false;
        int applied = 0;
        for (int i = 0; i < hits && !done; i++) {
            done = trunk.chop(player, 1);
            applied++;
        }
        if (done) {
            plugin.store().drop(trunk);
            sender.sendMessage(GREEN + "[AMCTimber] trunk fully chopped after " + applied
                    + " hit(s): yielded " + trunk.yieldLogs() + " logs for " + player.getName() + ".");
        } else {
            sender.sendMessage(GRAY + "[AMCTimber] applied " + applied
                    + " hit(s); trunk not yet fully chopped.");
        }
    }

    private static Player onlinePlayer(CommandSender sender, String name) {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) sender.sendMessage(RED + "[AMCTimber] player not online: " + name);
        return player;
    }

    private static Integer parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
