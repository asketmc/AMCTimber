# Supported Configuration and Coverage Matrix

This matrix separates what AMCTimber supports, what it advertises, and what has actually been executed.
It is evidence for review, not a compatibility certification. A green cell requires a durable run or
repository receipt; a committed workflow without a successful execution is only `implemented`.

The machine-readable source of truth is [`configuration-matrix.json`](configuration-matrix.json). Run
`python3 scripts/check-configuration-matrix.py` after changing either file. The check fails closed on stale
generated content, unsupported green rows, missing evidence, zero-test receipts, and compatibility drift
between the matrix and release metadata.

## Current release identity

- Release: `v1.0.9`
- Source commit: `435f3855fcf121c6bfef6b558615b22af12ecec9`
- Public JAR: `AMCTimber-1.0.9.jar`
- SHA-256: `dc398d13f99b15c88b500324fef3282f2374c28452b13f47f49fd8d36fdc9e6c`
- Matrix review date: 2026-07-20

## Gameplay candidate identity

The new gameplay evidence is intentionally separate from the public release evidence:

- Implementation commit: `e4cef48e17106d0fc5fc24e768db1c8d92efdd3f`
- Final harness commit: `d1d9c0ee4ab7e483825f1f1969352a4843c4c9cb`
- Candidate JAR SHA-256: `5def03124020473ebb49fab5eaf872c6593aefac5a5262b0fbaf1d18dbd71496`
- VCraftQABot source commit: `fb16e86441bbe54c15cd656a6606e5e2022e1337`
- Campaign evidence: [2026-07-20 local overnight E2E](evidence/configuration-matrix/2026-07-20-overnight-e2e/README.md)

## Coverage matrix

