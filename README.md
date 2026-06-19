# 🌲 AMCTimber

**Valheim-style tree felling for Paper, Purpur, Pufferfish & Folia (MC 1.20.6 → 1.21.x).**

Chop the base of a tree and the whole thing **topples over** — a smooth, client-interpolated fall that
leaves a stump behind, just like Valheim. The downed trunk lies on the ground and has to be **chopped
again** for its logs, so harvesting a forest *feels* like work. Giants come crashing down and **crush
whatever is standing where they land.**

No client mod. No resource pack. No required dependencies. One small, dependency-free jar.

> **Inspired by survival games where trees actually fall — and can flatten you.** AMCTimber is a custom,
> feature-rich mechanic from **[asketmc](https://asketmc.com)** — a Survival RPG server (CIS & EU) where
> falling trees are one layer of a deeper survival experience, alongside temperature, fishing, dynamic
> weather events and more custom mechanics. Come play on the server it was built for →
> **[asketmc.com](https://asketmc.com)** · *(Discord: `discord.gg/your-invite`)*

---

## ✨ Features

- **Whole-tree topple** — a real falling animation using vanilla Display entities (no lag-spawning of
  blocks, no client mod). Trees fall *away* from the player.
- **Stump + downed trunk** — cut anywhere on the trunk; everything above topples and the rest stays as a
  stump. The fallen trunk must be chopped again for its logs (reduced yield — you have to finish the job).
- **Tool-tier scaling** — a wooden axe only fells saplings; you need a **diamond/netherite axe to bring
  down a 2×2 jungle giant**. Fully configurable per tier.
- **Crush damage** — stand where a giant lands and it flattens you (size-scaled, capped, PvP-safe by default).
- **Your builds are safe** — a "tree" touching *any* player-placed block (planks, fences, glass, beds,
  stairs, …) is treated as a structure and **never auto-fells**, so you'll never accidentally topple your
  house or treehouse. Sneak-to-bypass for builders, plus optional **WorldGuard region** & **Towny claim**
  respect on top.
- **Species-aware** — felling never spreads across wood types; one oak won't drag a neighbouring birch down.
- **Skill-XP bridge** — award XP to *any* skill plugin via a configurable command (AuraSkills, mcMMO, a
  native engine, …) with **zero dependency**.
- **Atmosphere** — layered creak / fall / impact sounds, dust lines, drifting tinted leaves, canopy crash.
- **i18n** — English + Russian out of the box, MiniMessage-formatted, fully editable; item names localise
  on each client automatically.
- **Folia-native** — every world/entity action runs on the correct region thread. Runs unmodified on
  Paper *and* Folia.
- **Zero entity bloat** — every spawned entity is non-persistent, tagged, capped, swept every second, and
  purged on enable/disable. Nothing accumulates in your region files.

---

## ✅ Compatibility

| | |
|---|---|
| **Server software** | Paper, Purpur, Pufferfish, **Folia** (and other Paper forks) |
| **Minecraft** | **1.20.6 → 1.21.x** (one jar) |
| **Java** | 21+ |
| **Spigot** | ❌ not supported (the plugin uses modern Display/Interaction entities + Adventure) |
| **Dependencies** | none required; **WorldGuard** & **Towny** are optional, fail-open integrations |

> Built against the 1.20.6 API and runtime-tested on the latest 1.21.x. Version-specific particles/sounds
> are resolved at runtime, so one jar behaves correctly across the whole range.

---

## 📦 Installation

1. Download `AMCTimber-x.y.z.jar` from the [Modrinth page](https://modrinth.com/plugin/amctimber) or
   [GitHub Releases](https://github.com/asketmc/AMCTimber/releases).
2. Drop it into your server's `plugins/` folder.
3. Restart the server.
4. Edit `plugins/AMCTimber/config.yml` and `messages.yml`, then `/amctimber reload`.

---

## ⌨️ Commands

`/amctimber` (aliases: `/timber`, `/amct`) — requires `amctimber.admin`.

| Subcommand | Description |
|---|---|
| `/amctimber reload` | Reload `config.yml` + `messages.yml` |
| `/amctimber info` | Live status (active fells, trunks, platform, key settings) |
| `/amctimber selftest` | Run the built-in logic self-check (prints `PASS n/n`) |
| `/amctimber test …` | Deterministic QA hooks (`fell`, `break`, `attack`, `use`, `chop`) |

## 🔑 Permissions

| Node | Default | Meaning |
|---|---|---|
| `amctimber.use` | everyone | Trees topple for this player (revoke for vanilla breaking) |
| `amctimber.admin` | op | Manage / diagnose the plugin |

---

## ⚙️ Configuration highlights

Everything lives in a heavily-commented `config.yml`. A few of the knobs:

**Tool-tier scaling** — how big a tree each axe can fell (size in logs, `-1` = unlimited):

```yaml
tool-scaling:
  enabled: true
  too-weak-action: vanilla   # vanilla = break one block | cancel = nothing happens
  tiers:
    WOODEN: 12
    STONE: 30
    IRON: 120
    DIAMOND: 600
    NETHERITE: -1
```

**What counts as an axe:**

```yaml
axes:
  use-vanilla-tag: true
  extra-items: [NETHERITE_HOE]   # let a custom tool fell trees too
```

**Skill-XP bridge** (works with any engine — placeholders `%player% %skill% %amount%`):

```yaml
xp:
  mode: command
  per-log: 2
  skill: foraging
  command: "skillsadmin xp %player% %skill% %amount%"
  #   AuraSkills: "aureliumskills xp %player% %skill% %amount%"
  #   mcMMO:      "mcmmo addxp %player% Woodcutting %amount%"   (skill: Woodcutting)
```

**Durability cost, crush damage, animation timing, detection thresholds, debug verbosity** (`off/info/full`)
and **per-locale messages** (`messages.yml`, MiniMessage) are all configurable too.

---

## 🧵 Folia

AMCTimber is Folia-native (`folia-supported: true`). It uses Paper's region/global/entity scheduler API
directly — no external scheduler library — so the same jar runs correctly on Paper and Folia. On Folia,
felling work runs on the region thread that owns the tree.

---

## 🛠️ Building from source

```bash
git clone https://github.com/asketmc/AMCTimber.git
cd AMCTimber
mvn -B clean package
# -> target/AMCTimber-<version>.jar
```

Requires JDK 21. All dependencies resolve from public repositories (PaperMC, EngineHub, Glaremasters,
Maven Central). bStats is shaded and relocated; WorldGuard/Towny are `provided` (compile-only).

---

## 🤝 Contributing

Issues and PRs welcome on [GitHub](https://github.com/asketmc/AMCTimber/issues). The plugin ships a
console-safe self-check — run `/amctimber selftest` and keep it green.

## 📜 License

[GNU GPLv3](LICENSE). You are free to use, study, modify and redistribute it; derivative works must stay
open-source under the same license. You may **not** repackage it as a closed-source/paid plugin.

---

*Made with 🪓 for [asketmc.com](https://asketmc.com).*
