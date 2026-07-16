#!/usr/bin/env python3
"""Build deterministic, human-facing metadata for a Modrinth release."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
SEMVER = re.compile(
    r"^(?P<core>0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"
    r"(?P<prerelease>-[0-9A-Za-z.-]+)?(?P<build>\+[0-9A-Za-z.-]+)?$"
)
PROJECT_ID = re.compile(r"^[0-9A-Za-z]{8}$")
HEADING_ICONS = {
    "Added": "✨ Added",
    "Changed": "🔄 Changed",
    "Deprecated": "⚠️ Deprecated",
    "Removed": "🗑️ Removed",
    "Fixed": "🛠️ Fixed",
    "Security": "🔐 Security",
    "Performance": "⚡ Performance",
    "Notes": "📝 Notes",
}


class PreparationError(ValueError):
    """Raised when release metadata is incomplete or inconsistent."""


def read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise PreparationError(f"cannot read JSON from {path}: {error}") from error


def parse_game_version(value: str) -> tuple[int, int, int]:
    parts = value.split(".")
    if len(parts) not in (2, 3) or any(not part.isdigit() for part in parts):
        raise PreparationError(f"unsupported Minecraft version format: {value!r}")
    numeric = tuple(int(part) for part in parts)
    return (numeric + (0,))[:3]


def validate_config(config: Any) -> dict[str, Any]:
    if not isinstance(config, dict):
        raise PreparationError("Modrinth configuration must be a JSON object")
    required_strings = (
        "project_id",
        "project_slug",
        "display_name",
        "compatibility_label",
        "release_notes_directory",
    )
    for field in required_strings:
        if not isinstance(config.get(field), str) or not config[field].strip():
            raise PreparationError(f"Modrinth configuration field {field!r} is required")
    if not PROJECT_ID.fullmatch(config["project_id"]):
        raise PreparationError("project_id must be an eight-character Modrinth ID")
    loaders = config.get("loaders")
    if (
        not isinstance(loaders, list)
        or not loaders
        or any(not isinstance(loader, str) or not loader for loader in loaders)
        or len(loaders) != len(set(loaders))
    ):
        raise PreparationError("loaders must be a non-empty list of unique names")
    game_versions = config.get("game_versions")
    if (
        not isinstance(game_versions, list)
        or not game_versions
        or any(not isinstance(version, str) or not version for version in game_versions)
        or len(game_versions) != len(set(game_versions))
    ):
        raise PreparationError("game_versions must be a non-empty list of unique names")
    parsed_versions = [parse_game_version(version) for version in game_versions]
    if parsed_versions != sorted(parsed_versions):
        raise PreparationError("game_versions must be in ascending Minecraft version order")
    return config


def select_game_versions(entries: Any, configured: list[str]) -> list[str]:
    if not isinstance(entries, list):
        raise PreparationError("Modrinth game-version response must be a JSON array")
    available: set[str] = set()
    for entry in entries:
        if not isinstance(entry, dict) or entry.get("version_type") != "release":
            continue
        value = entry.get("version")
        if not isinstance(value, str):
            continue
        try:
            parse_game_version(value)
        except PreparationError:
            continue
        available.add(value)
    missing = [version for version in configured if version not in available]
    if missing:
        raise PreparationError(
            "Modrinth does not currently advertise configured stable versions: "
            + ", ".join(missing)
        )
    return configured


def validate_loaders(entries: Any, configured: list[str]) -> list[str]:
    if not isinstance(entries, list):
        raise PreparationError("Modrinth loader response must be a JSON array")
    supported = {
        entry.get("name")
        for entry in entries
        if isinstance(entry, dict)
        and isinstance(entry.get("supported_project_types"), list)
        and "plugin" in entry["supported_project_types"]
    }
    missing = sorted(set(configured) - supported)
    if missing:
        raise PreparationError(
            f"configured Modrinth plugin loaders are unavailable: {', '.join(missing)}"
        )
    return configured


def extract_changelog(changelog: str, version: str) -> str:
    heading = re.compile(
        rf"^## \[{re.escape(version)}\](?:\s+-\s+[^\n]+)?\s*$", re.MULTILINE
    )
    match = heading.search(changelog)
    if match is None:
        raise PreparationError(f"CHANGELOG.md has no section for {version}")
    next_heading = re.search(r"^## \[", changelog[match.end() :], re.MULTILINE)
    end = len(changelog) if next_heading is None else match.end() + next_heading.start()
    body = changelog[match.end() : end].strip()
    if not body:
        raise PreparationError(f"CHANGELOG.md section for {version} is empty")
    return body


def decorate_headings(body: str) -> str:
    decorated: list[str] = []
    for line in body.splitlines():
        if line.startswith("# "):
            raise PreparationError("release-note body must not contain another level-one heading")
        if line.startswith("### "):
            title = line[4:].strip()
            line = f"## {HEADING_ICONS.get(title, title)}"
        decorated.append(line.rstrip())
    return "\n".join(decorated).strip()


def classify_version_type(version: str, marked_prerelease: bool) -> str:
    match = SEMVER.fullmatch(version)
    if match is None:
        raise PreparationError(f"invalid semantic version: {version!r}")
    suffix = (match.group("prerelease") or "").lower()
    if not suffix and not marked_prerelease:
        return "release"
    if "alpha" in suffix or "snapshot" in suffix:
        return "alpha"
    return "beta"


def release_body(root: Path, config: dict[str, Any], version: str) -> str:
    override = root / config["release_notes_directory"] / f"{version}.md"
    if override.is_file():
        body = override.read_text(encoding="utf-8").strip()
        if not body:
            raise PreparationError(f"custom release notes are empty: {override}")
        return decorate_headings(body)
    changelog = (root / "CHANGELOG.md").read_text(encoding="utf-8")
    return decorate_headings(extract_changelog(changelog, version))


def render_changelog(
    config: dict[str, Any], version: str, tag: str, changes: str
) -> str:
    release_url = f"https://github.com/asketmc/AMCTimber/releases/tag/{tag}"
    verification_url = (
        "https://github.com/asketmc/AMCTimber/blob/"
        f"{tag}/docs/VERIFY_RELEASE.md"
    )
    return f"""# 🌲 {config['display_name']} {version}

