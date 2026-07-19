#!/usr/bin/env python3
"""Negative regression tests for the configuration-matrix policy."""

from __future__ import annotations

from copy import deepcopy
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "check-configuration-matrix.py"
sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location("check_configuration_matrix", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ConfigurationMatrixPolicyTests(unittest.TestCase):
    def setUp(self) -> None:
        self.matrix = MODULE.read_json(MODULE.MATRIX_PATH)

    def test_current_matrix_core_is_valid(self) -> None:
        self.assertEqual([], MODULE.validate_core(self.matrix))

    def test_duplicate_row_fails_closed(self) -> None:
        candidate = deepcopy(self.matrix)
        candidate["rows"].append(deepcopy(candidate["rows"][0]))
        self.assertTrue(any("duplicate row id" in error for error in MODULE.validate_core(candidate)))

    def test_verified_status_requires_observed_evidence(self) -> None:
        candidate = deepcopy(self.matrix)
        candidate["rows"][0]["evidence"] = [
            entry for entry in candidate["rows"][0]["evidence"]
            if "system_smoke" not in entry["covers"]
        ]
        self.assertTrue(
            any("verified system_smoke lacks" in error for error in MODULE.validate_core(candidate))
        )

    def test_unsupported_row_cannot_be_green(self) -> None:
        candidate = deepcopy(self.matrix)
        row = next(row for row in candidate["rows"] if row["support"] == "unsupported")
        row["system_smoke"] = "verified"
        row["evidence"] = [{
            "kind": "github_actions_run",
            "label": "invalid evidence",
            "covers": ["system_smoke"],
            "url": "https://example.invalid/run",
        }]
        self.assertTrue(
            any("unsupported rows cannot claim" in error for error in MODULE.validate_core(candidate))
        )

    def test_markdown_renderer_matches_committed_table(self) -> None:
        document = MODULE.DOCUMENT_PATH.read_text(encoding="utf-8")
        actual = document.split(MODULE.BEGIN_MARKER, 1)[1].split(MODULE.END_MARKER, 1)[0].strip()
        self.assertEqual(MODULE.render_matrix(self.matrix).strip(), actual)

    def test_zero_test_receipt_fails_closed(self) -> None:
        release = self.matrix["release_under_test"]
        receipt = {
            "schema": "amctimber-post-release-runtime-v1",
            "result": "PASS",
            "release": {"tag": release["tag"], "commit": release["commit"]},
            "artifact": {"sha256": release["sha256"]},
            "environments": [{
                "result": "PASS",
                "selftest_passed": 0,
                "selftest_total": 0,
                "plugin_sha256": release["sha256"],
            }],
        }
        target = ROOT / "target"
        target.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=target) as temporary:
            path = Path(temporary) / "receipt.json"
            path.write_text(json.dumps(receipt), encoding="utf-8")
            self.assertTrue(
                any("selftest count is empty" in error for error in MODULE.validate_receipt(path, self.matrix))
            )


if __name__ == "__main__":
    unittest.main(verbosity=2)
