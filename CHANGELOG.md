# Changelog

All notable changes to AMCTimber are documented here. This project follows Semantic Versioning and the
Keep a Changelog format.

## [Unreleased]

### Added

- Publish each completed GitHub Release to Modrinth through a least-privilege, idempotent API handoff.
  The workflow downloads the exact signed GitHub Release JAR, verifies its checksum and tag ancestry,
  selects current supported Minecraft tags, renders polished Markdown release notes, and verifies the
  Modrinth API readback without exposing the publication token to build or repository code.

### Fixed

- Advertise every stable Minecraft 1.20.x version and Minecraft 1.21.x through 1.21.11 on Modrinth,
  while rejecting later game-version tags until compatibility is reviewed explicitly.

## [1.0.9] - 2026-07-16

### Fixed

- Publish release assets from the isolated signing job with an explicit repository scope, and enforce
  that requirement in the release-security regression gate.

## [1.0.8] - 2026-07-16

### Security

- Split release build/runtime smoke from signing and publication permissions, require release tags to
  resolve to commits on `main`, timestamp-pin the Paper API input, scope custom Maven repositories to
  owned group IDs, and document exact-tag Sigstore verification.
- Correct WorldGuard block-break and Towny wilderness authorization, pre-authorize landing destinations,
  preserve final cancellation/no-drop policy before commit, and attribute crush damage to the feller.
- Add global/per-player scan admission, elapsed/read/hook-call limits, atomic launch work admission,
  paced display/crush phases, and a CI regression gate for the audited runtime boundaries.
- Route log and canopy rewards through one fair per-tick durable dispatcher, with recovery capacity
  separated from live display/session capacity so unloaded records cannot starve normal gameplay; legacy
  actorless records fail closed under active protection and terminal journal retries retain ownership.

## [1.0.7] - 2026-07-10

### Fixed

- Fixed fallen log displays becoming nearly black when Minecraft sampled their light from an entity
  position inside the stump or terrain. Display entities now use per-block light anchors above the visible
  geometry without changing its rendered position or forcing full brightness.

### Notes

- Native sky/block lighting remains enabled, including day/night changes. Shader-pack rendering is
  client-controlled, so this fix removes the server-side invalid light-sampling position without claiming
  compatibility with every third-party shader pack.

## [1.0.6] - 2026-07-09

### Added

- Added an explicit felling lifecycle shared by falling sessions and landed trunks, with idempotent
  completion, expiry, failure, and cleanup paths.
- Added snapshot-checked world mutation with compensating rollback that restores only still-empty blocks.
- Added immutable per-fell config snapshots, world-aware active-cut keys, and one global entity budget for
  falling and landed trunk entities.
- Added validated atomic local journaling (`amctimber.pending-yield.v1`) for bounded recovery queues and
  planned-shutdown transfer of undelivered in-flight/trunk yield, without claiming an exactly-once
  transaction across world item spawning and filesystem acknowledgement.
- Added a Paper runtime-smoke workflow for startup, built-in selftest, and clean shutdown on Paper 1.20.6
  and the latest stable 1.21 release, with logs retained as workflow artifacts.
- Release publication now repeats those checks against the exact prepared release jar and records its
  SHA256 before signing and upload.

### Changed

- Narrowed the supported runtime claim to Paper, Purpur, and Pufferfish 1.20.6-1.21.x only.
- Protection integration errors now deny by default; operators must explicitly opt into an allow policy.
- QA command hooks are disabled by default and require the separate `amctimber.qa` permission when enabled.
- Rejected item spawns no longer advance yield accounting: completed trunks remain retryable and abnormal
  in-flight recovery enters a bounded retry queue. Rejected display teleports abort landing, while
  unexpected trunk-entity invalidation expires without granting unchopped yield.
- Documented the player-build detector as a conservative heuristic, not a guarantee.
- Runtime smoke is scoped to the exact workflow matrix and checks performed; it does not claim automated
  Purpur/Pufferfish coverage, every 1.21 patch, or gameplay-path E2E.

## [1.0.5] - 2026-07-09

### Added

- Added `SECURITY.md` and moderator-focused `docs/REVIEWER_NOTES.md`.
- Added a `Reviewer Evidence` workflow that publishes a reviewer evidence bundle with the release jar,
  SHA256 checksums, SBOMs, jar safety report, Maven test report, dependency report and runtime-surface
  report.
