# Reviewer Notes

AMCTimber adds Valheim-style tree felling for Paper, Purpur, and Pufferfish 1.20.6-1.21.x only. These
notes are written for marketplace moderators and server administrators who want a concise review checklist.

## Runtime Behavior

- Listens to block break and interaction events.
- Detects natural trees with global/per-player admission, elapsed/read caps, and configurable limits. The player-build guard is a
  conservative heuristic, not a guarantee for every custom structure.
- Spawns non-persistent Display/Interaction entities for the falling-tree visual.
- Removes or replaces only detected tree/trunk blocks.
- Stores temporary downed-trunk state in memory only.
- Uses operation-specific WorldGuard/Towny mappings for source breaks, destination entities, trunk use,
  item delivery, and combat; wilderness is checked and hook errors deny unless explicitly configured otherwise.
- Uses an explicit felling lifecycle, immutable per-fell config snapshots, world-aware active-cut keys,
  one global resident-entity budget, and an independent durable-recovery budget.
- Prepares block breaks without mutation, consumes final ordinary-priority cancellation/drop policy before
  commit, and attributes crush damage to the feller for downstream combat-policy cancellation.
- Uses one global per-tick work governor for atomic launch admission, display phases, crush work, and
  round-robin log/canopy item delivery. Unloaded recovery does not reserve a live session.
- Preflights snapshotted block data and uses compensating rollback for partial mutation without
  overwriting non-air blocks changed by another actor.
- Retains rejected log delivery in the trunk or a bounded retry queue. Queue state and planned-shutdown
  transfers use the validated, atomically replaced local `amctimber.pending-yield.v2` file (v1 migrates).
  New entries retain their actor for delivery-time item-drop authorization; actorless v1 entries stay
  dormant while protection is active. Terminal transfers remain non-deliverable until their staged journal
  record is atomically checkpointed. This is not an
  exactly-once transaction with world item spawning and does not persist every in-world trunk across a
  process or host crash.
- Keeps deterministic QA hooks disabled by default and gates them with the separate `amctimber.qa`
  permission when enabled.
- Provides a disabled-by-default, server-operator configured XP command bridge; the command template is
  trusted admin config.
- Only the player's initial cut is a normal `BlockBreakEvent`; the remaining detected tree blocks are
  removed by AMCTimber after protection checks, so rollback plugins may not log every toppled block as a
  separate player break.

## No Hidden Payloads

- No obfuscation.
- No native libraries.
- No bundled shell scripts.
- No nested jars.
- No runtime downloads.
- No auto-updater.
- No remote command/control.
- No classloader tricks.
- No reflection-heavy hidden logic.
- No telemetry in the current release.

## Verification Evidence

Use the GitHub Release jar, not a jar committed under `dist/` or built locally without verification.

Recommended moderator package:

- Public source: <https://github.com/asketmc/AMCTimber>
- GitHub Release jar: `AMCTimber-x.y.z.jar`
- SHA256: `SHA256SUMS.txt`
- Build/test evidence: GitHub Actions `CI` run (`mvn -B -ntp clean verify`)
- Heuristic jar-hygiene report: `jar-safety-report.txt`
- SBOMs: `sbom.spdx.json` and `sbom.cdx.json`
- Signature evidence: `*.sigstore.json`
- Provenance evidence: GitHub artifact attestation
- Reviewer evidence bundle: GitHub Actions artifact `reviewer-evidence`
- QA reports: GitHub Actions artifact `qa-reports` with JaCoCo coverage, PIT mutation reports, Surefire
  reports, P0-only test reports, and the P0 matrix

VirusTotal can be included as an additional hash-based signal for the exact release jar SHA256. It should
not be described as proof of safety.

The `Paper Runtime Smoke` workflow starts Paper 1.20.6 and the latest stable 1.21 release, runs the built-in
selftest, checks clean shutdown, and uploads exact-build logs. This is implemented evidence only for those
Paper runs and checks; it does not establish Purpur/Pufferfish behavior, every 1.21 patch, or gameplay E2E.
Manual `/amctimber selftest` logs likewise apply only to the exact server, jar, and run that produced them.

## Manual Review Notes

The release jar safety gate heuristically fails on known native binary, script, nested jar, shaded
signature metadata, and `plugin.yml` count patterns. A pass does not guarantee that the jar is harmless.

The runtime-surface gate reports and fails on configured process execution, native library loading,
dynamic classloading, hidden reflection API, and runtime network API patterns in `src/main/java`. Review
its rules and allowlists when interpreting the result.

The focused runtime-security invariant gate also checks the audited WorldGuard/Towny operation mapping,
drop/cancellation ordering, landing authorization ordering, attributed damage sink, and single paced item
delivery choke point. It is deliberately narrow and does not replace live integration testing.

These checks provide verification evidence. They do not replace manual code review or a formal security
audit.

## Marketplace Security Text

Suggested text for Modrinth, Spigot, Hangar, or server-admin review:

```text
This plugin is public-source and not obfuscated. Release jars are built by GitHub Actions from tagged
source. Each release includes SHA256 checksums, SPDX/CycloneDX SBOMs, Sigstore bundles, GitHub artifact
attestations, and a heuristic jar-hygiene report.

The jar-hygiene gate checks for known native binary, script, nested jar, and shaded signature metadata
patterns. Passing this heuristic does not prove an artifact safe. The plugin does not use native code,
runtime downloads, auto-updaters, telemetry, or hidden external services. Optional integrations are
WorldGuard and Towny, whose errors deny by default.

These checks are verification evidence, not a certification, safety guarantee, or formal third-party
security audit.
```
