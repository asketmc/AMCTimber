# Changelog

All notable changes to **AMCTimber**. This project adheres to [Semantic Versioning](https://semver.org)
and the [Keep a Changelog](https://keepachangelog.com) format.

## [1.0.0] — 2026-06-19

First public release — extracted and generalised from the internal asketmc build.

### Added
- **Valheim-style tree felling**: cut a tree's base and the section above topples in a smooth,
  client-interpolated fall, leaving a stump; the downed trunk must be re-chopped for its logs.
- **Tool-tier scaling** — each axe tier (wooden → netherite) caps the tree size it can fell; configurable.
- **Configurable axe items** — `axes.use-vanilla-tag` + `axes.extra-items` decide what counts as an axe.
- **Pluggable skill-XP bridge** — award XP to any skill plugin via a configurable console command
  (`%player%`/`%skill%`/`%amount%`), no dependency. Examples for AuraSkills & mcMMO included.
- **Configurable durability** cost per fell and per chop.
- **Crush damage** — a landing tree flattens whatever stands in its path (size-scaled, capped, PvP-safe).
- **Treehouse / log-cabin guard** and species-aware spreading (no cross-wood-type felling).
- **Debug levels** — `off` / `info` / `full`.
- **Externalised messages** — `messages.yml` in EN + RU, MiniMessage-formatted, per-client locale.
- **Folia support** — uses Paper's region/global/entity scheduler API directly (no external lib).
- **Wide compatibility** — one jar for Paper/Purpur/Pufferfish/Folia, MC **1.20.6 → 1.21.x**
  (version-specific particles/sounds resolved at runtime).
- **bStats metrics** (opt-in; set the plugin id to enable).
- **Self-check** — `/amctimber selftest` (74 assertions over the pure logic).

[1.0.0]: https://github.com/asketmc/AMCTimber/releases/tag/v1.0.0
