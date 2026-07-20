#!/usr/bin/env python3
"""Validate AMCTimber's evidence-backed configuration coverage matrix."""

from __future__ import annotations

import argparse
import hashlib
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
SUPPORT_STATUSES = {"supported", "optional", "forward_canary", "unsupported"}
ROW_TYPES = {"runtime", "integration", "configuration", "java", "canary", "exclusion"}
VERIFICATION_KINDS = {"github_actions_run", "repository_receipt", "gameplay_receipt"}
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
SUPPORT_LABELS["forward_canary"] = "forward canary"


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

    candidate = data.get("gameplay_candidate", {})
    for field in ("implementation_commit", "harness_commit", "qabot_source_commit"):
        if not re.fullmatch(r"[0-9a-f]{40}", str(candidate.get(field, ""))):
            errors.append(f"gameplay_candidate.{field} must be a full commit SHA")
    for field in ("sha256", "qabot_production_sha256"):
        if not re.fullmatch(r"[0-9a-f]{64}", str(candidate.get(field, ""))):
            errors.append(f"gameplay_candidate.{field} must be a lowercase SHA-256")
    if not candidate.get("artifact"):
        errors.append("gameplay_candidate.artifact is required")

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
        if support == "forward_canary" and row.get("row_type") != "canary":
            errors.append(f"{row_id}: forward_canary support requires row_type canary")
        if row.get("row_type") == "canary" and support != "forward_canary":
            errors.append(f"{row_id}: canary rows require forward_canary support")

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
            if support not in {"unsupported", "forward_canary"} and status == "not_applicable":
                errors.append(f"{row_id}: supported rows cannot mark {field} not applicable")
            if (
                support == "forward_canary"
                and field != "post_deployment"
                and status == "not_applicable"
            ):
                errors.append(f"{row_id}: forward canaries must execute {field}")

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


