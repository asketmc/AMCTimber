# Security

AMCTimber is a public-source Minecraft server plugin. This document describes the project's security
posture and reporting process. It is not a formal third-party audit or certification.

## Security Posture

- No obfuscation.
- No native binaries in release jars.
- No bundled shell scripts in release jars.
- No nested jars in release jars.
- No runtime downloads or auto-updater.
- No required external services.
- No undisclosed telemetry.
- No shaded runtime libraries.
- Release jars are built by GitHub Actions from tagged source.
- Release jars include SHA256 checksums, SPDX and CycloneDX SBOMs, Sigstore bundles, GitHub artifact
  attestations, and a jar safety report.

The jar safety report is a heuristic artifact-hygiene check for known file and metadata patterns. A
passing report is useful evidence, but it does not prove that a jar is harmless or certified secure.

## Runtime Safety Boundaries

- Supported runtime claims are limited to Paper, Purpur, and Pufferfish 1.20.6-1.21.x.
- Player-build detection is conservative but heuristic and cannot guarantee that every custom structure
  will be rejected as a tree. Use claim/protection plugins for important builds.
- The initial break is prepared without mutation at HIGHEST and committed only after consuming the final
  ordinary-priority cancellation and drop state at MONITOR.
- WorldGuard and Towny use operation-specific source-break, placement, interaction, item-drop, and combat
  decisions instead of treating every sink as block destruction; wilderness is not an authorization bypass.
- Source nodes and conservative transformed display/hitbox footprints are authorized before mutation.
  Placement, actual trunk use, and deterministic item delivery are rechecked; persisted actor UUIDs keep
  delayed delivery fail-closed. Crush damage is cancellable and identifies the felling player as its source.
- Accepted fells use immutable config snapshots, world-aware keys, an explicit lifecycle, snapshot-checked
  mutation rollback, scan/deadline/hook admission, a global resident-entity budget, and a separate
  per-tick work governor for atomic launch, entity phases, and crush processing.
- Crush query/target counts and plugin-side candidate visits are globally paced. Paper's individual spatial
  query is a non-preemptible main-thread API unit, so the elapsed budget is a between-unit bound rather than
  a guarantee that an already-running dense-world query can be interrupted.
- All log and canopy item creation passes through one bounded round-robin dispatcher. Rejected stacks are
  not acknowledged; unloaded/rejected records remain durable without consuming a live session slot.
- The local recovery journal validates identifiers, actor UUIDs, coordinates, supported timber-yield materials,
  amounts, duplicate IDs, and a configurable hard-capped ceiling independent of live animation work.
- Pending recovery entries use a validated, atomically replaced local file with schema
  `amctimber.pending-yield.v2` (with v1 migration). Actorless legacy entries remain dormant whenever a
  protection hook is active, and terminal handoffs cannot deliver until their staged record is checkpointed;
  planned shutdown retains remaining yield before cleanup.
- The journal does not make a world item spawn and filesystem acknowledgement one atomic operation, and
  non-persisted in-world trunk state can still be lost in a process/host crash. It is not an exactly-once
  durability guarantee.
- Unexpected entity invalidation expires the trunk without granting its unchopped yield; non-persistent
  trunk entities are not a cross-chunk persistence mechanism.
- Deterministic QA hooks are disabled by default and use the separate `amctimber.qa` permission.
- CI runs `scripts/check-runtime-security.py` to prevent the audited operation mappings and pacing choke
  points from silently regressing. This focused gate and the repository tests are evidence, not a formal
  security certification.

## Sensitive Information

Do not post private server addresses, console logs with secrets, tokens, credentials, paid plugin keys,
or exploit details in public issues.

## Reporting Vulnerabilities

Use [GitHub private vulnerability reporting](https://github.com/asketmc/AMCTimber/security/advisories/new),
which is enabled for this repository. Do not publish exploit details or sensitive server data in a public
issue.

When reporting, include:

- AMCTimber version.
- Server software and Minecraft version.
- Minimal reproduction steps.
- Whether optional integrations such as WorldGuard or Towny are installed.
- Any relevant stack trace with secrets removed.

## Supported Versions

Security fixes target the latest public release line. Older versions may receive fixes only when the
impact and maintenance cost justify a backport.

