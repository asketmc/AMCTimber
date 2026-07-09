# P0 Test Matrix

This matrix maps AMCTimber's highest-risk behavior to automated evidence. It is a QA control, not a
security certification. Statuses are intentionally conservative:

- `implemented` means the listed gate currently runs in CI.
- `partial` means there is meaningful automated evidence, but a live Paper/Purpur/Pufferfish smoke test or
  deeper harness is still needed.
- `planned` means the risk is documented but not yet covered by an automated test.

The machine-readable mapping lives beside this document in `docs/p0-test-matrix.csv`.

| P0 ID | Risk | Evidence | CI gate | Status |
|---|---|---|---|---|
| P0-001 | Grief / false-positive felling of player builds | `TreeScannerLogicTest.naturalNeighborGuardRejectsPlayerBuildMaterials` covers the material-name heuristic; no heuristic can guarantee every custom build is rejected | unit + P0 tagged tests | partial |
| P0-002 | False-positive spread across neighbouring wood species | `TreeScannerLogicTest.speciesOf_materialDelegates_andFencesDifferentWoods`; `TreeScannerLogicTest.leafMatches_sameSpeciesAndAzaleaCountsAsOakOnly` | unit + P0 tagged tests | implemented |
| P0-003 | Protected region/claim damage from partial tree felling | `ProtectionTest` covers allow/deny/error composition and default deny-on-error policy; live WorldGuard/Towny integration smoke is not automated | unit tests; planned runtime smoke | partial |
| P0-004 | Partial mutation, duplicate completion, or lost recovery yield | `FellLifecycleTest`, `WorldMutationJournalTest`, `YieldLedgerTest`, `PendingYieldTest`, `PendingYieldFileTest`, `BlockKeyTest`, and `EntityBudgetTest` cover lifecycle retry, rollback, rejected spawn retention, recovery-schema roundtrip/validation, idempotent yield, world-aware identity, and global reservations; live plugin interaction remains untested | unit + P0 tagged tests; planned runtime failure injection | partial |
| P0-005 | Config-driven DoS from expensive settings | `TimberConfigTest.expensiveSettingsHaveUpperBounds`; `TimberConfigTest.lowerBoundsProtectTinyOrNegativeExpensiveSettings` | unit + P0 tagged tests + JaCoCo + PIT | implemented |
| P0-006 | Native/script/nested jar pattern in release artifact | `scripts/check-release-jar.sh`; CI and release dry run apply heuristic checks for native binaries, scripts, nested jars, shaded signature metadata, and missing/duplicate `plugin.yml`; a pass is not a safety guarantee | CI + Release Dry Run + Reviewer Evidence | implemented |
| P0-007 | Release tag/version mismatch | `scripts/verify-release-version.sh` checks `pom.xml`, `plugin.yml`, and release tag consistency | Release Dry Run + Release Security | implemented |
| P0-008 | Runtime process exec/native load/classloader/network behavior | `scripts/check-runtime-surface.sh` and Semgrep rules cover configured source patterns; review remains scoped to those rules | Reviewer Evidence + Semgrep | implemented |
| P0-009 | XP bridge runs when disabled | `XpBridgeTest.grantDoesNotTouchPlayerOrSchedulerWhenDisabled`; `TimberConfigTest.xpBridgeCanBeDisabledInConfig` | unit + P0 tagged tests | implemented |
| P0-010 | Paper boundary-version startup/selftest/shutdown regression | `Paper Runtime Smoke` starts Paper 1.20.6 and the latest stable 1.21 release, runs the built-in selftest, checks clean shutdown, and uploads exact-build logs | Paper Runtime Smoke | implemented |
| P0-011 | QA mutation hooks exposed in normal operation | `qa.commands-enabled` defaults to false and QA commands use the separate `amctimber.qa` permission; command-level runtime smoke is not automated | unit/config tests; planned runtime smoke | partial |
| P0-012 | Purpur/Pufferfish, intermediate 1.21 patch, or gameplay-path runtime regression | The Paper endpoint smoke does not exercise these runtimes or a full fell/chop/protection scenario | planned runtime smoke/E2E | planned |

## Current Gate

`mvn -B -ntp clean verify` runs:

- all server-free JUnit tests;
- a second P0-only JUnit execution into `target/surefire-p0-reports`;
- JaCoCo report and check;
- PIT mutation testing for the current pure-core target (`TimberConfig`, `Tools`);
- jar build.

Current JaCoCo hard gate:

- combined scoped bundle (`TimberConfig`, `Tools`, `FellLifecycle`, `EntityBudget`, `YieldLedger`):
  line >= 80%, branch >= 70%;
- full project coverage is still reported in `target/site/jacoco`, but not used as a global gate until a
  server/runtime harness exists for Bukkit-bound classes.

Current PIT hard gate:

- target classes: `com.asketmc.timber.TimberConfig`, `com.asketmc.timber.Tools`;
- mutation threshold: >= 70%;
- mutation coverage threshold: >= 80%.

