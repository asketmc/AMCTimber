## Summary

Describe the user-visible or operational outcome and the reason for the change.

## Validation

- [ ] `mvn -B -ntp clean verify`
- [ ] Changed configuration, permissions, and documentation were reviewed together
- [ ] Runtime evidence, if claimed, identifies the exact jar, server software, Minecraft version, and run
- [ ] No runtime-smoke result is marked implemented unless a committed dedicated workflow produces it

## Compatibility And Risk

- [ ] Public compatibility remains Paper, Purpur, and Pufferfish 1.20.6-1.21.11 only
- [ ] Player-build detection is described as heuristic, not guaranteed protection
- [ ] Security and supply-chain evidence is described as verification, not certification or proof of safety
- [ ] Rollback, shutdown, migration, and operator-facing behavior are documented where affected

## Evidence

Link relevant CI runs, issue context, logs, screenshots, or release artifacts. Redact secrets and private
server details.
