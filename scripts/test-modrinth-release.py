#!/usr/bin/env python3
"""Regression tests for deterministic Modrinth release metadata."""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "prepare-modrinth-release.py"
sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location("prepare_modrinth_release", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


SUPPORTED_VERSIONS = [
    "1.20",
    "1.20.1",
    "1.20.2",
    "1.20.3",
    "1.20.4",
    "1.20.5",
    "1.20.6",
    "1.21",
    "1.21.1",
    "1.21.2",
    "1.21.3",
    "1.21.4",
    "1.21.5",
    "1.21.6",
    "1.21.7",
    "1.21.8",
    "1.21.9",
    "1.21.10",
    "1.21.11",
]
GAME_VERSIONS = [
    *({"version": version, "version_type": "release"} for version in SUPPORTED_VERSIONS),
    {"version": "1.21.12-pre1", "version_type": "snapshot"},
    {"version": "1.21.12", "version_type": "release"},
    {"version": "1.22", "version_type": "release"},
]
LOADERS = [
    {"name": "paper", "supported_project_types": ["plugin", "mod"]},
    {"name": "purpur", "supported_project_types": ["plugin", "mod"]},
    {"name": "folia", "supported_project_types": ["plugin", "mod"]},
    {"name": "fabric", "supported_project_types": ["mod"]},
]


class ModrinthReleaseTests(unittest.TestCase):
    def test_selects_only_supported_stable_range(self) -> None:
        self.assertEqual(
            SUPPORTED_VERSIONS,
            MODULE.select_game_versions(GAME_VERSIONS, SUPPORTED_VERSIONS),
        )

    def test_rejects_missing_configured_stable_version(self) -> None:
        without_120 = [entry for entry in GAME_VERSIONS if entry["version"] != "1.20"]
        with self.assertRaisesRegex(MODULE.PreparationError, "configured stable versions"):
            MODULE.select_game_versions(without_120, SUPPORTED_VERSIONS)

    def test_validates_plugin_loader_tags(self) -> None:
        self.assertEqual(
            ["paper", "purpur"],
            MODULE.validate_loaders(LOADERS, ["paper", "purpur"]),
        )
        with self.assertRaisesRegex(MODULE.PreparationError, "unavailable"):
            MODULE.validate_loaders(LOADERS, ["fabric"])

    def test_extracts_exact_changelog_section(self) -> None:
        changelog = """## [Unreleased]

## [1.2.0] - 2026-07-16

### Added

- A polished release.

## [1.1.0] - 2026-07-15

- Older notes.
"""
        self.assertEqual(
            "### Added\n\n- A polished release.",
            MODULE.extract_changelog(changelog, "1.2.0"),
        )
        self.assertIn(
            "## ✨ Added",
            MODULE.decorate_headings(MODULE.extract_changelog(changelog, "1.2.0")),
        )

    def test_classifies_stable_beta_and_alpha_versions(self) -> None:
        self.assertEqual("release", MODULE.classify_version_type("1.2.0", False))
        self.assertEqual("beta", MODULE.classify_version_type("1.2.0-rc.1", True))
        self.assertEqual("alpha", MODULE.classify_version_type("1.2.0-alpha.1", True))

    def test_prepares_polished_current_release_payload(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            temporary_path = Path(temporary)
            versions_path = temporary_path / "versions.json"
            loaders_path = temporary_path / "loaders.json"
            versions_path.write_text(json.dumps(GAME_VERSIONS), encoding="utf-8")
            loaders_path.write_text(json.dumps(LOADERS), encoding="utf-8")
            output = temporary_path / "candidate"
            metadata = MODULE.prepare_candidate(
                root=ROOT,
                config_path=ROOT / ".github" / "modrinth.json",
                game_versions_path=versions_path,
                loaders_path=loaders_path,
                output=output,
                version="1.0.9",
                tag="v1.0.9",
                marked_prerelease=False,
            )
            payload = json.loads((output / "payload.json").read_text(encoding="utf-8"))
            self.assertEqual("ri1Ibgnf", payload["project_id"])
            self.assertEqual(["paper", "purpur"], payload["loaders"])
            self.assertEqual(SUPPORTED_VERSIONS, payload["game_versions"])
            self.assertNotIn("1.21.12", payload["game_versions"])
            self.assertEqual("server_only", payload["environment"])
            self.assertTrue(payload["featured"])
            self.assertIn("# 🌲 AMCTimber 1.0.9", payload["changelog"])
            self.assertIn("## 🛠️ Fixed", payload["changelog"])
            self.assertIn("## 🔐 Verified release", payload["changelog"])
            self.assertEqual("amctimber-modrinth-metadata-v1", metadata["schema"])
            self.assertEqual(
                {"candidate-metadata.json", "changelog.md", "payload.json"},
                {path.name for path in output.iterdir()},
            )


if __name__ == "__main__":
    unittest.main(verbosity=2)
