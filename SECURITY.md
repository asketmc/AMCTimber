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
- Optional WorldGuard and Towny hook errors deny block mutation by default. An operator can explicitly
  configure an allow-on-error policy.
- Accepted fells use immutable config snapshots, world-aware keys, an explicit lifecycle, a global entity
  budget, and snapshot-checked mutation rollback.
- Rejected log-item delivery retains the ledger in a choppable trunk or a globally bounded in-memory
  retry queue; rejected stacks are not acknowledged as delivered.
- Pending delivery retains its global reservation. Persistent rejection therefore fails closed at the
  configured live-session cap instead of creating unbounded recovery state. The local journal validates
  identifiers, coordinates, materials, amounts, duplicate IDs, and a 4096-entry hard ceiling.
- Pending recovery entries use a validated, atomically replaced local file with schema
  `amctimber.pending-yield.v1`; planned shutdown journals remaining in-flight/trunk yield before cleanup.
- The journal does not make a world item spawn and filesystem acknowledgement one atomic operation, and
  non-persisted in-world trunk state can still be lost in a process/host crash. It is not an exactly-once
  durability guarantee.
- Unexpected entity invalidation expires the trunk without granting its unchopped yield; non-persistent
  trunk entities are not a cross-chunk persistence mechanism.
- Deterministic QA hooks are disabled by default and use the separate `amctimber.qa` permission.

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

