# 🌲 AMCTimber

[![CI](https://github.com/asketmc/AMCTimber/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/asketmc/AMCTimber/actions/workflows/build.yml)
[![CodeQL](https://github.com/asketmc/AMCTimber/actions/workflows/codeql.yml/badge.svg?branch=main)](https://github.com/asketmc/AMCTimber/actions/workflows/codeql.yml)
[![OSV Scanner](https://github.com/asketmc/AMCTimber/actions/workflows/osv-scanner.yml/badge.svg?branch=main)](https://github.com/asketmc/AMCTimber/actions/workflows/osv-scanner.yml)
[![Semgrep](https://github.com/asketmc/AMCTimber/actions/workflows/semgrep.yml/badge.svg?branch=main)](https://github.com/asketmc/AMCTimber/actions/workflows/semgrep.yml)
[![OpenSSF Scorecard](https://github.com/asketmc/AMCTimber/actions/workflows/scorecard.yml/badge.svg?branch=main)](https://github.com/asketmc/AMCTimber/actions/workflows/scorecard.yml)
[![SBOM](https://github.com/asketmc/AMCTimber/actions/workflows/sbom.yml/badge.svg?branch=main)](https://github.com/asketmc/AMCTimber/actions/workflows/sbom.yml)
[![Reviewer Evidence](https://github.com/asketmc/AMCTimber/actions/workflows/reviewer-evidence.yml/badge.svg?branch=main)](https://github.com/asketmc/AMCTimber/actions/workflows/reviewer-evidence.yml)
[![Release Security](https://github.com/asketmc/AMCTimber/actions/workflows/release.yml/badge.svg)](https://github.com/asketmc/AMCTimber/actions/workflows/release.yml)
[![Modrinth](https://img.shields.io/modrinth/dt/ri1Ibgnf?logo=modrinth&label=Modrinth)](https://modrinth.com/plugin/amctimber)
[![License: GPLv3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

**Valheim-style tree felling for Paper, Purpur, and Pufferfish on Minecraft 1.20.6-1.21.11 only.**

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
- **Conservative player-build guard** — a scan touching common construction blocks (planks, fences,
  glass, beds, stairs, …) is rejected as a structure. This is a heuristic, not a guarantee for every
  custom build or treehouse; protect important areas with **WorldGuard** or **Towny** and test local rules.
- **Species-aware** — felling never spreads across wood types; one oak won't drag a neighbouring birch down.
- **Skill-XP bridge** — optionally award XP to *any* skill plugin via an explicitly enabled configurable
  command (AuraSkills, mcMMO, a native engine, …) with **zero dependency**.
- **Atmosphere** — layered creak / fall / impact sounds, dust lines, drifting tinted leaves, canopy crash.
- **Native display lighting** — every animated block samples light from an anchor above its visible model,
  avoiding terrain-blackened trunks without forced fullbright; third-party shader rendering remains
  client-dependent.
- **i18n** — English + Russian out of the box, MiniMessage-formatted, fully editable; item names localise
  on each client automatically.
- **Bounded runtime** — every fell has an explicit lifecycle, world-aware duplicate key, immutable config
  snapshot, and reservations in separate live-entity, queued-work, and durable-recovery budgets.
- **Ephemeral entities** — spawned entities are non-persistent, tagged, capped, swept, and removed during
  planned shutdown recovery. Startup cleanup handles tagged leftovers after an unclean stop.

---

## ✅ Compatibility

| | |
|---|---|
| **Server software** | **Paper, Purpur, Pufferfish only** |
| **Minecraft** | **1.20.6-1.21.11** (one jar) |
| **Java** | 21+ |
| **Spigot** | ❌ not supported (the plugin uses modern Display/Interaction entities + Adventure) |
| **Folia** | ❌ not supported |
| **Dependencies** | none required; **WorldGuard** & **Towny** are optional; hook errors deny by default |

> Built against the Paper 1.20.6 API and intended only for Paper, Purpur, and Pufferfish 1.20.6-1.21.11.
> `Paper Runtime Smoke` starts Paper 1.20.6 and the latest stable 1.21 release, runs the built-in selftest,
> and checks clean shutdown. It does not cover Purpur, Pufferfish, every 1.21 patch, or gameplay E2E.
> The evidence-backed [configuration matrix](docs/CONFIGURATION_MATRIX.md) separates supported, advertised,
> system-tested, gameplay-E2E, and post-deployment coverage.

---

## 📦 Installation

1. Download `AMCTimber-x.y.z.jar` from the [Modrinth page](https://modrinth.com/plugin/amctimber) or
   [GitHub Releases](https://github.com/asketmc/AMCTimber/releases).
2. Drop it into your server's `plugins/` folder.
3. Restart the server.
4. Edit `plugins/AMCTimber/config.yml` and `messages.yml`, then `/amctimber reload`.

Release assets are accompanied by checksums, SPDX/CycloneDX SBOMs, Sigstore bundles, GitHub artifact
attestations and a heuristic jar-hygiene report. See [release verification](docs/VERIFY_RELEASE.md).

For marketplace moderators and server administrators, see [reviewer notes](docs/REVIEWER_NOTES.md).

---

## ⌨️ Commands

`/amctimber` (aliases: `/timber`, `/amct`) — administrative subcommands require `amctimber.admin`.

| Subcommand | Description |
|---|---|
| `/amctimber reload` | Reload `config.yml` + `messages.yml` |
| `/amctimber info` | Live status (active fells, trunks, platform, key settings) |
| `/amctimber selftest` | Run the built-in logic self-check (prints `PASS n/n`) |
| `/amctimber test …` | Deterministic QA hooks; disabled by default and separately gated by `amctimber.qa` |

## 🔑 Permissions

| Node | Default | Meaning |
|---|---|---|
| `amctimber.use` | everyone | Trees topple for this player (revoke for vanilla breaking) |
| `amctimber.admin` | op | Manage / diagnose the plugin |
| `amctimber.qa` | op | Use QA hooks, when `qa.commands-enabled` is explicitly enabled |

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

**Protection and QA defaults:**

```yaml
detection:
  protection-error-policy: deny   # deny | allow
  max-attempt-micros: 10000
  max-protection-hook-calls: 4096
  max-attempts-per-tick: 3
  attempt-cooldown-ticks: 4

runtime-work:
  launch-block-ops-per-tick: 10000
  entity-operations-per-tick: 1024
  crush-queries-per-tick: 16
  yield-delivery-steps-per-tick: 8
  max-recovery-records: 4096

qa:
  commands-enabled: false
```

**Durability cost, crush damage, animation timing, detection thresholds, debug verbosity** (`off/info/full`)
and **per-locale messages** (`messages.yml`, MiniMessage) are all configurable too.

### Runtime Safety Model

- A fell moves through explicit prepared, falling, landing, landed, completion, expiry, or failure states.
- Tree blocks are checked against the scan snapshot before mutation. Partial removal uses a compensating
  rollback journal that restores only still-empty positions, so newer world changes are not overwritten.
- The HIGHEST block-break handler prepares a read-only plan. AMCTimber consumes cancellation and no-drop
  policy at MONITOR before committing, so later ordinary-priority policy listeners can veto the fell
  without secondary world, entity, durability, or synthetic-yield side effects.
- Each accepted fell keeps the immutable config snapshot it started with; reloads affect new work only.
- Active-cut identity includes the world UUID and block coordinates, avoiding cross-world key collisions.
- Scan admission is limited globally and per player. Every scan/protection stage also has a wall-clock,
  live-read, and external-hook-call budget; exhaustion declines special felling and preserves vanilla
  single-block behavior.
- One global resident-entity budget and a separate per-tick work governor reserve atomic launch work
  before mutation, then pace transform/landing phases and crush queries/targets across sessions.
  The elapsed crush budget is checked between dispatcher units; Paper exposes each spatial query as one
  non-preemptible main-thread operation, so entity-density limits remain an operator/server responsibility.
- WorldGuard maps source break, landing, trunk interaction, item-drop, PvP, and mob-damage operations to
  `BLOCK_BREAK`, `BUILD`, `INTERACT`, `ITEM_DROP`, `PVP`, and `DAMAGE_ANIMALS`. Towny independently maps
  them to `DESTROY`, `BUILD`, `SWITCH`, and `ITEM_USE`, including wilderness. Hook errors deny by default.
- Conservative transformed display cubes, complete interaction hitboxes, and deterministic item sinks are
  authorized before source removal and rechecked at landing, trunk use, and delivery. Crush damage is
  attributed to the felling player so cancellable combat-policy listeners see the actual actor and target.
- All log and canopy item creation shares a round-robin per-tick stack budget. Rejected or unloaded
  deliveries retain their exact ledger without acknowledging a stack that did not enter the world.
- Durable recovery uses its own configurable, hard-capped capacity instead of holding a live display/session
  reservation; restored records survive a lower reload limit while new reservations fail closed.
- Pending queue entries are atomically stored in
  `plugins/AMCTimber/pending-yields.properties` (`amctimber.pending-yield.v2`, with v1 migration). New records
  retain the actor UUID so item-drop authorization can be rechecked. Actorless v1 records remain dormant
  while a protection integration is active instead of receiving implicit authorization. Terminal handoffs
  are withheld from delivery until an atomic journal checkpoint succeeds; planned shutdown stages remaining
  in-flight/trunk yield before cleanup, and startup validates and resumes durable records.
- This local journal does not make the world-item spawn plus file acknowledgement one atomic transaction,
  and an in-world trunk that disappears in a process/host crash may still be lost. It is recovery evidence,
  not an exactly-once durability guarantee.
- Unexpected entity invalidation (including an unloaded non-persistent trunk) expires the trunk without
  granting unchopped yield. This avoids a chunk-unload yield bypass; it is not cross-chunk persistence.
- The player-build detector is intentionally conservative but heuristic; protection plugins remain the
  appropriate boundary for valuable builds.

Only the player's initial cut is a normal `BlockBreakEvent`. The rest of a detected tree is removed by
AMCTimber after the all-or-nothing protection check, so rollback/logging plugins may not record every
toppled log or leaf as an individual player block break.

---

## 🛠️ Building from source

```bash
git clone https://github.com/asketmc/AMCTimber.git
cd AMCTimber
mvn -B -ntp clean verify
# -> target/AMCTimber-<version>.jar
```

Requires JDK 21 and Maven 3.9 or newer. Project-controlled Maven Resolver filters route each non-Central
group ID only to PaperMC, Spigot, EngineHub, or Glaremasters and ignore repositories injected by dependency
POMs. WorldGuard/Towny are `provided` (compile-only) optional integrations and are not shaded into the
release jar.

### Tests

```bash
mvn -B -ntp clean verify
```

Automated layers:
- **Unit tests** (`src/test/java`, JUnit 5) — server-free checks over the pure logic: yield/XP/hits/
  durability/crush maths, axe-tier gating, species and leaf matching, fall direction, topple transforms,
  admission/deadline budgets, work pacing, recovery ownership, landing footprint, trunk shrink and the
  progress bar. Fast, and run on every push by CI (`mvn verify` runs them).
- **P0 tagged tests** — selected highest-risk server-free tests run again in the `verify` phase and are
  mapped in [docs/P0_TEST_MATRIX.md](docs/P0_TEST_MATRIX.md).
- **Coverage and mutation gates** — JaCoCo reports full project coverage and enforces >=80% line /
  >=70% branch coverage for the scoped stable core (`TimberConfig`, `Tools`, `FellLifecycle`,
  `EntityBudget`, `YieldLedger`). PIT mutation testing targets `TimberConfig` and `Tools` with a >=70%
  mutation threshold. These scoped gates are not presented as 80% whole-project coverage.
- **Runtime self-check** — `/amctimber selftest` on a live server additionally exercises the
  registry-backed paths (Tag-based log/leaf/axe detection) that require a running server. The committed
  `Paper Runtime Smoke` workflow runs startup, selftest, and clean shutdown on Paper 1.20.6 and the latest
  stable 1.21 release; it is not Purpur/Pufferfish or gameplay-path evidence.
- **Post-deployment runtime check** — `scripts/verify-published-release-runtime.sh` downloads the public
  release JAR and checksum, then repeats both Paper endpoint smokes. It emits a hash-bound JSON receipt;
  this remains a system smoke and is not labelled gameplay E2E.
- **Local gameplay E2E** — `scripts/e2e-gameplay.ps1` drives real VCraftQABot players through default-deny
  QA guards, vanilla sneak bypass, player-build rejection, oak and 2×2 jungle felling, exact yield,
  entity cleanup, planned-restart journal recovery, and a three-player concurrency/TPS check. It runs
  locally on Paper, Purpur, or Pufferfish and emits hash-bound fail-closed JSON receipts; it is intentionally
  not scheduled on hosted GitHub runners. The optional test-only `qa/event-policy-fixture` adds real
  late cancellation and no-drop event-policy assertions without entering the release JAR.
  Concurrent setup may retry once per tree only after an exact, logged `attempt-time-budget` fail-closed
  fallback; the receipt exposes the retry count and every other rejection remains a failure.
- **Configuration coverage contract** — [docs/CONFIGURATION_MATRIX.md](docs/CONFIGURATION_MATRIX.md) and
  its machine-readable JSON distinguish supported, advertised, tested, and explicitly missing rows.
- **Audited boundary regression gate** — `scripts/check-runtime-security.py` fails CI if the concrete
  WorldGuard/Towny operation mapping, no-drop/final-cancellation handoff, attributed crush damage,
  landing authorization ordering, or paced item-delivery choke point regresses.

### Supply-chain checks

CI and release automation provide evidence for:
- Maven verify and jar artifact upload.
- Release dry-run validation for version consistency, heuristic jar hygiene, checksums and SBOMs before
  publishing.
- CodeQL, Dependency Review, Dependabot, OSV Scanner, Semgrep and OpenSSF Scorecard.
- SPDX and CycloneDX SBOM generation.
- Release checksums, Sigstore/cosign keyless signatures and GitHub artifact attestations.
- Exact release-jar startup/selftest/shutdown smoke on Paper 1.20.6 and latest stable 1.21, with the jar
  SHA256 recorded in retained workflow evidence before signing and upload.
- Heuristic jar-hygiene checks for native binaries, scripts, nested jars and shaded signature metadata.
- Reviewer evidence artifacts containing the release jar, SHA256 checksums, SBOMs, jar safety report,
  Maven test report, dependency report and runtime-surface report.
- QA report artifacts containing JaCoCo coverage, PIT mutation reports, Surefire reports, P0-only test
  reports and the P0 test matrix.

These controls support verification; they are not a certification, external audit, or guarantee that an
artifact is safe.

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
