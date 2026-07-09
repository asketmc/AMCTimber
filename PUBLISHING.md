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

The workflow `.github/workflows/release.yml` runs when a GitHub Release is published. It builds the jar
from the tagged source and uploads:

- `AMCTimber-x.y.z.jar`
- `SHA256SUMS.txt`
- `sbom.spdx.json`
- `sbom.cdx.json`
- `jar-safety-report.txt`
- `*.sigstore.json` bundles
- GitHub artifact attestations

Release process:

```bash
git tag v1.0.4
git push origin v1.0.4
gh release create v1.0.4 \
  --repo asketmc/AMCTimber \
  --target v1.0.4 \
  --title "AMCTimber v1.0.4" \
  --notes-file CHANGELOG.md
```

The release workflow enforces that the tag, `pom.xml`, and `plugin.yml` versions match. After the
release workflow finishes, verify assets with `docs/VERIFY_RELEASE.md`.

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

## 4. Other Distribution

Hangar or other mirrors should use the same verified GitHub Release jar. Keep release notes, supported
Minecraft versions, and source links consistent across platforms.
