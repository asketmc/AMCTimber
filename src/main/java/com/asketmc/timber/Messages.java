package com.asketmc.timber;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * All player-facing text, loaded from {@code messages.yml} and formatted with
 * <a href="https://docs.advntr.dev/minimessage/format.html">MiniMessage</a> so server owners get full
 * colour/format control. Two locales ship (en, ru); each player sees one based on {@code locale-mode}
 * (client = their client language, ru → ru, everything else → en). Item names use translatable
 * components so they localise on the client for free in every language.
 *
 * <p>The progress bar is built in code (it is a two-colour widget, not translatable prose) and injected
 * into the {@code progress} template as the {@code <bar>} placeholder.
 */
final class Messages {
    enum LocaleMode { CLIENT, EN, RU }

    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Map<String, String> en = new HashMap<>();
    private final Map<String, String> ru = new HashMap<>();
    private LocaleMode mode = LocaleMode.CLIENT;

    /** (Re)load from messages.yml. Missing keys fall back to the en map, then to the raw key. */
    void load(FileConfiguration c) {
        mode = parseMode(c.getString("locale-mode", "client"));
        en.clear();
        ru.clear();
        readInto(c.getConfigurationSection("en"), en);
        readInto(c.getConfigurationSection("ru"), ru);
    }

    private static void readInto(ConfigurationSection sec, Map<String, String> into) {
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            into.put(key, sec.getString(key, ""));
        }
    }

    private static LocaleMode parseMode(String s) {
        if (s == null) return LocaleMode.CLIENT;
        switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "en": case "english": return LocaleMode.EN;
            case "ru": case "russian": return LocaleMode.RU;
            default: return LocaleMode.CLIENT;
        }
    }

    private boolean ru(Player p) {
        if (mode == LocaleMode.RU) return true;
        if (mode == LocaleMode.EN) return false;
        try { return "ru".equalsIgnoreCase(p.locale().getLanguage()); } catch (Throwable t) { return false; }
    }

    private String raw(Player p, String key) {
        String s = ru(p) ? ru.get(key) : en.get(key);
        if (s == null) s = en.get(key);
        return s == null ? key : s;
    }

    private Component render(Player p, String key, TagResolver... resolvers) {
        return mm.deserialize(raw(p, key), resolvers);
    }

    /** Send an action bar for {@code key}, unless the operator blanked that message. */
    private void actionBar(Player p, String key, TagResolver... resolvers) {
        if (raw(p, key).isEmpty()) return;
        p.sendActionBar(render(p, key, resolvers));
    }

    // --- concrete messages -------------------------------------------------------

    void needAxe(Player p) { actionBar(p, "need-axe"); }

    void ownerLocked(Player p) { actionBar(p, "owner-locked"); }

    void tooWeak(Player p, Material axe, int treeLogs) {
        actionBar(p, "too-weak",
                Placeholder.component("axe", Component.translatable(axe)),
                Placeholder.unparsed("logs", String.valueOf(treeLogs)));
    }

    void progress(Player p, int progress, int required) {
        actionBar(p, "progress",
                Placeholder.component("bar", barComponent(progress, required)),
                Placeholder.unparsed("progress", String.valueOf(clamp(progress, required))),
                Placeholder.unparsed("required", String.valueOf(required)));
    }

    void yield(Player p, Material mat, int logs, int xp, boolean showXp) {
        Component xpSuffix = (showXp && xp > 0)
                ? render(p, "xp-suffix", Placeholder.unparsed("xp", String.valueOf(xp)))
                : Component.empty();
        actionBar(p, "yield",
                Placeholder.unparsed("logs", String.valueOf(logs)),
                Placeholder.component("item", Component.translatable(mat)),
                Placeholder.component("xp_suffix", xpSuffix));
    }

    // --- the progress widget -----------------------------------------------------

    private static int clamp(int progress, int required) {
        return Math.max(0, Math.min(required, progress));
    }

    private Component barComponent(int progress, int required) {
        int done = clamp(progress, required);
        return Component.text("■".repeat(done), NamedTextColor.GREEN)
                .append(Component.text("□".repeat(Math.max(0, required - done)), NamedTextColor.DARK_GRAY));
    }

    /** Pure glyph bar (selftested): {@code ■}×progress + {@code □}×(required−progress). */
    static String bar(int progress, int required) {
        int p = clamp(progress, required);
        return "■".repeat(p) + "□".repeat(Math.max(0, required - p));
    }
}
