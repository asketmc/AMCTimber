# OSS Proofs

This project implements a small, auditable set of open-source proof controls suitable for a Minecraft
server plugin portfolio project.

| Control | Evidence | Status |
|---|---|---|
| GitHub Actions CI | `CI` workflow runs `mvn -B -ntp clean verify`, jar safety check, and uploads the jar artifact | implemented |
| CodeQL | Java/Kotlin CodeQL workflow with `security-and-quality` queries | implemented |
| Dependency Review | PR-only dependency review with moderate severity failure threshold for runtime scope | implemented |
| Dependabot | Weekly Maven and GitHub Actions updates | implemented |
| OSV Scanner | Recursive OSV scan on push, PR, and schedule. Maven transitive resolution is disabled because Paper API is a provided SNAPSHOT dependency; transitive evidence is covered by SBOM generation. | implemented |
| Semgrep | Pinned Semgrep engine with committed local rules for runtime process/native/classloader risks and SARIF upload | implemented |
| OpenSSF Scorecard | Scheduled and push-triggered Scorecard workflow with published results | implemented |
| SBOM | SPDX SBOM generation via `spdx-maven-plugin` and CycloneDX SBOM generation via Maven plugin, both in CI and releases | implemented |
| Release dry run | PR/push workflow builds the release asset directory without upload or signing and validates version consistency, jar safety, checksums, and SBOMs | implemented |
| Release checksums | `SHA256SUMS.txt` release asset | implemented |
| Sigstore/cosign | Keyless `cosign sign-blob` bundles for release assets | implemented |
| Artifact attestations | GitHub artifact attestations for release assets | implemented |
| Jar safety | Scripted check blocks native binaries, scripts, nested jars, and shaded signature metadata | implemented |

These controls provide verification evidence. They do not mean the plugin has been externally audited or
certified secure.
