# P0 Test Matrix

This matrix maps AMCTimber's highest-risk behavior to automated evidence. It is a QA control, not a
security certification. Statuses are intentionally conservative:

- `implemented` means the listed gate currently runs in CI.
- `partial` means there is meaningful automated evidence, but a live Paper/Purpur/Folia smoke test or
  deeper harness is still needed.
- `planned` means the risk is documented but not yet covered by an automated test.

The machine-readable mapping lives beside this document in `docs/p0-test-matrix.csv`.

| P0 ID | Risk | Evidence | CI gate | Status |
|---|---|---|---|---|
| P0-001 | Grief / false-positive felling of player builds | `TreeScannerLogicTest.naturalNeighborGuardRejectsPlayerBuildMaterials` covers the pure material-name guard before Bukkit tag checks | unit + P0 tagged tests | partial |
| P0-002 | False-positive spread across neighbouring wood species | `TreeScannerLogicTest.speciesOf_materialDelegates_andFencesDifferentWoods`; `TreeScannerLogicTest.leafMatches_sameSpeciesAndAzaleaCountsAsOakOnly` | unit + P0 tagged tests | implemented |
| P0-003 | Protected region/claim damage from partial tree felling | Current code checks base/all collected logs through `Protection.canFell` and skips crush damage at protected landing points; fake bridge/unit harness is not present yet | planned | partial |
| P0-004 | Stuck `activeCuts` slot causing duplicate-block/DoS behavior | Code uses `try/finally` in launch and landing paths, clears active jobs on shutdown, and emergency-drops mid-fall yield; failure-path unit harness is not present yet | planned | partial |
| P0-005 | Config-driven DoS from expensive settings | `TimberConfigTest.expensiveSettingsHaveUpperBounds`; `TimberConfigTest.lowerBoundsProtectTinyOrNegativeExpensiveSettings` | unit + P0 tagged tests + JaCoCo + PIT | implemented |
| P0-006 | Native/script/nested jar payload in release artifact | `scripts/check-release-jar.sh`; CI and release dry run fail on native binaries, scripts, nested jars, shaded signature metadata, or missing/duplicate `plugin.yml` | CI + Release Dry Run + Reviewer Evidence | implemented |
| P0-007 | Release tag/version mismatch | `scripts/verify-release-version.sh` checks `pom.xml`, `plugin.yml`, and release tag consistency | Release Dry Run + Release Security | implemented |
| P0-008 | Runtime process exec/native load/classloader/network behavior | `scripts/check-runtime-surface.sh` and Semgrep rules; Folia `Class.forName` compatibility probe is reported separately | Reviewer Evidence + Semgrep | implemented |
| P0-009 | XP bridge runs when disabled | `XpBridgeTest.grantDoesNotTouchPlayerOrSchedulerWhenDisabled`; `TimberConfigTest.xpBridgeCanBeDisabledInConfig` | unit + P0 tagged tests | implemented |
| P0-010 | Folia scheduler paths use unsafe sync world mutation | Scheduler wrapper centralizes region/global/entity scheduling and admin QA hooks dispatch through region/entity schedulers; automated live Folia smoke evidence is not present yet | planned runtime smoke | partial |

## Current Gate

`mvn -B -ntp clean verify` runs:

- all server-free JUnit tests;
- a second P0-only JUnit execution into `target/surefire-p0-reports`;
- JaCoCo report and check;
- PIT mutation testing for the current pure-core target (`TimberConfig`, `Tools`);
- jar build.

Current JaCoCo hard gate:

- `TimberConfig`: line >= 80%, branch >= 70%;
- `Tools`: line >= 80%, branch >= 70%;
- full project coverage is still reported in `target/site/jacoco`, but not used as a global gate until a
  server/runtime harness exists for Bukkit-bound classes.

Current PIT hard gate:

- target classes: `com.asketmc.timber.TimberConfig`, `com.asketmc.timber.Tools`;
- mutation threshold: >= 70%;
- mutation coverage threshold: >= 80%.

