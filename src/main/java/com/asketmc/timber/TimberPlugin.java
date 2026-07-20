package com.asketmc.timber;

import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/** Composes AMCTimber's runtime services and owns their lifecycle. */
public final class TimberPlugin extends JavaPlugin {

    private TimberConfig cfg;
    private Debug debug;
    private Sched sched;
    private Messages messages;
    private Fx fx;
    private XpBridge xpBridge;
    private Protection protection;
    private TreeScanner scanner;
    private EntityBudget budget;
    private RecoveryBudget recoveryBudget;
    private FellAttemptAdmission attemptAdmission;
    private RuntimeWorkLimiter workLimiter;
    private CrushDispatcher crushDispatcher;
    private FellRuntime runtime;
    private FellJobManager fellManager;
    private FelledTrunkStore store;
    private BlockBreakListener listener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.sched = new Sched(this);
        this.recoveryBudget = new RecoveryBudget(PendingYieldFile.MAX_ENTRIES);
        this.debug = new Debug(getLogger());
        this.store = new FelledTrunkStore(debug, recoveryBudget);
        applyConfig();
        store.initializeRecovery(getDataFolder().toPath());
        this.fellManager = new FellJobManager(budget);

        store.purgeTagged("start");
        this.listener = new BlockBreakListener(this);
        getServer().getPluginManager().registerEvents(listener, this);

        PluginCommand command = getCommand("amctimber");
        if (command == null) throw new IllegalStateException("amctimber command is missing from plugin.yml");
        command.setExecutor(new AdminCommand(this));

        sched.timer(attemptAdmission::beginTick, 1L, 1L);
        sched.timer(workLimiter::beginTick, 1L, 1L);
        sched.timer(crushDispatcher::tick, 1L, 1L);
        sched.timer(store::tickYieldDelivery, 1L, 1L);
        sched.timer(store::sweep, 20L, 20L);
        String hooks = protection.hooks();
        debug.info("AMCTimber v" + getPluginMeta().getVersion() + " enabled - felling "
                + (cfg.enabled ? "on" : "OFF")
                + (hooks.isEmpty() ? "" : ", hooks: " + hooks) + ".");
    }

    @Override
    public void onDisable() {
        if (crushDispatcher != null) crushDispatcher.clear();
        try {
            if (fellManager != null) fellManager.shutdown();
        } finally {
            if (store != null) store.shutdown();
        }
        if (debug != null) debug.info("AMCTimber v" + getPluginMeta().getVersion() + " disabled.");
    }

    /** Rebuild config-derived services while preserving active runtime state. */
    void applyConfig() {
        reloadConfig();
        TimberConfig nextConfig = new TimberConfig(getConfig());
        Debug nextDebug = new Debug(getLogger());
        nextDebug.setLevel(nextConfig.debug);
        Messages nextMessages = loadMessages();
        Fx nextEffects = new Fx(nextConfig.sounds, nextConfig.particles, nextConfig.fallingLeaves);
        XpBridge nextXpBridge = new XpBridge(getLogger(), sched, nextConfig);
        nextXpBridge.init(nextDebug);
        Protection nextProtection = new Protection(
                nextConfig.respectBuilds, nextConfig.protectionFailClosed, nextDebug::warn);
        nextProtection.init();
        recoveryBudget.updateLimit(nextConfig.maxRecoveryRecords);
        store.updateDebug(nextDebug);
        store.updateProtection(nextProtection);
        TreeScanner nextScanner = new TreeScanner(nextConfig);
        EntityBudget nextBudget = budget == null
                ? new EntityBudget(nextConfig.maxLiveTrunks, nextConfig.maxTotalEntities) : budget;
        nextBudget.updateLimits(nextConfig.maxLiveTrunks, nextConfig.maxTotalEntities);
        FellAttemptAdmission nextAttemptAdmission = attemptAdmission == null
                ? new FellAttemptAdmission(nextConfig.maxScanAttemptsPerTick,
                nextConfig.scanAttemptCooldownTicks) : attemptAdmission;
        nextAttemptAdmission.updateLimits(nextConfig.maxScanAttemptsPerTick,
                nextConfig.scanAttemptCooldownTicks);
        RuntimeWorkLimiter nextWorkLimiter = workLimiter == null
                ? new RuntimeWorkLimiter(nextConfig) : workLimiter;
        nextWorkLimiter.updateLimits(nextConfig);
        CrushDispatcher nextCrushDispatcher = crushDispatcher == null
                ? new CrushDispatcher(nextWorkLimiter, nextConfig) : crushDispatcher;
        nextCrushDispatcher.updateLimits(nextConfig);
        store.updateDeliveryLimit(nextConfig.yieldDeliveryStepsPerTick);
        FellRuntime nextRuntime = new FellRuntime(nextConfig, sched, nextDebug, store,
                nextEffects, nextXpBridge, nextMessages, nextProtection,
                nextWorkLimiter, nextCrushDispatcher, recoveryBudget);

        // Publish one fully built generation. Existing fells retain their prior generation references.
        this.cfg = nextConfig;
        this.debug = nextDebug;
        this.messages = nextMessages;
        this.fx = nextEffects;
        this.xpBridge = nextXpBridge;
        this.protection = nextProtection;
        this.scanner = nextScanner;
        this.budget = nextBudget;
        this.attemptAdmission = nextAttemptAdmission;
        this.workLimiter = nextWorkLimiter;
        this.crushDispatcher = nextCrushDispatcher;
        this.runtime = nextRuntime;
    }

    private Messages loadMessages() {
        File f = new File(getDataFolder(), "messages.yml");
        if (!f.exists()) saveResource("messages.yml", false);
        Messages loaded = new Messages();
        loaded.load(YamlConfiguration.loadConfiguration(f));
        return loaded;
    }

    TimberConfig cfg() { return cfg; }
    Debug debug() { return debug; }
    Sched sched() { return sched; }
    Messages messages() { return messages; }
    Fx fx() { return fx; }
    XpBridge xpBridge() { return xpBridge; }
    Protection protection() { return protection; }
    TreeScanner scanner() { return scanner; }
    EntityBudget budget() { return budget; }
    RecoveryBudget recoveryBudget() { return recoveryBudget; }
    FellAttemptAdmission attemptAdmission() { return attemptAdmission; }
    RuntimeWorkLimiter workLimiter() { return workLimiter; }
    FellRuntime runtime() { return runtime; }
    FellJobManager fellManager() { return fellManager; }
    FelledTrunkStore store() { return store; }
}
