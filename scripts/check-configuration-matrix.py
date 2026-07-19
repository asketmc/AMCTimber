#!/usr/bin/env python3
"""Validate AMCTimber's evidence-backed configuration coverage matrix."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
MATRIX_PATH = ROOT / "docs" / "configuration-matrix.json"
DOCUMENT_PATH = ROOT / "docs" / "CONFIGURATION_MATRIX.md"
BEGIN_MARKER = "<!-- BEGIN GENERATED CONFIGURATION MATRIX -->"
END_MARKER = "<!-- END GENERATED CONFIGURATION MATRIX -->"

COVERAGE_FIELDS = ("system_smoke", "gameplay_e2e", "post_deployment")
COVERAGE_STATUSES = {"verified", "implemented", "unit_only", "gap", "not_applicable"}
SUPPORT_STATUSES = {"supported", "optional", "unsupported"}
ROW_TYPES = {"runtime", "integration", "configuration", "java", "exclusion"}
VERIFICATION_KINDS = {"github_actions_run", "repository_receipt"}
IMPLEMENTATION_KINDS = {"workflow"}

STATUS_LABELS = {
    "verified": "✅ verified",
    "implemented": "🟡 implemented",
    "unit_only": "🟡 unit only",
    "gap": "❌ gap",
    "not_applicable": "— n/a",
}
SUPPORT_LABELS = {
    "supported": "✅ supported",
    "optional": "🟡 optional",
    "unsupported": "⛔ unsupported",
}


def read_json(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as handle:
        value = json.load(handle)
    if not isinstance(value, dict):
        raise ValueError(f"{path.relative_to(ROOT)} must contain a JSON object")
    return value


def markdown_escape(value: Any) -> str:
    return str(value).replace("|", "\\|").replace(">", "&gt;")


def render_evidence(entries: list[dict[str, Any]]) -> str:
    rendered: list[str] = []
    for entry in entries:
        label = markdown_escape(entry["label"])
        if "url" in entry:
            rendered.append(f"[{label}]({entry['url']})")
        elif "path" in entry:
            rendered.append(f"[{label}](../{entry['path']})")
    return "<br>".join(rendered) if rendered else "—"


def render_matrix(data: dict[str, Any]) -> str:
    lines = [
        "| ID | Configuration | Support | System smoke | Gameplay E2E | Post-deployment | Evidence |",
        "|---|---|---|---|---|---|---|",
    ]
    for row in data["rows"]:
        configuration = (
            f"{row['runtime']} · MC {row['minecraft']} · Java {row['java']} · "
            f"{row['configuration']}"
        )
        lines.append(
            "| `{id}` | {configuration} | {support} | {system} | {gameplay} | "
            "{post} | {evidence} |".format(
                id=markdown_escape(row["id"]),
                configuration=markdown_escape(configuration),
                support=SUPPORT_LABELS[row["support"]],
                system=STATUS_LABELS[row["system_smoke"]],
                gameplay=STATUS_LABELS[row["gameplay_e2e"]],
                post=STATUS_LABELS[row["post_deployment"]],
                evidence=render_evidence(row["evidence"]),
            )
        )
    return "\n".join(lines)


def validate_core(data: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if data.get("schema") != "amctimber-configuration-matrix-v1":
        errors.append("unsupported or missing matrix schema")
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", str(data.get("last_reviewed", ""))):
        errors.append("last_reviewed must be YYYY-MM-DD")

    release = data.get("release_under_test", {})
    if not re.fullmatch(r"v\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?", str(release.get("tag", ""))):
        errors.append("release_under_test.tag is not a release tag")
    if not re.fullmatch(r"[0-9a-f]{40}", str(release.get("commit", ""))):
        errors.append("release_under_test.commit must be a full commit SHA")
    if not re.fullmatch(r"[0-9a-f]{64}", str(release.get("sha256", ""))):
        errors.append("release_under_test.sha256 must be a lowercase SHA-256")

    contract = data.get("support_contract", {})
    versions = contract.get("minecraft_versions", [])
    if not isinstance(versions, list) or not versions or len(versions) != len(set(versions)):
        errors.append("support_contract.minecraft_versions must be a non-empty unique list")
    if versions and versions[0] != "1.20.6":
        errors.append("the evidence-backed Minecraft baseline must remain 1.20.6")
    if contract.get("distribution_loaders") != ["paper", "purpur"]:
        errors.append("Modrinth loader contract must be exactly paper,purpur")
    if set(contract.get("unsupported_server_software", [])) != {"Folia", "Spigot"}:
        errors.append("Folia and Spigot must remain explicit unsupported rows")

    rows = data.get("rows")
    if not isinstance(rows, list) or not rows:
        return errors + ["rows must be a non-empty list"]

    row_ids: set[str] = set()
    supported_count = 0
    gap_count = 0
    for index, row in enumerate(rows):
        prefix = f"rows[{index}]"
        row_id = row.get("id")
        if not isinstance(row_id, str) or not re.fullmatch(r"[a-z0-9][a-z0-9.-]*", row_id):
            errors.append(f"{prefix}.id must be a stable lowercase identifier")
            continue
        if row_id in row_ids:
            errors.append(f"duplicate row id: {row_id}")
        row_ids.add(row_id)
        if row.get("row_type") not in ROW_TYPES:
            errors.append(f"{row_id}: invalid row_type")
        support = row.get("support")
        if support not in SUPPORT_STATUSES:
            errors.append(f"{row_id}: invalid support status")
        if support in {"supported", "optional"}:
            supported_count += 1

        evidence = row.get("evidence")
        if not isinstance(evidence, list):
            errors.append(f"{row_id}: evidence must be a list")
            evidence = []
        for evidence_index, entry in enumerate(evidence):
            entry_prefix = f"{row_id}.evidence[{evidence_index}]"
            if not isinstance(entry, dict):
                errors.append(f"{entry_prefix} must be an object")
                continue
            if not entry.get("label") or not entry.get("kind"):
                errors.append(f"{entry_prefix} requires kind and label")
            covers = entry.get("covers")
            if not isinstance(covers, list) or not covers or any(
                field not in COVERAGE_FIELDS for field in covers
            ):
                errors.append(f"{entry_prefix}.covers must name coverage columns")
            has_url = isinstance(entry.get("url"), str)
            has_path = isinstance(entry.get("path"), str)
            if has_url == has_path:
                errors.append(f"{entry_prefix} requires exactly one of url or path")
            if has_url and not entry["url"].startswith("https://"):
                errors.append(f"{entry_prefix}.url must use https")

        for field in COVERAGE_FIELDS:
            status = row.get(field)
            if status not in COVERAGE_STATUSES:
                errors.append(f"{row_id}: invalid {field} status")
                continue
            if status == "gap":
                gap_count += 1
            matching = [entry for entry in evidence if field in entry.get("covers", [])]
            if status == "verified" and not any(
                entry.get("kind") in VERIFICATION_KINDS for entry in matching
            ):
                errors.append(f"{row_id}: verified {field} lacks run or receipt evidence")
            if status == "implemented" and not any(
                entry.get("kind") in IMPLEMENTATION_KINDS for entry in matching
            ):
                errors.append(f"{row_id}: implemented {field} lacks workflow evidence")
            if support == "unsupported" and status != "not_applicable":
                errors.append(f"{row_id}: unsupported rows cannot claim {field} coverage")
            if support != "unsupported" and status == "not_applicable":
                errors.append(f"{row_id}: supported rows cannot mark {field} not applicable")

    if supported_count == 0:
        errors.append("matrix has no supported rows")
    if gap_count == 0:
        errors.append("matrix hides all coverage gaps")
    if "paper-1.20.6-java21-default" not in row_ids or "paper-1.21.11-java21-default" not in row_ids:
        errors.append("matrix must retain explicit minimum/latest Paper rows")
    if "folia-unsupported" not in row_ids or "spigot-unsupported" not in row_ids:
        errors.append("matrix must retain explicit unsupported Folia and Spigot rows")

    observations = data.get("distribution_observations", [])
    if not isinstance(observations, list) or not observations:
        errors.append("distribution observations must disclose current public metadata")
    elif not any(observation.get("status") == "drift" for observation in observations):
        errors.append("known public metadata drift must not be hidden")
    return errors


def validate_receipt(path: Path, data: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    receipt = read_json(path)
    release = data["release_under_test"]
    if receipt.get("schema") != "amctimber-post-release-runtime-v1":
        errors.append(f"{path.relative_to(ROOT)}: unsupported receipt schema")
    if receipt.get("result") != "PASS":
        errors.append(f"{path.relative_to(ROOT)}: receipt result is not PASS")
    if receipt.get("release", {}).get("tag") != release["tag"]:
        errors.append(f"{path.relative_to(ROOT)}: receipt tag does not match the matrix")
    if receipt.get("release", {}).get("commit") != release["commit"]:
        errors.append(f"{path.relative_to(ROOT)}: receipt commit does not match the matrix")
    if receipt.get("artifact", {}).get("sha256") != release["sha256"]:
        errors.append(f"{path.relative_to(ROOT)}: receipt artifact hash does not match the matrix")
    environments = receipt.get("environments", [])
    if not isinstance(environments, list) or not environments:
        errors.append(f"{path.relative_to(ROOT)}: receipt has no environments")
        return errors
    for environment in environments:
        if environment.get("result") != "PASS":
            errors.append(f"{path.relative_to(ROOT)}: environment result is not PASS")
        passed = environment.get("selftest_passed")
        total = environment.get("selftest_total")
        if not isinstance(passed, int) or not isinstance(total, int) or total <= 0 or passed != total:
            errors.append(f"{path.relative_to(ROOT)}: selftest count is empty or incomplete")
        if environment.get("plugin_sha256") != release["sha256"]:
            errors.append(f"{path.relative_to(ROOT)}: environment used a different plugin JAR")
    return errors


def validate_repository(data: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    contract = data["support_contract"]
    modrinth = read_json(ROOT / ".github" / "modrinth.json")
    if modrinth.get("game_versions") != contract["minecraft_versions"]:
        errors.append(".github/modrinth.json game_versions drift from the support contract")
    if modrinth.get("loaders") != contract["distribution_loaders"]:
        errors.append(".github/modrinth.json loaders drift from the support contract")

    readme = (ROOT / "README.md").read_text(encoding="utf-8")
    for required in ("1.20.6-1.21.11", "Java** | 21+", "Folia"):
        if required not in readme:
            errors.append(f"README compatibility contract is missing {required!r}")

    required_paths = (
        ROOT / ".github" / "workflows" / "post-release-runtime.yml",
        ROOT / "scripts" / "verify-published-release-runtime.sh",
    )
    for path in required_paths:
        if not path.is_file():
            errors.append(f"missing matrix producer: {path.relative_to(ROOT)}")

    release_workflow = (ROOT / ".github" / "workflows" / "release.yml").read_text(encoding="utf-8")
    for required in ("post-deployment-runtime:", "verify-published-release-runtime.sh"):
        if required not in release_workflow:
            errors.append(f"release workflow is missing {required}")

    for row in data["rows"]:
        for entry in row["evidence"]:
            path_value = entry.get("path")
            if path_value and not (ROOT / path_value).is_file():
                errors.append(f"{row['id']}: evidence path does not exist: {path_value}")
            if entry.get("kind") == "repository_receipt" and path_value:
                errors.extend(validate_receipt(ROOT / path_value, data))

    document = DOCUMENT_PATH.read_text(encoding="utf-8")
    if document.count(BEGIN_MARKER) != 1 or document.count(END_MARKER) != 1:
        errors.append("CONFIGURATION_MATRIX.md must contain one generated matrix marker pair")
    else:
        before, remainder = document.split(BEGIN_MARKER, 1)
        actual, after = remainder.split(END_MARKER, 1)
        del before, after
        if actual.strip() != render_matrix(data).strip():
            errors.append(
                "CONFIGURATION_MATRIX.md generated table is stale; run "
                "python3 scripts/check-configuration-matrix.py --write"
            )
    return errors


def write_generated_table(data: dict[str, Any]) -> None:
    document = DOCUMENT_PATH.read_text(encoding="utf-8")
    if document.count(BEGIN_MARKER) != 1 or document.count(END_MARKER) != 1:
        raise ValueError("CONFIGURATION_MATRIX.md has invalid generated markers")
    before, remainder = document.split(BEGIN_MARKER, 1)
    _, after = remainder.split(END_MARKER, 1)
    rendered = f"{before}{BEGIN_MARKER}\n{render_matrix(data)}\n{END_MARKER}{after}"
    DOCUMENT_PATH.write_text(rendered, encoding="utf-8", newline="\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write", action="store_true", help="refresh the generated Markdown table")
    args = parser.parse_args()
    try:
        data = read_json(MATRIX_PATH)
        errors = validate_core(data)
        if args.write and not errors:
            write_generated_table(data)
        errors.extend(validate_repository(data))
    except (OSError, ValueError, json.JSONDecodeError) as error:
        errors = [str(error)]
    if errors:
        for error in errors:
            print(f"[FAIL] {error}", file=sys.stderr)
        return 1
    print(f"[PASS] configuration matrix: {len(data['rows'])} rows, evidence and drift explicit")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
