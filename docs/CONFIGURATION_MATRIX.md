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

## Coverage matrix

<!-- BEGIN GENERATED CONFIGURATION MATRIX -->
| ID | Configuration | Support | System smoke | Gameplay E2E | Post-deployment | Evidence |
|---|---|---|---|---|---|---|
| `paper-1.20.6-java21-default` | Paper · MC 1.20.6 · Java 21 · defaults; no optional integrations | ✅ supported | ✅ verified | ❌ gap | 🟡 implemented | [current-main Paper smoke](https://github.com/asketmc/AMCTimber/actions/runs/29511517489)<br>[exact v1.0.9 pre-publication smoke](https://github.com/asketmc/AMCTimber/actions/runs/29467341360)<br>[post-release runtime workflow](../.github/workflows/post-release-runtime.yml) |
| `paper-1.21.11-java21-default` | Paper · MC 1.21.11 · Java 21 · defaults; no optional integrations | ✅ supported | ✅ verified | ❌ gap | 🟡 implemented | [current-main Paper smoke](https://github.com/asketmc/AMCTimber/actions/runs/29511517489)<br>[exact v1.0.9 pre-publication smoke](https://github.com/asketmc/AMCTimber/actions/runs/29467341360)<br>[post-release runtime workflow](../.github/workflows/post-release-runtime.yml) |
| `paper-intermediate-1.21-java21-default` | Paper · MC 1.21-1.21.10 intermediate patches · Java 21 · defaults; no optional integrations | ✅ supported | ❌ gap | ❌ gap | ❌ gap | — |
| `purpur-supported-range-java21-default` | Purpur · MC 1.20.6-1.21.11 · Java 21 · defaults; no optional integrations | ✅ supported | ❌ gap | ❌ gap | ❌ gap | — |
| `pufferfish-supported-range-java21-default` | Pufferfish · MC 1.20.6-1.21.11 · Java 21 · defaults; no optional integrations | ✅ supported | ❌ gap | ❌ gap | ❌ gap | — |
| `supported-runtime-worldguard-towny` | supported runtimes · MC 1.20.6-1.21.11 · Java 21 · WorldGuard, Towny, or both | 🟡 optional | 🟡 unit only | ❌ gap | ❌ gap | [protection contract tests](../src/test/java/com/asketmc/timber/ProtectionTest.java) |
| `supported-runtime-nondefault-config` | supported runtimes · MC 1.20.6-1.21.11 · Java 21 · non-default supported settings | ✅ supported | 🟡 unit only | ❌ gap | ❌ gap | [configuration tests](../src/test/java/com/asketmc/timber/TimberConfigTest.java) |
| `supported-runtime-java-newer-than-21` | supported runtimes · MC 1.20.6-1.21.11 · Java &gt;21 · defaults | ✅ supported | ❌ gap | ❌ gap | ❌ gap | — |
| `spigot-unsupported` | Spigot · MC any · Java any · any | ⛔ unsupported | — n/a | — n/a | — n/a | — |
| `folia-unsupported` | Folia · MC any · Java any · any | ⛔ unsupported | — n/a | — n/a | — n/a | — |
<!-- END GENERATED CONFIGURATION MATRIX -->

## What the levels mean

- **System smoke** starts a real server process, loads the JAR, requires a non-zero selftest result, and
  checks a clean shutdown. It does not create a player or mutate the world.
- **Gameplay E2E** requires a real player/world scenario covering detection, felling, animation, cleanup,
  stump, yield, and applicable protection decisions. No row currently meets this bar.
- **Post-deployment** downloads the public release artifact, verifies its published checksum, and then
  executes it. Pre-publication release-candidate smoke does not satisfy this column.

`unit only` is deliberately not green: server-free contract tests are valuable, but they do not establish
runtime compatibility for a server, integration, or configuration row.

## Representative baseline environment

The current baseline is intentionally narrow: official Paper, Java 21, packaged defaults, no optional
plugins, loopback/offline mode, one player slot, no RCON, and no generated structures. It is representative
only for startup/selftest/shutdown compatibility at the minimum and latest supported Paper endpoints.
It is not representative of multiplayer gameplay, WorldGuard/Towny policy, Purpur, or Pufferfish.

## Known gaps and advertised-surface drift

- No real 2×2 jungle gameplay scenario exists yet; helper/unit coverage must not be labelled E2E.
- Purpur, Pufferfish, intermediate 1.21 patches, Java versions above 21, optional hooks, and non-default
  runtime configuration combinations have no real-server evidence.
- The public Modrinth `v1.0.9` entry was observed on 2026-07-20 advertising Folia and Minecraft
  1.20-1.20.5. Those rows are outside the evidence-backed contract. Repository publication metadata is
  narrowed by this change; the already-published Modrinth record remains an explicit external correction.

## Reproduce the post-deployment check

```bash
bash scripts/verify-published-release-runtime.sh v1.0.9 post-release-evidence/v1.0.9
python3 scripts/check-configuration-matrix.py
```

The runtime verifier downloads the public GitHub Release JAR and checksum file without build credentials,
executes both Paper endpoint smokes, and emits a machine-readable receipt plus retained server logs.
