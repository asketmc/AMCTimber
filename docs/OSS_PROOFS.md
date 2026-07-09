# OSS Proofs

This project implements a small, auditable set of open-source evidence controls suitable for a Minecraft
server plugin portfolio project.

| Control | Evidence | Status |
|---|---|---|
| GitHub Actions CI | `CI` workflow runs `mvn -B -ntp clean verify`, the heuristic jar-hygiene check, and uploads the jar artifact | implemented |
| CodeQL | Java/Kotlin CodeQL workflow with `security-and-quality` queries | implemented |
| Dependency Review | PR-only dependency review with moderate severity failure threshold for runtime scope | implemented |
| Dependabot | Weekly Maven and GitHub Actions updates | implemented |
| OSV Scanner | Recursive OSV scan on push, PR, and schedule. Maven transitive resolution is disabled because Paper API is a provided SNAPSHOT dependency; transitive evidence is covered by SBOM generation. | implemented |
| Semgrep | Pinned Semgrep engine with committed local rules for runtime process/native/classloader risks and SARIF upload | implemented |
| OpenSSF Scorecard | Scheduled and push-triggered Scorecard workflow with published results | implemented |
| SBOM | SPDX SBOM generation via `spdx-maven-plugin` and CycloneDX SBOM generation via Maven plugin, both in CI and releases | implemented |
| Release dry run | PR/push workflow builds the release asset directory without upload or signing and validates version consistency, heuristic jar hygiene, checksums, and SBOMs | implemented |
| Release checksums | `SHA256SUMS.txt` release asset | implemented |
| Sigstore/cosign | Keyless `cosign sign-blob` bundles for release assets | implemented |
| Artifact attestations | GitHub artifact attestations for release assets | implemented |
| Jar safety | Scripted heuristic blocks known native binary, script, nested jar, and shaded signature metadata patterns; passing is not a safety guarantee | implemented |
| Runtime surface review | Scripted check reports/fails process execution, native loading, dynamic classloading, hidden reflection and runtime network API references | implemented |
| Reviewer evidence | GitHub Actions artifact with release jar, checksums, SBOMs, jar safety, Maven test, dependency and runtime-surface reports | implemented |
| Security policy | `SECURITY.md` documents reporting, sensitive data handling and conservative trust claims | implemented |
| Private vulnerability reporting | GitHub private vulnerability reporting is enabled; `SECURITY.md` links the advisory flow | implemented |
| JaCoCo coverage gate | Maven `verify` generates JaCoCo reports and enforces >=80% line / >=70% branch coverage for the current pure-core gate | implemented |
| PIT mutation gate | Maven `verify` runs PIT for the current pure-core mutation target and uploads mutation reports through CI artifacts | implemented |
| P0 test matrix | `docs/P0_TEST_MATRIX.md` and `docs/p0-test-matrix.csv` map highest-risk behavior to tests/scripts and CI gates | implemented |
| Paper endpoint runtime smoke | Dedicated PR/push workflow tests its built jar; the release workflow separately tests the exact prepared release jar on Paper 1.20.6 and latest stable 1.21, records the plugin hash, and uploads logs; no Purpur/Pufferfish or gameplay E2E claim | implemented |

These controls provide verification evidence. They do not mean the plugin has been externally audited or
certified secure.