> **Verified server-side release for Paper-compatible servers.**

{changes}

---

## 🎮 Compatibility

- **Minecraft:** {config['compatibility_label']}
- **Platforms:** Paper and Purpur *(Pufferfish uses Paper compatibility)*
- **Java:** 21 or newer
- **Client installation:** none

> **Compatibility note:** Folia and Spigot are not supported.

## 🔐 Verified release

The file on this page is the exact JAR from the signed
[GitHub release]({release_url}). Before publication it passes Maven verification, jar-safety checks,
Paper runtime smoke tests on the oldest and latest stable supported lines, checksum validation,
Sigstore signing, SBOM generation, and GitHub build-provenance attestation.

- [Download checksums, signatures, SBOMs, and provenance evidence]({release_url})
- [Verify this exact tagged release]({verification_url})

For a clean upgrade, stop the server, back up `plugins/AMCTimber`, replace the old JAR, and restart.
""".strip()


def prepare_candidate(
    *,
    root: Path,
    config_path: Path,
    game_versions_path: Path,
    loaders_path: Path,
    output: Path,
    version: str,
    tag: str,
    marked_prerelease: bool,
) -> dict[str, Any]:
    if tag != f"v{version}":
        raise PreparationError(f"tag {tag!r} does not match version {version!r}")
    config = validate_config(read_json(config_path))
    game_versions = select_game_versions(
        read_json(game_versions_path),
        config["game_versions"],
    )
    loaders = validate_loaders(read_json(loaders_path), config["loaders"])
    version_type = classify_version_type(version, marked_prerelease)
    changelog = render_changelog(
        config, version, tag, release_body(root, config, version)
    )
    if len(changelog) > 100_000:
        raise PreparationError("rendered Modrinth changelog is unexpectedly large")

    payload = {
        "name": f"{config['display_name']} {version}",
        "version_number": version,
        "changelog": changelog,
        "dependencies": [],
        "game_versions": game_versions,
        "version_type": version_type,
        "loaders": loaders,
        "featured": version_type == "release",
        "status": "listed",
        "project_id": config["project_id"],
        "file_parts": ["file"],
        "primary_file": "file",
        "environment": "server_only",
    }
    payload_bytes = (
        json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    ).encode("utf-8")
    changelog_bytes = (changelog + "\n").encode("utf-8")
    metadata = {
        "schema": "amctimber-modrinth-metadata-v1",
        "project_id": config["project_id"],
        "project_slug": config["project_slug"],
        "tag": tag,
        "version": version,
        "version_type": version_type,
        "loaders": loaders,
        "game_versions": game_versions,
        "payload_sha256": hashlib.sha256(payload_bytes).hexdigest(),
        "changelog_sha256": hashlib.sha256(changelog_bytes).hexdigest(),
    }

    output.mkdir(parents=True, exist_ok=True)
    (output / "payload.json").write_bytes(payload_bytes)
    (output / "changelog.md").write_bytes(changelog_bytes)
    (output / "candidate-metadata.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return metadata


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--game-versions", type=Path, required=True)
    parser.add_argument("--loaders", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--config", type=Path, default=ROOT / ".github" / "modrinth.json"
    )
    parser.add_argument("--prerelease", action="store_true")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        metadata = prepare_candidate(
            root=ROOT,
            config_path=args.config,
            game_versions_path=args.game_versions,
            loaders_path=args.loaders,
            output=args.output,
            version=args.version,
            tag=args.tag,
            marked_prerelease=args.prerelease,
        )
    except (OSError, PreparationError) as error:
        print(f"[FAIL] {error}", file=sys.stderr)
        return 1
    print(
        "[PASS] prepared Modrinth metadata "
        f"for {metadata['tag']} ({len(metadata['game_versions'])} game versions, "
        f"{', '.join(metadata['loaders'])})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
