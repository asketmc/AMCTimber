#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only

set -euo pipefail

expected_ref="${1:-}"

pom_version="$(mvn -q -DforceStdout -Dstyle.color=never help:evaluate -Dexpression=project.version)"
pom_version="${pom_version//$'\r'/}"
plugin_version="$(awk -F: '/^version:/ { gsub(/^[ \t"'\'' ]+|[ \t"'\''\r]+$/, "", $2); print $2; exit }' src/main/resources/plugin.yml)"

if [[ -z "$pom_version" ]]; then
  echo "could not read Maven project.version" >&2
  exit 1
fi

if [[ -z "$plugin_version" ]]; then
  echo "could not read src/main/resources/plugin.yml version" >&2
  exit 1
fi

if [[ "$pom_version" != "$plugin_version" ]]; then
  echo "version mismatch: pom.xml=$pom_version plugin.yml=$plugin_version" >&2
  exit 1
fi

if [[ -n "$expected_ref" ]]; then
  tag="${expected_ref#refs/tags/}"
  if [[ ! "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$ ]]; then
    echo "release tag must be SemVer-like and start with v, got: $expected_ref" >&2
    exit 1
  fi
  expected_version="${tag#v}"
  if [[ "$pom_version" != "$expected_version" ]]; then
    echo "tag/version mismatch: tag=$tag project=$pom_version" >&2
    exit 1
  fi
fi

echo "$pom_version"
