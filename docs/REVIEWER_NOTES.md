# Reviewer Notes

AMCTimber adds Valheim-style tree felling for Paper-family Minecraft servers. These notes are written for
marketplace moderators and server administrators who want a concise review checklist.

## Runtime Behavior

- Listens to block break and interaction events.
- Detects natural trees with bounded scanning and configurable caps.
- Spawns non-persistent Display/Interaction entities for the falling-tree visual.
- Removes or replaces only detected tree/trunk blocks.
- Stores temporary downed-trunk state in memory only.
- Respects optional WorldGuard and Towny checks when those plugins are installed.
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
- No reflection-heavy hidden logic. A `Class.forName` probe is used only to detect Folia at runtime.
- No telemetry in the current release.

## Verification Evidence

Use the GitHub Release jar, not a jar committed under `dist/` or built locally without verification.

Recommended moderator package:

- Public source: <https://github.com/asketmc/AMCTimber>
- GitHub Release jar: `AMCTimber-x.y.z.jar`
- SHA256: `SHA256SUMS.txt`
- Build/test evidence: GitHub Actions `CI` run (`mvn -B -ntp clean verify`)
- Jar safety: `jar-safety-report.txt`
- SBOMs: `sbom.spdx.json` and `sbom.cdx.json`
- Signature evidence: `*.sigstore.json`
- Provenance evidence: GitHub artifact attestation
- Reviewer evidence bundle: GitHub Actions artifact `reviewer-evidence`
- QA reports: GitHub Actions artifact `qa-reports` with JaCoCo coverage, PIT mutation reports, Surefire
  reports, P0-only test reports, and the P0 matrix

VirusTotal can be included as an additional hash-based signal for the exact release jar SHA256. It should
not be described as proof of safety.

## Manual Review Notes

The release jar safety gate fails if the jar contains native binaries, scripts, nested jars, shaded
signature metadata, or anything other than exactly one `plugin.yml`.

The runtime-surface gate reports and fails on process execution, native library loading, dynamic
classloading, hidden reflection APIs, and runtime network API references in `src/main/java`. It reports
compatibility class probes separately so the Folia detection path stays visible without treating it as
hidden code loading.

These checks provide verification evidence. They do not replace manual code review or a formal security
audit.

## Marketplace Security Text

Suggested text for Modrinth, Spigot, Hangar, or server-admin review:

```text
This plugin is public-source and not obfuscated. Release jars are built by GitHub Actions from tagged
source. Each release includes SHA256 checksums, SPDX/CycloneDX SBOMs, Sigstore bundles, GitHub artifact
attestations, and a jar safety report.

The jar safety gate fails the release if native binaries, scripts, nested jars, or shaded signature
metadata are present. The plugin does not use native code, runtime downloads, auto-updaters, telemetry, or
hidden external services. Optional integrations are WorldGuard and Towny.

These checks are verification evidence, not a formal third-party security audit.
```
