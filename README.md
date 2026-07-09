# 🌲 AMCTimber

[![CI](https://github.com/asketmc/AMCTimber/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/asketmc/AMCTimber/actions/workflows/build.yml)
[![CodeQL](https://github.com/asketmc/AMCTimber/actions/workflows/codeql.yml/badge.svg?branch=main)](https://github.com/asketmc/AMCTimber/actions/workflows/codeql.yml)
[![OSV Scanner](https://github.com/asketmc/AMCTimber/actions/workflows/osv-scanner.yml/badge.svg?branch=main)](https://github.com/asketmc/AMCTimber/actions/workflows/osv-scanner.yml)
[![Semgrep](https://github.com/asketmc/AMCTimber/actions/workflows/semgrep.yml/badge.svg?branch=main)](https://github.com/asketmc/AMCTimber/actions/workflows/semgrep.yml)
[![OpenSSF Scorecard](https://github.com/asketmc/AMCTimber/actions/workflows/scorecard.yml/badge.svg?branch=main)](https://github.com/asketmc/AMCTimber/actions/workflows/scorecard.yml)
[![SBOM](https://github.com/asketmc/AMCTimber/actions/workflows/sbom.yml/badge.svg?branch=main)](https://github.com/asketmc/AMCTimber/actions/workflows/sbom.yml)
[![Reviewer Evidence](https://github.com/asketmc/AMCTimber/actions/workflows/reviewer-evidence.yml/badge.svg?branch=main)](https://github.com/asketmc/AMCTimber/actions/workflows/reviewer-evidence.yml)
[![Release Security](https://github.com/asketmc/AMCTimber/actions/workflows/release.yml/badge.svg)](https://github.com/asketmc/AMCTimber/actions/workflows/release.yml)
[![License: GPLv3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

**Valheim-style tree felling for Paper, Purpur, Pufferfish & Folia (MC 1.20.6 → 1.21.x).**

Chop the base of a tree and the whole thing **topples over** — a smooth, client-interpolated fall that
leaves a stump behind, just like Valheim. The downed trunk lies on the ground and has to be **chopped
again** for its logs, so harvesting a forest *feels* like work. Giants come crashing down and **crush
whatever is standing where they land.**

No client mod. No resource pack. No required external runtime plugins. One small jar with no shaded
runtime libraries.

> **Inspired by survival games where trees actually fall — and can flatten you.** AMCTimber is a custom,
> feature-rich mechanic from **[asketmc](https://asketmc.com)** — a Survival RPG server (CIS & EU) where
> falling trees are one layer of a deeper survival experience, alongside temperature, fishing, dynamic
> weather events and more custom mechanics. Come play on the server it was built for →
> **[asketmc.com](https://asketmc.com)**
> Discord: https://discord.gg/2MuA3Nv

---

## ✨ Features

- **Whole-tree topple** — a real falling animation using vanilla Display entities (no lag-spawning of
  blocks, no client mod). Trees fall *away* from the player.
- **Stump + downed trunk** — cut anywhere on the trunk; everything above topples and the rest stays as a
  stump. The fallen trunk must be chopped again for its logs (reduced yield — you have to finish the job).
- **Tool-tier scaling** — a wooden axe only fells saplings; you need a **diamond/netherite axe to bring
  down a 2×2 jungle giant**. Fully configurable per tier.
- **Crush damage** — stand where a giant lands and it flattens you (size-scaled, capped, PvP-safe by default;
  protected landing points, tamed/leashed mobs, and villagers are skipped).
- **Your builds are safe** — a "tree" touching *any* player-placed block (planks, fences, glass, beds,
  stairs, …) is treated as a structure and **never auto-fells**, so you'll never accidentally topple your
  house or treehouse. Sneak-to-bypass for builders, plus optional **WorldGuard region** & **Towny claim**
  respect on top.
- **Species-aware** — felling never spreads across wood types; one oak won't drag a neighbouring birch down.
- **Skill-XP bridge** — optionally award XP to *any* skill plugin via an explicitly enabled configurable
  command (AuraSkills, mcMMO, a native engine, …) with **zero dependency**.
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

> Built against the 1.20.6 API and intended for 1.20.6 through current 1.21.x Paper-family servers.
> Public CI is server-free Maven/JUnit verification; operators can run `/amctimber selftest` on a live
> server to verify runtime registry-backed paths.

---

## 📦 Installation

1. Download `AMCTimber-x.y.z.jar` from the [Modrinth page](https://modrinth.com/plugin/amctimber) or
   [GitHub Releases](https://github.com/asketmc/AMCTimber/releases).
2. Drop it into your server's `plugins/` folder.
3. Restart the server.
4. Edit `plugins/AMCTimber/config.yml` and `messages.yml`, then `/amctimber reload`.

Release assets are accompanied by checksums, SPDX/CycloneDX SBOMs, Sigstore bundles, GitHub artifact
attestations and a jar safety report. See [release verification](docs/VERIFY_RELEASE.md).

For marketplace moderators and server administrators, see [reviewer notes](docs/REVIEWER_NOTES.md).

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
  enabled: true
  mode: command
  per-log: 2
  skill: foraging
  command: "skillsadmin xp %player% %skill% %amount%"
  #   AuraSkills: "aureliumskills xp %player% %skill% %amount%"
  #   mcMMO:      "mcmmo addxp %player% Woodcutting %amount%"   (skill: Woodcutting)
```

**Durability cost, crush damage, animation timing, detection thresholds, debug verbosity** (`off/info/full`)
and **per-locale messages** (`messages.yml`, MiniMessage) are all configurable too.

### Operational Notes

Only the player's initial cut is a normal `BlockBreakEvent`. The rest of a detected tree is removed by
AMCTimber after the all-or-nothing protection check, so rollback/logging plugins may not record every
toppled log or leaf as an individual player block break.

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
mvn -B -ntp clean verify
# -> target/AMCTimber-<version>.jar
```

Requires JDK 21. All dependencies resolve from public repositories (PaperMC, EngineHub, Glaremasters,
Maven Central). WorldGuard/Towny are `provided` (compile-only) optional integrations and are not shaded
into the release jar.

### Tests

```bash
mvn -B -ntp clean verify
```

Automated layers:
- **Unit tests** (`src/test/java`, JUnit 5) — server-free checks over the pure logic: yield/XP/hits/
  durability/crush maths, axe-tier gating, species & leaf matching, fall direction, topple transforms,
  trunk shrink and the progress bar. Fast, and run on every push by CI (`mvn verify` runs them).
- **P0 tagged tests** — selected highest-risk server-free tests run again in the `verify` phase and are
  mapped in [docs/P0_TEST_MATRIX.md](docs/P0_TEST_MATRIX.md).
- **Coverage and mutation gates** — JaCoCo reports full project coverage and enforces >=80% line /
  >=70% branch coverage for the current pure-core gate (`TimberConfig`, `Tools`). PIT mutation testing
  runs for that same pure-core target with a >=70% mutation threshold.
- **Runtime self-check** — `/amctimber selftest` on a live server additionally exercises the
  registry-backed paths (Tag-based log/leaf/axe detection) that require a running server.

### Supply-chain checks

CI and release automation provide evidence for:
- Maven verify and jar artifact upload.
- Release dry-run validation for version consistency, jar safety, checksums and SBOMs before publishing.
- CodeQL, Dependency Review, Dependabot, OSV Scanner, Semgrep and OpenSSF Scorecard.
- SPDX and CycloneDX SBOM generation.
- Release checksums, Sigstore/cosign keyless signatures and GitHub artifact attestations.
- Jar safety checks for native binaries, scripts, nested jars and shaded signature metadata.
- Reviewer evidence artifacts containing the release jar, SHA256 checksums, SBOMs, jar safety report,
  Maven test report, dependency report and runtime-surface report.
- QA report artifacts containing JaCoCo coverage, PIT mutation reports, Surefire reports, P0-only test
  reports and the P0 test matrix.

These controls support verification; they are not a certification or external audit.

---

## 🤝 Contributing

Issues and PRs welcome on [GitHub](https://github.com/asketmc/AMCTimber/issues). Add or update a JUnit
test under `src/test/java` for any logic change (`mvn test`), and keep the runtime `/amctimber selftest`
green too.

## 📜 License

[GNU GPLv3](LICENSE). You are free to use, study, modify and redistribute it; derivative works must stay
open-source under the same license. You may **not** repackage it as a closed-source/paid plugin.

---

*Made with 🪓 for [asketmc.com](https://asketmc.com).*
