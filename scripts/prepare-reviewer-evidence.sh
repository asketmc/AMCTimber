#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only

set -euo pipefail

evidence_dir="${1:-reviewer-evidence}"

rm -rf "$evidence_dir"
mkdir -p "$evidence_dir"

version="$(bash scripts/verify-release-version.sh)"

mvn -B -ntp clean verify > "$evidence_dir/test-report.txt" 2>&1
bash scripts/prepare-release-assets.sh "$version" "$evidence_dir"
bash scripts/check-runtime-surface.sh "$evidence_dir/forbidden-api-report.txt"

{
  echo "AMCTimber dependency report"
  echo "version: $version"
  echo
  echo "Maven dependency tree:"
  mvn -B -ntp -DskipTests dependency:tree
} > "$evidence_dir/dependency-scan-report.txt" 2>&1

{
  echo "AMCTimber reviewer evidence manifest"
  echo "version: $version"
  echo "jar: AMCTimber-${version}.jar"
  echo "source: https://github.com/asketmc/AMCTimber"
  echo
  echo "Included files:"
  find "$evidence_dir" -maxdepth 1 -type f -printf '%f\n' | sort
} > "$evidence_dir/README.txt"