<!-- BEGIN GENERATED CONFIGURATION MATRIX -->
| ID | Configuration | Support | System smoke | Gameplay E2E | Post-deployment | Evidence |
|---|---|---|---|---|---|---|
| `paper-1.20.6-java21-default` | Paper · MC 1.20.6 · Java 21 · defaults; no optional integrations | ✅ supported | ✅ verified | ❌ gap | ✅ verified | [current-main Paper smoke](https://github.com/asketmc/AMCTimber/actions/runs/29511517489)<br>[exact v1.0.9 pre-publication smoke](https://github.com/asketmc/AMCTimber/actions/runs/29467341360)<br>[v1.0.9 post-release runtime receipt](../docs/evidence/configuration-matrix/v1.0.9-post-release-runtime.json) |
| `paper-1.20.6-java21-viaversion-fixture` | Paper · MC 1.20.6 · Java 21 · ViaVersion 5.11.0 test transport; otherwise candidate defaults | 🟡 optional | ✅ verified | ✅ verified | ❌ gap | [local candidate gameplay receipt](../docs/evidence/configuration-matrix/2026-07-20-overnight-e2e/paper-1.20.6-java21-viaversion.json) |
| `paper-1.21.7-java21-default` | Paper · MC 1.21.7 · Java 21 · candidate defaults; no optional integrations | ✅ supported | ✅ verified | ✅ verified | ❌ gap | [local candidate gameplay receipt](../docs/evidence/configuration-matrix/2026-07-20-overnight-e2e/paper-1.21.7-java21.json) |
| `paper-other-intermediate-java21-default` | Paper · MC 1.21-1.21.6 and 1.21.8-1.21.10 · Java 21 · defaults; no optional integrations | ✅ supported | ❌ gap | ❌ gap | ❌ gap | — |
| `paper-1.21.11-java21-default` | Paper · MC 1.21.11 · Java 21 · defaults; no optional integrations | ✅ supported | ✅ verified | ✅ verified | ✅ verified | [current-main Paper smoke](https://github.com/asketmc/AMCTimber/actions/runs/29511517489)<br>[local candidate 10-cycle soak receipt](../docs/evidence/configuration-matrix/2026-07-20-overnight-e2e/paper-1.21.11-java21-soak10.json)<br>[v1.0.9 post-release runtime receipt](../docs/evidence/configuration-matrix/v1.0.9-post-release-runtime.json) |
| `paper-1.21.11-java21-event-policy` | Paper · MC 1.21.11 · Java 21 · late cancellation and dropItems=false policy fixture | 🟡 optional | ✅ verified | ✅ verified | ❌ gap | [local candidate event-policy receipt](../docs/evidence/configuration-matrix/2026-07-20-overnight-e2e/paper-1.21.11-java21-policy.json) |
| `purpur-1.21.11-java21-default` | Purpur · MC 1.21.11 · Java 21 · candidate defaults; no optional integrations | ✅ supported | ✅ verified | ✅ verified | ❌ gap | [local candidate gameplay receipt](../docs/evidence/configuration-matrix/2026-07-20-overnight-e2e/purpur-1.21.11-java21.json) |
| `purpur-other-supported-range-java21-default` | Purpur · MC 1.20.6-1.21.10 · Java 21 · defaults; no optional integrations | ✅ supported | ❌ gap | ❌ gap | ❌ gap | — |
| `pufferfish-1.21.10-java21-default` | Pufferfish · MC 1.21.10 · Java 21 · candidate defaults; no optional integrations | ✅ supported | ✅ verified | ✅ verified | ❌ gap | [local candidate gameplay receipt](../docs/evidence/configuration-matrix/2026-07-20-overnight-e2e/pufferfish-1.21.10-java21.json) |
| `pufferfish-other-supported-range-java21-default` | Pufferfish · MC 1.20.6-1.21.9 and 1.21.11 · Java 21 · defaults; no optional integrations | ✅ supported | ❌ gap | ❌ gap | ❌ gap | — |
| `supported-runtime-worldguard` | supported runtimes · MC 1.20.6-1.21.11 · Java 21 · WorldGuard enabled | 🟡 optional | 🟡 unit only | ❌ gap | ❌ gap | [protection contract tests](../src/test/java/com/asketmc/timber/ProtectionTest.java) |
| `supported-runtime-towny` | supported runtimes · MC 1.20.6-1.21.11 · Java 21 · Towny enabled | 🟡 optional | 🟡 unit only | ❌ gap | ❌ gap | [protection contract tests](../src/test/java/com/asketmc/timber/ProtectionTest.java) |
| `supported-runtime-nondefault-config` | supported runtimes · MC 1.20.6-1.21.11 · Java 21 · non-default supported settings | ✅ supported | 🟡 unit only | ❌ gap | ❌ gap | [configuration tests](../src/test/java/com/asketmc/timber/TimberConfigTest.java) |
| `paper-1.21.11-java25-default` | Paper · MC 1.21.11 · Java 25 · candidate defaults; no optional integrations | ✅ supported | ✅ verified | ✅ verified | ❌ gap | [local candidate Java 25 gameplay receipt](../docs/evidence/configuration-matrix/2026-07-20-overnight-e2e/paper-1.21.11-java25.json) |
| `supported-runtime-other-java-newer-than-21` | supported runtimes · MC 1.20.6-1.21.11 · Java &gt;21 except exact Java 25 row · defaults | ✅ supported | ❌ gap | ❌ gap | ❌ gap | — |
| `paper-26.1.2-java25-forward-canary` | Paper · MC 26.1.2 · Java 25 · forward-compatibility canary; outside supported release range | forward canary | ✅ verified | ✅ verified | — n/a | [local candidate Minecraft 26.1.2 canary receipt](../docs/evidence/configuration-matrix/2026-07-20-overnight-e2e/paper-26.1.2-java25-canary.json) |
| `spigot-unsupported` | Spigot · MC any · Java any · any | ⛔ unsupported | — n/a | — n/a | — n/a | — |
| `folia-unsupported` | Folia · MC any · Java any · any | ⛔ unsupported | — n/a | — n/a | — n/a | — |
<!-- END GENERATED CONFIGURATION MATRIX -->

## What the levels mean

- **System smoke** starts a real server process, loads the JAR, requires a non-zero selftest result, and
  checks a clean shutdown. It does not create a player or mutate the world.
- **Gameplay E2E** uses real protocol clients and a real server world. Every green candidate receipt
  asserts default-deny QA access, sneak bypass, conservative player-build rejection, oak and 2x2 jungle
  felling, animation/chopping, exact yield, cleanup, restart recovery, three-player concurrency, TPS/MSPT,
  and plugin-log health.
- **Post-deployment** downloads the public release artifact, verifies its published checksum, and then
  executes it. Pre-publication release-candidate smoke does not satisfy this column.

`unit only` is deliberately not green: server-free contract tests are valuable, but they do not establish
runtime compatibility for a server, integration, or configuration row.

## Representative environment

The representative gameplay environment is a disposable Windows loopback runtime with deterministic
world setup, real QABot protocol clients, checksum-bound server and plugin JARs, bounded waits, restart
recovery, concurrent clients, and explicit performance and log-health oracles. Exact candidate runs cover
Paper 1.21.7/1.21.11, Purpur 1.21.11, Pufferfish 1.21.10, Java 21/25, and a Paper 26.1.2 forward canary.

The older public-release baseline remains narrower: Paper endpoints, Java 21, packaged defaults, and
startup/selftest/shutdown only. Candidate gameplay evidence never upgrades a post-deployment cell.

## Known gaps and advertised-surface drift

- WorldGuard and Towny still have contract/unit coverage only; neither integration was installed live.
- Untested intermediate Paper patches and all untested Purpur/Pufferfish versions remain gaps. Exact fork
  runs do not certify a whole version range.
- Java 25 is green only for the exact Paper 1.21.11 row. Other Java versions above 21 remain gaps.
- The Paper 1.20.6 gameplay receipt uses an explicitly disclosed ViaVersion transport fixture, so the
  default 1.20.6 gameplay cell remains red.
- Paper 26.1.2 is a forward canary outside the supported 1.20.6-1.21.11 release contract.
- The public Modrinth `v1.0.9` entry was observed on 2026-07-20 advertising Folia and Minecraft
  1.20-1.20.5. Those rows are outside the evidence-backed contract. Repository publication metadata is
  narrowed by this change; the already-published Modrinth record remains an explicit external correction.

## Reproduce locally

```powershell
powershell -ExecutionPolicy Bypass -File scripts/e2e-gameplay.ps1 -Runtime paper -MinecraftVersion 1.21.11
python scripts/check-configuration-matrix.py
```

The campaign README records the exact runtime builds, Java variants, fixtures, SHA-256 values, scenario
counts, performance results, and deliberate gaps. Heavy campaign execution used zero GitHub-hosted Actions
minutes.

The public-release check remains:

```bash
bash scripts/verify-published-release-runtime.sh v1.0.9 post-release-evidence/v1.0.9
python3 scripts/check-configuration-matrix.py
```

The runtime verifier downloads the public GitHub Release JAR and checksum file without build credentials,
executes both Paper endpoint smokes, and emits a machine-readable receipt plus retained server logs.
