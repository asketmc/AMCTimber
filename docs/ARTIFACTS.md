# Artifact Evidence Map

AMCTimber uses release and CI artifacts as release verification evidence for build, dependency, and
artifact hygiene controls. These entries describe what is produced and how to verify it; they do not claim
formal certification or byte-for-byte reproducible builds.

`implemented` means a committed producer currently runs and emits the named evidence. Manual server logs
may supplement a review, but runtime smoke is not marked implemented without a dedicated workflow.

| Artifact | Producer | Trigger | Location | Verification method | Status |
|---|---|---|---|---|---|
| CI jar | `CI` workflow | Push to `main`, pull request, manual dispatch | GitHub Actions artifact `AMCTimber-jar` | Download artifact; run `jar tf`; compare with source build if needed | implemented |
| Reviewer evidence bundle | `Reviewer Evidence` workflow via `scripts/prepare-reviewer-evidence.sh` | Push to `main`, pull request, manual dispatch | GitHub Actions artifact `reviewer-evidence` containing `reviewer-evidence.zip` | Inspect included jar, SHA256 checksums, SBOMs, heuristic jar-hygiene report, Maven test report, dependency report and runtime-surface report | implemented |
| QA reports | `CI` workflow running `mvn -B -ntp clean verify` | Push to `main`, pull request, manual dispatch | GitHub Actions artifact `qa-reports` | Inspect JaCoCo HTML/XML, PIT mutation reports, Surefire reports, P0-only reports and P0 matrix | implemented |
| JaCoCo coverage report | `jacoco-maven-plugin` in Maven `verify` | Local/CI `mvn -B -ntp clean verify` | `target/site/jacoco/` and `qa-reports` artifact | Confirm full report exists and pure-core threshold check passed | implemented |
| PIT mutation report | `pitest-maven` in Maven `verify` | Local/CI `mvn -B -ntp clean verify` | `target/pit-reports/` and `qa-reports` artifact | Confirm mutation threshold and coverage threshold passed for configured pure-core target | implemented |
| P0 test matrix | `docs/P0_TEST_MATRIX.md` and `docs/p0-test-matrix.csv` | Source-controlled, uploaded by CI | Repository and `qa-reports` artifact | Map P0 risk to test/script evidence and CI gate; statuses are conservative | implemented |
| Release dry-run bundle | `Release Dry Run` workflow | Push to `main`, pull request, manual dispatch | GitHub Actions artifact `release-dry-run` | Check jar, checksums, SPDX/CycloneDX SBOMs, and heuristic jar-hygiene report before publishing a release | implemented |
| Jar safety report | `scripts/check-release-jar.sh` via `CI` and the read-only `Release Security` build job | CI and GitHub Release publication | CI artifact and GitHub Release asset | Confirm the known-risk pattern checks passed; inspect the script scope and do not treat a pass as a safety guarantee | implemented |
| Runtime surface report | `scripts/check-runtime-surface.sh` via `Reviewer Evidence` workflow | Push to `main`, pull request, manual dispatch | Included in `reviewer-evidence.zip` as `forbidden-api-report.txt` | Confirm configured process execution, native loading, dynamic classloading, hidden reflection and runtime network API patterns are reported as `none`; inspect script scope and allowlists | implemented |
| Paper endpoint runtime smoke | `Paper Runtime Smoke` workflow via `scripts/smoke-paper.sh` | Push to `main`, pull request, manual dispatch | `paper-smoke-1.20.6` and `paper-smoke-latest-1.21` artifacts | Confirm the resolved Paper build, startup, built-in selftest, and clean shutdown in logs; this does not cover Purpur, Pufferfish, every 1.21 patch, or gameplay E2E | implemented |
| Exact release-jar runtime smoke | Read-only `Release Security` build job using the already prepared release jar | GitHub Release publication | Actions artifacts `release-runtime-smoke-vx.y.z` and checksum-bound `release-candidate-vx.y.z` | Match `plugin_sha256` in both smoke summaries to the release jar entry in `SHA256SUMS.txt`; confirm Paper 1.20.6 and latest stable 1.21 startup, selftest, and clean shutdown | implemented |
| Maven test report | `scripts/prepare-reviewer-evidence.sh` | Push to `main`, pull request, manual dispatch | Included in `reviewer-evidence.zip` as `test-report.txt` | Confirm `mvn -B -ntp clean verify` completed successfully | implemented |
| Dependency report | `scripts/prepare-reviewer-evidence.sh` | Push to `main`, pull request, manual dispatch | Included in `reviewer-evidence.zip` as `dependency-scan-report.txt` | Review Maven dependency tree; CVE evidence remains in OSV/Dependency Review workflows | implemented |
| Release jar | Split `Release Security` build/publish jobs | GitHub Release published for a `v*` tag whose commit is on `main` | GitHub Release assets | Verify checksum, exact-tag Sigstore bundle, and exact-tag GitHub attestation | implemented |
| Checksums | `Release Security` workflow | GitHub Release published | `SHA256SUMS.txt` release asset | `sha256sum -c SHA256SUMS.txt --ignore-missing` | implemented |
| SPDX SBOM | `SBOM` and `Release Security` workflows using `spdx-maven-plugin` | CI/PR and GitHub Release publication | CI artifact or `sbom.spdx.json` release asset | Confirm checksum/signature on release; inspect with SPDX tooling | implemented |
| CycloneDX SBOM | `SBOM` and `Release Security` workflows | CI/PR and GitHub Release publication | CI artifact or `sbom.cdx.json` release asset | Confirm checksum/signature on release; inspect with CycloneDX tooling | implemented |
| Sigstore bundles | `Release Security` workflow | GitHub Release published | `*.sigstore.json` release assets | `cosign verify-blob --bundle ...` | implemented |
| GitHub artifact attestations | `Release Security` workflow | GitHub Release published | GitHub attestation store | `gh attestation verify ... --repo asketmc/AMCTimber` | implemented |
| CodeQL result | `CodeQL` workflow | Push, pull request, weekly schedule | GitHub code scanning | GitHub Security tab / workflow run status | implemented |
| Dependency Review result | `Dependency Review` workflow | Pull request | PR checks | PR status check; fails on moderate-or-higher runtime vulnerabilities | implemented |
| Dependabot updates | `.github/dependabot.yml` | Weekly schedule | Dependabot PRs | Review dependency PRs and CI results | implemented |
| OSV Scanner result | `OSV Scanner` workflow | Push, pull request, weekly schedule | GitHub Actions run | Workflow status and logs; Maven scan uses `--no-resolve` for Paper provided SNAPSHOT compatibility | implemented |
| Semgrep result | `Semgrep` workflow | Push, pull request, weekly schedule | GitHub code scanning SARIF | GitHub Security tab / workflow run status; local `.semgrep.yml` rules and pinned Semgrep engine keep scan behavior low-noise | implemented |
| OpenSSF Scorecard result | `Scorecard` workflow | Push to `main`, branch protection event, weekly schedule | OpenSSF/GitHub workflow results | Workflow logs and published Scorecard result | implemented |
