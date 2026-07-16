#!/usr/bin/env python3
"""Fail when a release-pipeline trust boundary regresses."""

from __future__ import annotations

from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
EXPECTED_RULES = {
    "papermc": {"io.papermc.paper"},
    "spigot-public": {"net.md-5"},
    "enginehub": {
        "com.sk89q.worldedit",
        "com.sk89q.worldedit.worldedit-libs",
        "com.sk89q.worldguard",
        "com.sk89q.worldguard.worldguard-libs",
    },
    "glaremasters": {"com.palmergames.bukkit.towny"},
}
DENIED_TRANSITIVE_REPOSITORIES = {
    "apache.snapshots",
    "jvnet-nexus-snapshots",
    "ow2-snapshot",
    "sonatype-nexus-snapshots",
}


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def child_text(element: ET.Element, name: str) -> str:
    for child in element:
        if local_name(child.tag) == name:
            return (child.text or "").strip()
    return ""


def job_blocks(workflow: str) -> dict[str, str]:
    blocks: dict[str, list[str]] = {}
    current: str | None = None
    in_jobs = False
    for line in workflow.splitlines():
        if line == "jobs:":
            in_jobs = True
            continue
        if not in_jobs:
            continue
        if line and not line.startswith(" "):
            break
        match = re.match(r"^  ([A-Za-z0-9_-]+):\s*$", line)
        if match:
            current = match.group(1)
            blocks[current] = [line]
        elif current is not None:
            blocks[current].append(line)
    return {name: "\n".join(lines) for name, lines in blocks.items()}


def active_rules(path: Path) -> set[str]:
    rules = {
        line.strip()
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    }
    if any(rule.startswith(("!", "=")) or rule == "*" for rule in rules):
        raise ValueError(f"unsupported broad or modifying rule in {path.relative_to(ROOT)}")
    return rules


def main() -> int:
    errors: list[str] = []

    workflow_path = ROOT / ".github" / "workflows" / "release.yml"
    workflow = workflow_path.read_text(encoding="utf-8")
    jobs = job_blocks(workflow)
    builder = jobs.get("build-and-smoke", "")
    publisher = jobs.get("publish", "")

    if not builder or not publisher:
        errors.append("release workflow must have build-and-smoke and publish jobs")
    for forbidden in ("contents: write", "id-token: write", "attestations: write"):
        if forbidden in builder:
            errors.append(f"unprivileged build job grants {forbidden}")
    for required in (
        "contents: read",
        "mvn -B -ntp clean verify",
        "bash scripts/smoke-paper.sh",
        "refs/remotes/origin/main",
        "scripts/check-release-security.py",
        "scripts/test-release-version.py",
    ):
        if required not in builder:
            errors.append(f"unprivileged build job is missing {required}")
    for required in (
        "needs: build-and-smoke",
        "contents: write",
        "id-token: write",
        "attestations: write",
        "actions/download-artifact@3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c",
        "${{ runner.temp }}/release-candidate",
        "compare/${EXPECTED_COMMIT}...main",
        "sha256sum -c SHA256SUMS.txt",
        'gh release upload "${RELEASE_TAG}" "${assets[@]}" --repo "${GITHUB_REPOSITORY}"',
    ):
        if required not in publisher:
            errors.append(f"publisher job is missing {required}")
    for forbidden in (
        "actions/checkout@",
        "actions/setup-java@",
        "mvn ",
        "smoke-paper.sh",
        "java -jar",
    ):
        if forbidden in publisher:
            errors.append(f"publisher executes forbidden build/runtime surface: {forbidden}")

    root = ET.parse(ROOT / "pom.xml").getroot()
    properties = next(
        (node for node in root if local_name(node.tag) == "properties"), None
    )
    paper_version = "" if properties is None else child_text(properties, "paper.version")
    if not re.fullmatch(r"1\.20\.6-R0\.1-\d{8}\.\d{6}-\d+", paper_version):
        errors.append(f"Paper API must use an immutable timestamped version, got {paper_version!r}")
    if "SNAPSHOT" in (ROOT / "pom.xml").read_text(encoding="utf-8").upper():
        errors.append("pom.xml contains a mutable direct SNAPSHOT selector")

    repositories = next(
        (node for node in root if local_name(node.tag) == "repositories"), None
    )
    repository_ids = {
        child_text(node, "id")
        for node in (() if repositories is None else repositories)
        if local_name(node.tag) == "repository"
    }
    if repository_ids != set(EXPECTED_RULES):
        errors.append(
            f"custom repository IDs {sorted(repository_ids)} do not match scoped rules "
            f"{sorted(EXPECTED_RULES)}"
        )

    config = (ROOT / ".mvn" / "maven.config").read_text(encoding="utf-8")
    if "-Daether.remoteRepositoryFilter.groupId=true" not in config:
        errors.append("Maven groupId remote repository filter is not enabled")
    if "${session.rootDirectory}/.mvn/rrf/" not in config:
        errors.append("Maven groupId filters are not rooted in the project")
    if "--ignore-transitive-repositories" not in config:
        errors.append("Maven dependency POMs can still inject additional repositories")
    rule_ids = {
        path.name.removeprefix("groupId-").removesuffix(".txt")
        for path in (ROOT / ".mvn" / "rrf").glob("groupId-*.txt")
    }
    expected_rule_ids = set(EXPECTED_RULES) | DENIED_TRANSITIVE_REPOSITORIES
    if rule_ids != expected_rule_ids:
        errors.append(
            f"repository rule files {sorted(rule_ids)} do not match policy "
            f"{sorted(expected_rule_ids)}"
        )
    for repository_id, expected in EXPECTED_RULES.items():
        path = ROOT / ".mvn" / "rrf" / f"groupId-{repository_id}.txt"
        try:
            actual = active_rules(path)
        except (OSError, ValueError) as error:
            errors.append(str(error))
            continue
        if actual != expected:
            errors.append(
                f"{path.relative_to(ROOT)} has {sorted(actual)}, expected {sorted(expected)}"
            )
    for repository_id in DENIED_TRANSITIVE_REPOSITORIES:
        path = ROOT / ".mvn" / "rrf" / f"groupId-{repository_id}.txt"
        try:
            actual = active_rules(path)
        except (OSError, ValueError) as error:
            errors.append(str(error))
            continue
        if actual:
            errors.append(f"{path.relative_to(ROOT)} must deny every group ID")

    verifier = (ROOT / "scripts" / "verify-release-version.sh").read_text(
        encoding="utf-8"
    )
    for required in (
        "expected_commit",
        'git rev-parse "${tag_ref}^{commit}"',
        "git merge-base --is-ancestor",
    ):
        if required not in verifier:
            errors.append(f"release source verifier is missing {required}")

    docs = (ROOT / "docs" / "VERIFY_RELEASE.md").read_text(encoding="utf-8")
    project_version = child_text(root, "version")
    exact_identity = (
        "https://github.com/asketmc/AMCTimber/.github/workflows/"
        f"release.yml@refs/tags/v{project_version}"
    )
    if "--certificate-identity-regexp" in docs:
        errors.append("release verification still accepts a wildcard certificate identity")
    if f"--certificate-identity '{exact_identity}'" not in docs:
        errors.append("release verification is missing the exact current-tag identity")
    if f"--source-ref refs/tags/v{project_version}" not in docs:
        errors.append("release attestation example is missing the exact current tag")

    if errors:
        for error in errors:
            print(f"[FAIL] {error}", file=sys.stderr)
        return 1
    print(
        "[PASS] release build privileges, tag ancestry, immutable Paper API, "
        "repository ownership, and exact verification identity"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