- Added a runtime-surface gate for process execution, native loading, dynamic classloading, hidden
  reflection and runtime network API references.
- Added JaCoCo coverage, P0 tagged tests, a P0 test matrix and PIT mutation testing for the current
  pure-core QA gate.

### Changed

- Disabled the XP command bridge by default; operators must explicitly enable it after choosing a real
  skill-plugin command.

### Fixed

- Enforced `animation.max-display-entities` for logs as well as leaves.
- Skipped crush damage at protected landing points and against tamed/leashed mobs and villagers.
- Routed admin QA test hooks through the server scheduler.
- Added shutdown cleanup for in-flight falls so mid-animation disables drop the expected log yield instead
  of silently losing the tree.
- Increased giant-trunk hitbox coverage and fixed non-stump durability charging.

## [1.0.4] - 2026-07-09

### Changed

- Removed the disabled bStats stub and shaded dependency so the release jar has no bundled runtime
  libraries.
- Replaced registry-based Semgrep scanning with a pinned engine and committed low-noise local rules.
- Added release dry-run validation for version consistency, jar safety, checksums, and SPDX/CycloneDX
  SBOM generation before publishing a GitHub Release.

### Fixed

- Fixed the OpenSSF Scorecard action pin to use the commit object for `v2.4.3`, not the annotated tag
  object.
- Hardened release upload/signing so existing release assets are not overwritten and only primary release
  evidence artifacts are signed.
- Added upper bounds for expensive config values and ensured active fell slots are released on startup or
  landing failures.

## [1.0.3] - 2026-07-09

### Fixed

- Switched SPDX SBOM generation to `spdx-maven-plugin` so the SPDX artifact is based on Maven project
  and dependency metadata instead of a shallow jar file scan.

## [1.0.2] - 2026-07-09

### Fixed

- Fixed SPDX SBOM generation to scan the built plugin jar directly instead of the release asset directory,
  producing more useful package/file evidence for release verification.

## [1.0.1] - 2026-07-09

### Changed

- Added pinned GitHub Actions workflows for CI, CodeQL, Dependency Review, OSV Scanner, Semgrep,
  OpenSSF Scorecard, SBOM generation, and release security evidence.
- Moved release jar distribution out of the git source tree and into GitHub Release assets.

### Security

- Added release checksums, SPDX/CycloneDX SBOM generation, Sigstore/cosign keyless signing, and GitHub
  artifact attestations.
- Added a jar safety gate that fails CI/release if the plugin jar contains native binaries, scripts,
  nested jars, or shaded dependency signature metadata.

## [1.0.0] - 2026-06-19

First public release, extracted and generalised from the internal asketmc build.

### Added

- Valheim-style tree felling: cut a tree's base and the section above topples in a smooth,
  client-interpolated fall, leaving a stump; the downed trunk must be re-chopped for its logs.
- Tool-tier scaling: each axe tier caps the tree size it can fell; configurable.
- Configurable axe items with `axes.use-vanilla-tag` and `axes.extra-items`.
- Pluggable skill-XP bridge via configurable console command.
- Configurable durability cost per fell and per chop.
- Crush damage for tree landings.
- Treehouse/log-cabin guard and species-aware spreading.
- Debug levels: `off`, `info`, `full`.
- Externalised EN/RU messages in `messages.yml`.
- Compatibility with Paper, Purpur, and Pufferfish.
- bStats metrics hook, disabled while the plugin id is `0`.
- `/amctimber selftest`.
- JUnit 5 server-free unit test suite.

[1.0.9]: https://github.com/asketmc/AMCTimber/releases/tag/v1.0.9
[1.0.8]: https://github.com/asketmc/AMCTimber/releases/tag/v1.0.8
[1.0.7]: https://github.com/asketmc/AMCTimber/releases/tag/v1.0.7
[1.0.6]: https://github.com/asketmc/AMCTimber/releases/tag/v1.0.6
[1.0.5]: https://github.com/asketmc/AMCTimber/releases/tag/v1.0.5
[1.0.4]: https://github.com/asketmc/AMCTimber/releases/tag/v1.0.4
[1.0.3]: https://github.com/asketmc/AMCTimber/releases/tag/v1.0.3
[1.0.2]: https://github.com/asketmc/AMCTimber/releases/tag/v1.0.2
[1.0.1]: https://github.com/asketmc/AMCTimber/releases/tag/v1.0.1
[1.0.0]: https://modrinth.com/plugin/amctimber/version/q637itJU
