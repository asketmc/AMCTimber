package com.asketmc.timber;

import java.util.Locale;
import java.util.logging.Logger;

/**
 * Tiered logging gate (config {@code debug: off | info | full}).
 *
 * <ul>
 *   <li><b>off</b>  — warnings only; the console stays quiet.</li>
 *   <li><b>info</b> — lifecycle lines: enable, reload, integration presence (default).</li>
 *   <li><b>full</b> — everything, including a line per fell / topple / yield (diagnostics).</li>
 * </ul>
 */
final class Debug {
    enum Level { OFF, INFO, FULL }

    private final Logger log;
    private volatile Level level = Level.INFO;

    Debug(Logger log) { this.log = log; }

    void setLevel(String s) { this.level = parse(s); }

    Level level() { return level; }

    static Level parse(String s) {
        if (s == null) return Level.INFO;
        switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "off": case "false": case "none": case "quiet": return Level.OFF;
            case "full": case "all": case "verbose": case "debug": return Level.FULL;
            default: return Level.INFO;
        }
    }

    /** Lifecycle messages — suppressed only at {@code off}. */
    void info(String m) { if (level != Level.OFF) log.info(m); }

    /** Verbose per-event diagnostics — emitted only at {@code full}. */
    void full(String m) { if (level == Level.FULL) log.info(m); }

    /** Always shown, regardless of level. */
    void warn(String m) { log.warning(m); }

    /** Always shown, regardless of level. */
    void severe(String m) { log.severe(m); }
}
