#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only

set -euo pipefail

version="${1:-}"
release_dir="${2:-release}"

if [[ -z "$version" ]]; then
  echo "usage: prepare-release-assets.sh <version> [release-dir]" >&2
  exit 2
fi

verified_version="$(bash scripts/verify-release-version.sh "v${version}")"
if [[ "$verified_version" != "$version" ]]; then
  echo "verified version mismatch: expected $version, got $verified_version" >&2
  exit 1
fi

jar="target/AMCTimber-${version}.jar"
if [[ ! -f "$jar" ]]; then
  echo "release jar not found: $jar" >&2
  exit 1
fi

mkdir -p "$release_dir"
rm -f \
  "$release_dir"/AMCTimber-*.jar \
  "$release_dir"/SHA256SUMS.txt \
  "$release_dir"/sbom.spdx.json \
  "$release_dir"/sbom.cdx.json \
  "$release_dir"/sbom.json \
  "$release_dir"/jar-safety-report.txt \
  "$release_dir"/*.sigstore.json

cp "$jar" "$release_dir/AMCTimber-${version}.jar"
bash scripts/check-release-jar.sh "$release_dir/AMCTimber-${version}.jar" "$release_dir/jar-safety-report.txt"

mvn -B -ntp -DskipTests org.spdx:spdx-maven-plugin:1.0.4:createSPDX
cp "target/site/com.asketmc_amctimber-${version}.spdx.json" "$release_dir/sbom.spdx.json"
test -s "$release_dir/sbom.spdx.json"

mvn -B -ntp -DskipTests org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom \
  -DoutputDirectory="$release_dir" \
  -DoutputName=sbom \
  -DoutputFormat=json
mv "$release_dir/sbom.json" "$release_dir/sbom.cdx.json"
test -s "$release_dir/sbom.cdx.json"

(cd "$release_dir" && sha256sum "AMCTimber-${version}.jar" sbom.*.json jar-safety-report.txt > SHA256SUMS.txt)
test -s "$release_dir/SHA256SUMS.txt"
