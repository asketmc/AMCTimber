# Local overnight gameplay E2E — 2026-07-20

This evidence pack records a local, fail-closed campaign against the unreleased AMCTimber candidate built
from `e4cef48e17106d0fc5fc24e768db1c8d92efdd3f`. It does not rewrite the post-deployment status of the
public `v1.0.9` artifact. Heavy tests ran on a disposable Windows loopback environment and consumed no
GitHub-hosted Actions minutes.

## Outcome

| Environment | Result | Scenarios | TPS / MSPT | Receipt |
|---|---:|---:|---:|---|
| Paper 1.20.6 build 151, Java 21, ViaVersion 5.11.0 test transport | PASS | 9/9 | 20.11 / 1.84 | `paper-1.20.6-java21-viaversion.json` |
| Paper 1.21.7 build 32, Java 21 | PASS | 9/9 | 20.02 / 2.09 | `paper-1.21.7-java21.json` |
| Paper 1.21.11 build 132, Java 21, 10 concurrency cycles | PASS | 9/9 | 19.98 / 1.28 | `paper-1.21.11-java21-soak10.json` |
| Paper 1.21.11 build 132, Java 21, late event-policy fixture | PASS | 11/11 | 19.97 / 1.19 | `paper-1.21.11-java21-policy.json` |
| Purpur 1.21.11 build 2568, Java 21 | PASS | 9/9 | 20.00 / 2.07 | `purpur-1.21.11-java21.json` |
| Pufferfish 1.21.10 build 39, Java 21 | PASS | 9/9 | 19.94 / 1.12 | `pufferfish-1.21.10-java21.json` |
| Paper 1.21.11 build 132, Java 25 | PASS | 9/9 | 20.01 / 2.19 | `paper-1.21.11-java25.json` |
| Paper 26.1.2 build 74, Java 25 forward canary | PASS | 9/9 | 19.91 / 3.02 | `paper-26.1.2-java25-canary.json` |

Every normal run asserts a non-zero live selftest, default-deny QA hooks, sneak bypass, conservative
player-build rejection, real oak and 2x2 jungle break/topple/chop paths, exact yield, entity cleanup,
planned process restart with v2 journal recovery, three-player concurrency, TPS/MSPT, and plugin log health.
The policy run additionally proves that a later listener cancellation and `dropItems=false` both suppress
all AMCTimber secondary mutation, entities, and synthetic yield.

The Paper 1.21.11 soak executed ten cycles of three simultaneous trees: 30 complete concurrent tree
lifecycles in addition to the standalone oak, jungle, and restart scenarios. The published receipt exposes
any bounded retry used after an exact `attempt-time-budget` fallback; the policy run used zero retries.

## Hash binding

- AMCTimber candidate JAR: `5def03124020473ebb49fab5eaf872c6593aefac5a5262b0fbaf1d18dbd71496`
- AMCTimber implementation commit: `e4cef48e17106d0fc5fc24e768db1c8d92efdd3f`
- Final harness commit: `d1d9c0ee4ab7e483825f1f1969352a4843c4c9cb`
- VCraftQABot source commit: `fb16e86441bbe54c15cd656a6606e5e2022e1337`
- Production-default QABot JAR: `d63cf22597b5e01c76103260d4c39aee0531c0b279ad9c3dd4976fc34ec3fa3b`
- Temurin 25.0.3+9 archive: `709312cd0420296d9b9de917fe6e28a5b979e875ee5ab91783fb79bcd5857235`
- ViaVersion 5.11.0 fixture: `89db76c8e3e674238f5eee2bb7a9e9a2beeba0760bbd1b86494778e8a5a52f70`

Paper receipts bind the vendor SHA-256. Purpur publishes an MD5 receipt and the harness additionally
records the downloaded JAR SHA-256. Pufferfish's official Jenkins does not publish a checksum; its receipt
therefore says `not_published_recorded_locally` and records the locally calculated SHA-256 rather than
claiming upstream checksum verification.

## Findings resolved during the campaign

- QABot's declared optional LuckPerms dependency could still fail class linkage when LuckPerms was absent;
  the bridge is now instantiated only when the plugin is enabled and linkage failure degrades safely.
- Distant bot mining raced destination chunk availability; the bot now waits five ticks after teleport.
- Non-living `Interaction` hitboxes were invisible to the attack primitive; explicit entity filters now use
  real `Player.attack(Entity)` for plugin hitboxes.
- Cold tree scans exceeded the old 2.5 ms default. The candidate uses a 10 ms per-attempt deadline and three
  admissions per tick, retaining a 30 ms worst-case configured scan envelope.
- The ground-only yield oracle produced a false failure after bot pickup; exact yield now sums ground plus
  cleared bot inventory.
- Pufferfish 1.21.10 rejected a 1.21.11 wire client; the fixture used the exact 1.21.9 protocol snapshot and
  records its shaded JAR hash.
- Paper 1.20.6 rejected QABot's production `api-version: 1.21`; a build-only manifest override plus
  checksum-verified ViaVersion enabled the test without changing the production default.
- MCProtocolLib 26.1 renamed its respawn enum. QABot now resolves the stable wire meaning across 1.21 and
  26.1, and both production-default and forward-canary builds compile.

## Deliberate gaps

- WorldGuard and Towny have unit/invariant coverage but no live installed-plugin fixture in this campaign.
- Untested intermediate Paper patches and other Purpur/Pufferfish versions remain gaps; one representative
  intermediate Paper row and exact fork versions are reported, not whole-range certification.
- The Minecraft 26.1.2 result is a forward-compatibility canary outside the 1.20.6-1.21.11 support contract.
- Offline loopback bots do not prove proxy, authentication, real WAN, client rendering, or arbitrary shader behavior.
- Gameplay receipts cover a locally built candidate. Only the existing `v1.0.9` receipt is post-deployment
  evidence for the downloadable public release.

`manifest.json` contains receipt hashes and campaign identities. The repository matrix checker validates
each gameplay receipt's environment, artifact hash, non-zero scenario count, recovery proof, and performance
acceptance before allowing a green gameplay cell.