def validate_gameplay_receipt(
    path: Path,
    data: dict[str, Any],
    entry: dict[str, Any],
) -> list[str]:
    errors: list[str] = []
    label = str(path.relative_to(ROOT))
    receipt = read_json(path)
    candidate = data["gameplay_candidate"]

    if receipt.get("schema") != "amctimber-gameplay-e2e-v1":
        errors.append(f"{label}: unsupported gameplay receipt schema")
    if receipt.get("result") != "passed" or receipt.get("fatal_error") is not None:
        errors.append(f"{label}: gameplay result is not a clean pass")
    if not isinstance(receipt.get("duration_seconds"), (int, float)) or receipt.get(
        "duration_seconds", 0
    ) <= 0:
        errors.append(f"{label}: gameplay duration must be positive")

    environment = receipt.get("environment", {})
    expected = entry.get("expected_environment")
    if not isinstance(expected, dict):
        errors.append(f"{label}: matrix evidence must declare expected_environment")
        expected = {}
    for field in ("runtime", "minecraft"):
        if environment.get(field) != expected.get(field):
            errors.append(f"{label}: {field} does not match matrix evidence")
    java_major = expected.get("java_major")
    java_version = str(environment.get("java", ""))
    if not isinstance(java_major, int) or not java_version.startswith(
        f'openjdk version "{java_major}.'
    ):
        errors.append(f"{label}: Java runtime does not match matrix evidence")
    if environment.get("online_mode") is not False:
        errors.append(f"{label}: gameplay runtime must be isolated in offline mode")
    if "VCraftQABot" not in environment.get("instrumentation", []):
        errors.append(f"{label}: VCraftQABot instrumentation is missing")
    required_instrumentation = expected.get("required_instrumentation", [])
    if not isinstance(required_instrumentation, list) or any(
        item not in environment.get("instrumentation", []) for item in required_instrumentation
    ):
        errors.append(f"{label}: required matrix instrumentation is missing")
    if not str(environment.get("server_url", "")).startswith("https://"):
        errors.append(f"{label}: server source URL must use https")
    if not re.fullmatch(r"[0-9a-f]{64}", str(environment.get("server_sha256", ""))):
        errors.append(f"{label}: server JAR is not SHA-256 bound")
    if environment.get("server_checksum_source") not in {
        "vendor_sha256",
        "vendor_md5",
        "not_published_recorded_locally",
    }:
        errors.append(f"{label}: server checksum provenance is missing")

    artifacts = receipt.get("artifacts", {})
    if artifacts.get("amctimber_file") != candidate["artifact"]:
        errors.append(f"{label}: candidate artifact name does not match the matrix")
    if artifacts.get("amctimber_sha256") != candidate["sha256"]:
        errors.append(f"{label}: candidate artifact hash does not match the matrix")
    for field in ("qabot_sha256", "harness_sha256", "latest_log_sha256"):
        if not re.fullmatch(r"[0-9a-f]{64}", str(artifacts.get(field, ""))):
            errors.append(f"{label}: {field} must be a lowercase SHA-256")
    if not isinstance(artifacts.get("additional_plugins"), list):
        errors.append(f"{label}: additional_plugins must be an explicit list")

    scenarios = receipt.get("scenarios", [])
    counters = receipt.get("counters", {})
    if not isinstance(scenarios, list) or not scenarios:
        errors.append(f"{label}: gameplay receipt discovered no scenarios")
        scenarios = []
    scenario_ids = [scenario.get("id") for scenario in scenarios if isinstance(scenario, dict)]
    if len(scenario_ids) != len(set(scenario_ids)):
        errors.append(f"{label}: gameplay scenario IDs must be unique")
    if any(scenario.get("status") != "passed" for scenario in scenarios):
        errors.append(f"{label}: at least one gameplay scenario did not pass")
    if (
        counters.get("discovered") != len(scenarios)
        or counters.get("passed") != len(scenarios)
        or counters.get("failed") != 0
        or counters.get("skipped") != 0
    ):
        errors.append(f"{label}: gameplay counters are empty, incomplete, or inconsistent")
    required_scenarios = {
        "runtime-selftest",
        "qa-hooks-default-deny",
        "sneak-bypass-vanilla-path",
        "player-build-rejected",
        "oak-fell-chop-yield-cleanup",
        "jungle-2x2-vines-stump-yield-cleanup",
        "planned-restart-yield-recovery",
        "three-bot-concurrency-soak",
        "plugin-log-health",
    }
    missing_scenarios = required_scenarios.difference(scenario_ids)
    if missing_scenarios:
        errors.append(f"{label}: missing required scenarios: {sorted(missing_scenarios)}")
    row_scenarios = expected.get("required_scenarios", [])
    if not isinstance(row_scenarios, list) or any(item not in scenario_ids for item in row_scenarios):
        errors.append(f"{label}: row-specific required scenarios are missing")

    recovery = receipt.get("restart_recovery", {})
    if (
        recovery.get("journal_schema") != "amctimber.pending-yield.v2"
        or recovery.get("journal_records") != 1
        or recovery.get("recovered_amount") != 3
        or recovery.get("actor_retained") is not True
    ):
        errors.append(f"{label}: restart recovery proof is incomplete")

    performance = receipt.get("performance", {})
    acceptance = performance.get("acceptance", {})
    tps = performance.get("tps_1m")
    mspt = performance.get("mspt")
    minimum_tps = acceptance.get("minimum_tps")
    maximum_mspt = acceptance.get("maximum_mspt")
    if not all(isinstance(value, (int, float)) for value in (tps, mspt, minimum_tps, maximum_mspt)):
        errors.append(f"{label}: performance evidence is incomplete")
    elif tps < minimum_tps or mspt > maximum_mspt:
        errors.append(f"{label}: performance acceptance was not met")

    def inspect_value(value: Any) -> None:
        if isinstance(value, dict):
            for key, child in value.items():
                if key in {"amctimber_path", "qabot_path", "runtime_path"}:
                    errors.append(f"{label}: local path key {key} must not be published")
                inspect_value(child)
        elif isinstance(value, list):
            for child in value:
                inspect_value(child)
        elif isinstance(value, str) and re.match(r"^(?:[A-Za-z]:[\\/]|\\\\)", value):
            errors.append(f"{label}: absolute local paths must not be published")

    inspect_value(receipt)
    return errors


