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
VERSION=1.0.10
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

Modrinth publication is the final stage of `.github/workflows/release.yml`. It runs only after the
unprivileged build and Paper smokes succeed and the privileged GitHub publisher has signed, attested, and
uploaded the release assets. The Modrinth publisher then:

1. downloads the exact GitHub Release JAR and `SHA256SUMS.txt`, never a local or rebuild artifact;
2. rechecks the tag, commit, `main` ancestry, checksum, size, and sealed metadata handoff;
3. queries Modrinth's current game-version and loader tags with an identifying User-Agent;
4. publishes through `POST /v2/version` with retry/time limits and one multipart JAR upload;
5. handles reruns idempotently, accepting an existing version only when its metadata and SHA-512 match;
6. reads the created version back from the API and links it from the GitHub Actions job summary.

The publication job cannot write GitHub contents, does not check out or execute repository code, and only
receives `MODRINTH_TOKEN` inside the `modrinth` GitHub Environment. Use a dedicated Modrinth personal
access token with the single `VERSION_CREATE` scope; GitHub tokens are not a supported substitute.

One-time credential setup:

```bash
gh secret set MODRINTH_TOKEN --repo asketmc/AMCTimber --env modrinth
```

Enter the token at the secure prompt. Never put it in a command argument, commit, issue, release body, or
workflow log. If the credential is missing or expired, GitHub Release publication remains complete and
the Modrinth job fails explicitly; replace the Environment secret and rerun the failed jobs.

Public channel metadata is versioned in `.github/modrinth.json`. The exact stable Modrinth tags for
`1.20.6` and every `1.21.x` release through `1.21.11` are validated against the live API before
publication. Snapshots, `1.21.12+`, `1.22+`, and unsupported loaders are rejected until the reviewed
configuration is intentionally changed. Modrinth has no Pufferfish loader tag, so releases declare Paper
and Purpur; Folia is intentionally excluded because it is not supported.

Release notes are rendered from the exact `CHANGELOG.md` version section with a consistent compatibility,
verification, and upgrade panel. For a richer player-facing release, add `docs/releases/x.y.z.md` before
tagging; see `docs/releases/README.md`. The override can change prose only, not the project, JAR, loader,
version range, or trust checks.

Suggested Modrinth metadata:

- Type: Mod/Plugin
- Environment: Server
- License: GPL-3.0-only
- Slug: `amctimber`
- Summary: `Valheim-style tree felling`
- Source: <https://github.com/asketmc/AMCTimber>

Moderator/reviewer package:

- Public source link: <https://github.com/asketmc/AMCTimber>.
- Exact GitHub Release JAR only; no `dist/` jar from the source repo.
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
- The evidence-backed configuration matrix and a post-deployment receipt for the exact public JAR.

VirusTotal is only a weak external reputation signal. It should be presented as "hash X had no detections
on date Y", not as proof that the plugin is safe.

## 4. Other Distribution

Hangar or other mirrors should use the same verified GitHub Release jar. Keep release notes, supported
Minecraft versions, and source links consistent across platforms.
