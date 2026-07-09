# Artifact Evidence Map

AMCTimber uses release and CI artifacts as reproducible evidence for build, dependency, and artifact
hygiene controls. These entries describe what is produced and how to verify it; they do not claim formal
certification.

| Artifact | Producer | Trigger | Location | Verification method | Status |
|---|---|---|---|---|---|
| CI jar | `CI` workflow | Push to `main`, pull request, manual dispatch | GitHub Actions artifact `AMCTimber-jar` | Download artifact; run `jar tf`; compare with source build if needed | implemented |
| Jar safety report | `scripts/check-release-jar.sh` via `CI` and `Release Security` workflows | CI and GitHub Release publication | CI artifact and GitHub Release asset | Confirm report says `status: passed`; inspect blocked file classes in script | implemented |
| Release jar | `Release Security` workflow | GitHub Release published for a `v*` tag | GitHub Release assets | Verify checksum, Sigstore bundle, and GitHub attestation | implemented |
| Checksums | `Release Security` workflow | GitHub Release published | `SHA256SUMS.txt` release asset | `sha256sum -c SHA256SUMS.txt --ignore-missing` | implemented |
| SPDX SBOM | `SBOM` and `Release Security` workflows | CI/PR and GitHub Release publication | CI artifact or `sbom.spdx.json` release asset | Confirm checksum/signature on release; inspect with SPDX tooling | implemented |
| CycloneDX SBOM | `SBOM` and `Release Security` workflows | CI/PR and GitHub Release publication | CI artifact or `sbom.cdx.json` release asset | Confirm checksum/signature on release; inspect with CycloneDX tooling | implemented |
| Sigstore bundles | `Release Security` workflow | GitHub Release published | `*.sigstore.json` release assets | `cosign verify-blob --bundle ...` | implemented |
| GitHub artifact attestations | `Release Security` workflow | GitHub Release published | GitHub attestation store | `gh attestation verify ... --repo asketmc/AMCTimber` | implemented |
| CodeQL result | `CodeQL` workflow | Push, pull request, weekly schedule | GitHub code scanning | GitHub Security tab / workflow run status | implemented |
| Dependency Review result | `Dependency Review` workflow | Pull request | PR checks | PR status check; fails on moderate-or-higher vulnerabilities | implemented |
| Dependabot updates | `.github/dependabot.yml` | Weekly schedule | Dependabot PRs | Review dependency PRs and CI results | implemented |
| OSV Scanner result | `OSV Scanner` workflow | Push, pull request, weekly schedule | GitHub Actions run | Workflow status and logs; Maven scan uses `--no-resolve` for Paper provided SNAPSHOT compatibility | implemented |
| Semgrep result | `Semgrep` workflow | Push, pull request, weekly schedule | GitHub code scanning SARIF | GitHub Security tab / workflow run status | implemented |
| OpenSSF Scorecard result | `Scorecard` workflow | Push to `main`, branch protection event, weekly schedule | OpenSSF/GitHub workflow results | Workflow logs and published Scorecard result | implemented |