def validate_campaign_manifest(path: Path, data: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    label = str(path.relative_to(ROOT))
    manifest = read_json(path)
    candidate = data["gameplay_candidate"]

    if manifest.get("schema") != "amctimber-local-e2e-campaign-v1":
        errors.append(f"{label}: unsupported campaign manifest schema")
    if manifest.get("github_actions_minutes_used") != 0:
        errors.append(f"{label}: campaign must not claim zero-cost local execution inaccurately")
    manifest_candidate = manifest.get("candidate", {})
    for field in ("implementation_commit", "harness_commit", "artifact", "sha256"):
        if manifest_candidate.get(field) != candidate.get(field):
            errors.append(f"{label}: candidate {field} does not match the matrix")
    qabot = manifest.get("qabot", {})
    if qabot.get("source_commit") != candidate.get("qabot_source_commit"):
        errors.append(f"{label}: QABot source commit does not match the matrix")
    if qabot.get("production_artifact_sha256") != candidate.get("qabot_production_sha256"):
        errors.append(f"{label}: QABot artifact hash does not match the matrix")

    gate = manifest.get("local_quality_gate", {})
    for count_field in ("unit_tests", "p0_tests", "pit_mutations_generated"):
        if not isinstance(gate.get(count_field), int) or gate[count_field] <= 0:
            errors.append(f"{label}: {count_field} must be a positive integer")
    for failure_field in ("unit_failures", "p0_failures"):
        if gate.get(failure_field) != 0:
            errors.append(f"{label}: {failure_field} must be zero")
    if gate.get("jacoco_scoped_gate") != "passed":
        errors.append(f"{label}: JaCoCo scoped gate did not pass")
    generated = gate.get("pit_mutations_generated")
    killed = gate.get("pit_mutations_killed")
    if not isinstance(killed, int) or not isinstance(generated, int) or killed <= 0 or killed > generated:
        errors.append(f"{label}: PIT mutation counts are invalid")

    listed_receipts = manifest.get("receipts", [])
    if not isinstance(listed_receipts, list) or not listed_receipts:
        errors.append(f"{label}: campaign has no receipt inventory")
        return errors
    listed_paths: set[str] = set()
    for index, item in enumerate(listed_receipts):
        prefix = f"{label}.receipts[{index}]"
        if not isinstance(item, dict) or not isinstance(item.get("path"), str):
            errors.append(f"{prefix}: path is required")
            continue
        receipt_name = item["path"]
        if receipt_name in listed_paths:
            errors.append(f"{prefix}: duplicate receipt path")
        listed_paths.add(receipt_name)
        receipt_path = path.parent / receipt_name
        if not receipt_path.is_file():
            errors.append(f"{prefix}: receipt file does not exist")
            continue
        actual_hash = hashlib.sha256(receipt_path.read_bytes()).hexdigest()
        if item.get("sha256") != actual_hash:
            errors.append(f"{prefix}: receipt hash does not match file content")

    referenced = {
        Path(entry["path"]).name
        for row in data["rows"]
        for entry in row["evidence"]
        if entry.get("kind") == "gameplay_receipt" and entry.get("path")
    }
    missing = referenced.difference(listed_paths)
    if missing:
        errors.append(f"{label}: matrix receipts missing from manifest: {sorted(missing)}")
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

    manifest_value = data.get("campaign_manifest")
    if not isinstance(manifest_value, str):
        errors.append("campaign_manifest path is required")
    else:
        manifest_path = ROOT / manifest_value
        if not manifest_path.is_file():
            errors.append(f"campaign manifest does not exist: {manifest_value}")
        else:
            errors.extend(validate_campaign_manifest(manifest_path, data))

    for row in data["rows"]:
        for entry in row["evidence"]:
            path_value = entry.get("path")
            if path_value and not (ROOT / path_value).is_file():
                errors.append(f"{row['id']}: evidence path does not exist: {path_value}")
            if entry.get("kind") == "repository_receipt" and path_value:
                errors.extend(validate_receipt(ROOT / path_value, data))
            if entry.get("kind") == "gameplay_receipt" and path_value:
                errors.extend(validate_gameplay_receipt(ROOT / path_value, data, entry))

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
