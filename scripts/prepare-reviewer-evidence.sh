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

mkdir -p "$evidence_dir/qa"
cp -R target/site/jacoco "$evidence_dir/qa/jacoco"
cp -R target/pit-reports "$evidence_dir/qa/pit-reports"
cp -R target/surefire-reports "$evidence_dir/qa/surefire-reports"
cp -R target/surefire-p0-reports "$evidence_dir/qa/surefire-p0-reports"
cp docs/P0_TEST_MATRIX.md "$evidence_dir/qa/P0_TEST_MATRIX.md"
cp docs/p0-test-matrix.csv "$evidence_dir/qa/p0-test-matrix.csv"

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
  find "$evidence_dir" -maxdepth 4 -type f -printf '%P\n' | sort
} > "$evidence_dir/README.txt"
