#!/usr/bin/env python3
"""Exercise the release tag/commit/main ancestry gate in an isolated repository."""

from __future__ import annotations

import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import textwrap


ROOT = Path(__file__).resolve().parents[1]


def run(command: list[str], cwd: Path, *, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        cwd=cwd,
        check=check,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )


def bash_command() -> str:
    configured = os.environ.get("BASH")
    if configured:
        return configured
    if os.name == "nt":
        git_bash = Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / "Git/bin/bash.exe"
        if git_bash.is_file():
            return str(git_bash)
    found = shutil.which("bash")
    if found:
        return found
    raise RuntimeError("bash is required")


def git(cwd: Path, *arguments: str) -> str:
    return run(["git", *arguments], cwd).stdout.strip()


def invoke_verifier(cwd: Path, bash: str) -> subprocess.CompletedProcess[str]:
    commit = git(cwd, "rev-parse", "HEAD")
    return run(
        [
            bash,
            "scripts/verify-release-version.sh",
            "refs/tags/v1.0.7",
            commit,
            "refs/heads/main",
        ],
        cwd,
        check=False,
    )


def main() -> int:
    bash = bash_command()
    with tempfile.TemporaryDirectory(prefix="amctimber-release-source-") as temp:
        fixture = Path(temp)
        (fixture / "scripts").mkdir()
        (fixture / "src/main/resources").mkdir(parents=True)
        shutil.copy2(ROOT / "scripts/verify-release-version.sh", fixture / "scripts")
        (fixture / "pom.xml").write_text(
            textwrap.dedent(
                """\
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.asketmc</groupId>
                  <artifactId>release-source-fixture</artifactId>
                  <version>1.0.7</version>
                </project>
                """
            ),
            encoding="utf-8",
        )
        (fixture / "src/main/resources/plugin.yml").write_text(
            "name: ReleaseSourceFixture\nversion: 1.0.7\n",
            encoding="utf-8",
        )

        git(fixture, "init", "-b", "main")
        git(fixture, "config", "user.name", "Release source test")
        git(fixture, "config", "user.email", "release-test@example.invalid")
        git(fixture, "add", ".")
        git(fixture, "commit", "-m", "main release input")
        git(fixture, "tag", "v1.0.7")

        positive = invoke_verifier(fixture, bash)
        if positive.returncode != 0 or positive.stdout.strip() != "1.0.7":
            print(positive.stdout, end="", file=sys.stderr)
            print(positive.stderr, end="", file=sys.stderr)
            print("[FAIL] main-ancestor release tag was rejected", file=sys.stderr)
            return 1

        git(fixture, "switch", "-c", "outside-main")
        git(fixture, "commit", "--allow-empty", "-m", "outside main")
        git(fixture, "tag", "--force", "v1.0.7")
        negative = invoke_verifier(fixture, bash)
        if negative.returncode == 0 or "not an ancestor" not in negative.stderr:
            print(negative.stdout, end="", file=sys.stderr)
            print(negative.stderr, end="", file=sys.stderr)
            print("[FAIL] non-main release tag was not rejected", file=sys.stderr)
            return 1

    print("[PASS] release source gate accepts main and rejects a non-main tag")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
