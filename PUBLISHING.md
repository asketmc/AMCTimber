# Publishing AMCTimber

This checklist keeps source, GitHub release assets, and distribution channels aligned.

## 1. GitHub Source

The source of truth is <https://github.com/asketmc/AMCTimber>.

Before a release:

1. Bump the version in `pom.xml`.
2. Bump the version in `src/main/resources/plugin.yml`.
3. Add a `CHANGELOG.md` entry.
4. Confirm `bash scripts/verify-release-version.sh` prints the same version.
5. Merge the release commit to `main`.

## 2. GitHub Release Evidence

The workflow `.github/workflows/release.yml` runs when a GitHub Release is published. Its read-only job
requires the exact tag commit to be an ancestor of `main`, builds and smokes the jar, and uploads an exact
candidate. Only after that succeeds does a separate publication job validate, sign, attest, and upload:

- `AMCTimber-x.y.z.jar`
- `SHA256SUMS.txt`
- `sbom.spdx.json`
- `sbom.cdx.json`
- `jar-safety-report.txt`
- `*.sigstore.json` bundles
- GitHub artifact attestations

Release process:

```bash
VERSION=1.0.7
gh release create "v${VERSION}" \
  --repo asketmc/AMCTimber \
  --target main \
  --title "AMCTimber v${VERSION}" \
  --generate-notes
```

The release workflow enforces that the tag points to the checked-out commit on `main` and that the tag,
`pom.xml`, and `plugin.yml` versions match. Maven custom repositories are restricted to their owned group
IDs, and the Paper API build input is timestamp-pinned. After the workflow finishes, verify assets with
`docs/VERIFY_RELEASE.md` using the exact requested tag identity.

## 3. Modrinth

The Modrinth project is <https://modrinth.com/plugin/amctimber>.

Upload the jar from a verified GitHub Release. Do not upload a locally edited jar.

Suggested Modrinth metadata:

- Type: Mod/Plugin
- Environment: Server
- License: GPL-3.0-only
- Slug: `amctimber`
- Summary: `Valheim-style tree felling`
- Source: <https://github.com/asketmc/AMCTimber>

Moderator/reviewer package:

- Public source link: <https://github.com/asketmc/AMCTimber>
- GitHub Release jar only; no `dist/` jar from the source repo.
- SHA256 from `SHA256SUMS.txt`.
- GitHub Actions `CI` run showing `mvn -B -ntp clean verify`.
- `jar-safety-report.txt`.
- `sbom.spdx.json` and `sbom.cdx.json`.
- `*.sigstore.json` bundle and GitHub artifact attestation.
- Optional VirusTotal file-hash report for the exact release jar SHA256.
- `docs/REVIEWER_NOTES.md`.
- `qa-reports` CI artifact with JaCoCo, PIT, Surefire and P0 matrix evidence.
- Paper 1.20.6 and latest stable 1.21 runtime-smoke logs. Purpur/Pufferfish compatibility is based on
  their Paper API compatibility unless separate runtime evidence is attached; Folia is not supported.

VirusTotal is only a weak external reputation signal. It should be presented as "hash X had no detections
on date Y", not as proof that the plugin is safe.

## 4. Other Distribution

Hangar or other mirrors should use the same verified GitHub Release jar. Keep release notes, supported
Minecraft versions, and source links consistent across platforms.
