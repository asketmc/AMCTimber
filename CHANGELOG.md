# Changelog

All notable changes to AMCTimber are documented here. This project follows Semantic Versioning and the
Keep a Changelog format.

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
- Folia support through Paper's region/global/entity scheduler APIs.
- Wide compatibility for Paper, Purpur, Pufferfish, and Folia.
- bStats metrics hook, disabled while the plugin id is `0`.
- `/amctimber selftest`.
- JUnit 5 server-free unit test suite.

[1.0.4]: https://github.com/asketmc/AMCTimber/releases/tag/v1.0.4
[1.0.3]: https://github.com/asketmc/AMCTimber/releases/tag/v1.0.3
[1.0.2]: https://github.com/asketmc/AMCTimber/releases/tag/v1.0.2
[1.0.1]: https://github.com/asketmc/AMCTimber/releases/tag/v1.0.1
[1.0.0]: https://modrinth.com/plugin/amctimber/version/q637itJU
