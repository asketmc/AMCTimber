package com.asketmc.timber;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.logging.Logger;

/**
 * Awards skill XP when a trunk is chopped, through a configurable console command — so it bridges to ANY
 * skill engine without a compile or runtime dependency on it. The command is a template:
 *
 * <pre>  command: "skillsadmin xp %player% %skill% %amount%"   # native engine / SkillsPlugin
 *   command: "aureliumskills xp %player% %skill% %amount%" # AuraSkills
 *   command: "mcmmo addxp %player% %skill% %amount%"       # mcMMO (skill = Woodcutting)</pre>
 *
 * Placeholders: {@code %player%}, {@code %skill%}, {@code %amount%}. Dispatched as CONSOLE on the global
 * region thread (Folia-safe) so it works regardless of who triggered the fell. {@code mode: none} (or
 * {@code enabled: false}) turns it off and hides the XP part of the yield toast.
 */
final class XpBridge {
    private final Logger log;
    private final Sched sched;
    private final boolean enabled;
    private final String mode;
    private final String skill;
    private final String command;

    XpBridge(Logger log, Sched sched, TimberConfig cfg) {
        this.log = log;
        this.sched = sched;
        this.enabled = cfg.xpEnabled;
        this.mode = cfg.xpMode == null ? "command" : cfg.xpMode.trim().toLowerCase();
        this.skill = (cfg.xpSkill == null || cfg.xpSkill.isBlank()) ? "foraging" : cfg.xpSkill.trim();
        this.command = cfg.xpCommand == null ? "" : cfg.xpCommand.trim();
    }

    void init(Debug debug) {
        if (!present()) {
            debug.info("Skill XP awards are disabled (xp.enabled=false or mode=none).");
        } else {
            debug.info("Fell drops grant " + skill + " XP via console command: \"" + command + "\".");
        }
    }

    /** Whether XP is actually being granted (controls the XP part of the yield toast too). */
    boolean present() {
        return enabled && !"none".equals(mode) && !command.isEmpty();
    }

    /** Grant {@code amount} XP to the player. Safe no-op when disabled, amount<=0, or player is null. */
    void grant(Player player, int amount) {
        if (!present() || amount <= 0 || player == null) return;
        final String cmd = command
                .replace("%player%", player.getName())
                .replace("%skill%", skill)
                .replace("%amount%", Integer.toString(amount));
        sched.global(() -> {
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } catch (Throwable t) {
                log.warning("XP grant failed for " + player.getName() + ": " + t);
            }
        });
    }
}
