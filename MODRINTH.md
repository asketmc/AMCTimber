# 🌲 AMCTimber — Valheim-style tree felling

> Paste this into your Modrinth project **Description** (Modrinth renders Markdown). Add a GIF/screenshot
> at the top — a falling-tree clip converts browsers into downloads better than any wall of text.

<!-- ![A tree toppling over](https://your-cdn/amctimber-demo.gif) -->

Chop the base of a tree and **the whole thing topples over** — a smooth, client-interpolated fall that
leaves a stump, just like Valheim. The downed trunk lies on the ground and must be **chopped again** for
its logs, so clearing a forest *feels* like real work. Giants come crashing down and **crush** whatever
stands where they land.

**No client mod. No resource pack. No required external runtime plugins. One small jar with no shaded
runtime libraries.**

**Inspired by survival games where trees actually fall — and can flatten you.** AMCTimber is a custom,
feature-rich mechanic from **[asketmc](https://asketmc.com)** — a Survival RPG (CIS & EU) where falling
trees are one layer of a deeper survival experience, alongside **temperature, fishing, dynamic weather
events** and more custom mechanics. Come chop a forest with us → **https://asketmc.com**

## Features
- 🌳 **Whole-tree topple** with a client-interpolated, bounded Display-entity animation (no client mod)
- 🪵 **Stump + downed trunk** you re-chop for logs (reduced yield — finish the job)
- 🪓 **Tool-tier scaling** — wooden axe = saplings; **diamond/netherite = 2×2 jungle giants**
- 💥 **Crush damage** — don't stand where it lands
- 🏠 **Conservative build guard** — rejects common player-build materials and suspicious tree shapes;
  this is a heuristic, so use WorldGuard/Towny for important builds
- ⭐ **Skill-XP bridge** to *any* skill plugin (AuraSkills, mcMMO, …) via a configurable command
- 🌍 **EN + RU** built in (MiniMessage, fully editable), auto-localised item names
- **Bounded ephemeral entities** - one global cap covers active falls and landed trunks; tagged entities
  are swept on startup and planned shutdown

## Compatibility
**Paper / Purpur / Pufferfish**, Minecraft **1.20.6-1.21.x**, Java 21+.
Not for Spigot or Folia. WorldGuard & Towny are optional; installed-hook errors deny by default.

## Links
- 📖 Source & issues: https://github.com/asketmc/AMCTimber
- 🔍 Release verification / reviewer notes: https://github.com/asketmc/AMCTimber/blob/main/docs/REVIEWER_NOTES.md
- 🎮 Built for **[asketmc.com](https://asketmc.com)** — survival, CIS & EU. Come play! *(Discord: https://discord.gg/2MuA3Nv)*

## Security / Review Notes

This plugin is public-source and not obfuscated. Release jars are built by GitHub Actions from tagged
source. Each release includes SHA256 checksums, SPDX/CycloneDX SBOMs, Sigstore bundles, GitHub artifact
attestations, and a jar safety report.

The heuristic jar-hygiene gate is configured to fail on native binaries, scripts, nested jars, or shaded
signature metadata. Its pass is scoped pattern evidence, not proof of safety. The plugin does not use
native code, runtime downloads, auto-updaters, telemetry, or hidden external services. Optional
integrations are WorldGuard and Towny.

These checks are verification evidence, not a formal third-party security audit.

## License
GPLv3 — free to use and modify; derivatives stay open-source; no closed-source resale.
