# Security

AMCTimber is a public-source Minecraft server plugin. This document describes the project's security
posture and reporting process. It is not a formal third-party audit or certification.

## Security Posture

- No obfuscation.
- No native binaries in release jars.
- No bundled shell scripts in release jars.
- No nested jars in release jars.
- No runtime downloads or auto-updater.
- No required external services.
- No undisclosed telemetry.
- No shaded runtime libraries.
- Release jars are built by GitHub Actions from tagged source.
- Release jars include SHA256 checksums, SPDX and CycloneDX SBOMs, Sigstore bundles, GitHub artifact
  attestations, and a jar safety report.

## Sensitive Information

Do not post private server addresses, console logs with secrets, tokens, credentials, paid plugin keys,
or exploit details in public issues.

## Reporting Vulnerabilities

Use GitHub's private vulnerability reporting / Security Advisory flow for this repository if it is
available. If private reporting is not available, open a minimal public issue that does not include exploit
details and ask for a private contact path.

When reporting, include:

- AMCTimber version.
- Server software and Minecraft version.
- Minimal reproduction steps.
- Whether optional integrations such as WorldGuard or Towny are installed.
- Any relevant stack trace with secrets removed.

## Supported Versions

Security fixes target the latest public release line. Older versions may receive fixes only when the
impact and maintenance cost justify a backport.

