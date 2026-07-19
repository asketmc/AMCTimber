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
| P0-003 | Protected source/landing damage or wrong integration operation | `ProtectionTest` covers deny/error/hook-call composition, `LandingPlanTest` covers destination coverage, and `scripts/check-runtime-security.py` locks `BLOCK_BREAK`/`DESTROY` plus pre-mutation landing ordering; live WorldGuard/Towny fixtures remain absent | unit + P0 tests + CI invariant gate; planned hook smoke | partial |
| P0-004 | Partial mutation, duplicate completion, or lost/starved recovery yield | Existing lifecycle/journal/ledger tests plus `PendingYieldTest`, `PendingYieldFileTest`, and `RecoveryBudgetTest` cover one-stack pacing, actorless-legacy fail-closed behavior, durable canopy/log records, rollback, and recovery capacity independent of live sessions; live filesystem failure injection remains absent | unit + P0 tagged tests; planned runtime failure injection | partial |
| P0-005 | Main-thread exhaustion from scans, launch, display, crush, or yield bursts | `TimberConfigTest` clamps controls; `FellAttemptBudgetTest`, `FellAttemptAdmissionTest`, `RuntimeWorkLimiterTest`, `CrushDispatcherTest`, `FellSessionSecurityTest`, `FellWorkEstimateTest`, and `PendingYieldTest` enforce global/per-player admission, spent-work accounting, delayed-target geometry, and per-tick ceilings | unit + P0 tagged tests + CI invariant gate | implemented |
| P0-006 | Native/script/nested jar pattern in release artifact | `scripts/check-release-jar.sh`; CI and release dry run apply heuristic checks for native binaries, scripts, nested jars, shaded signature metadata, and missing/duplicate `plugin.yml`; a pass is not a safety guarantee | CI + Release Dry Run + Reviewer Evidence | implemented |
| P0-007 | Release tag/version/source mismatch | `scripts/verify-release-version.sh` checks `pom.xml`, `plugin.yml`, exact tag commit, and ancestry from `main`; `scripts/check-release-security.py` guards the privilege split and dependency-source policy | Release Dry Run + Release Security | implemented |
| P0-008 | Runtime process exec/native load/classloader/network behavior | `scripts/check-runtime-surface.sh` and Semgrep rules cover configured source patterns; review remains scoped to those rules | Reviewer Evidence + Semgrep | implemented |
| P0-009 | XP bridge runs when disabled | `XpBridgeTest.grantDoesNotTouchPlayerOrSchedulerWhenDisabled`; `TimberConfigTest.xpBridgeCanBeDisabledInConfig` | unit + P0 tagged tests | implemented |
| P0-010 | Paper boundary-version startup/selftest/shutdown or public-artifact regression | `Paper Runtime Smoke` covers the built JAR; release publication covers the prepared JAR; `verify-published-release-runtime.sh` downloads the public JAR, verifies its checksum, repeats both endpoints, requires a non-zero selftest, and emits a receipt | Paper Runtime Smoke + post-release runtime verification | implemented |
| P0-011 | QA mutation hooks exposed in normal operation | `qa.commands-enabled` defaults to false and QA commands use the separate `amctimber.qa` permission; command-level runtime smoke is not automated | unit/config tests; planned runtime smoke | partial |
| P0-012 | Purpur/Pufferfish, intermediate 1.21 patch, or gameplay-path runtime regression | The Paper endpoint smoke does not exercise these runtimes or a full fell/chop/protection scenario | planned runtime smoke/E2E | planned |
| P0-013 | Fallen `BlockDisplay` renders black because light is sampled inside terrain | `ToppleAnimatorMathTest.lightAnchor_sitsAboveTheRenderedBlockAndPreservesEveryVertex` proves that the logical light anchor is above the visible block and that rebasing does not move any model vertex; the runtime selftest checks the same contract, but arbitrary client shader packs are not visually automated | unit + P0 tagged tests + Paper selftest; manual shader visual check | partial |
| P0-014 | Block-break cancellation/no-drop or combat-policy bypass | `scripts/check-runtime-security.py` locks final-state/drop ordering and source-attributed damage; unit budget tests cover downstream bounded paths, but a later-listener/combat-canceller Paper fixture is not automated | CI invariant gate; planned gameplay fixture | partial |

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

