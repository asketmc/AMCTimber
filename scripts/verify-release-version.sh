#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only

set -euo pipefail

expected_ref="${1:-}"
expected_commit="${2:-}"
main_ref="${3:-}"

if [[ -n "$expected_commit" || -n "$main_ref" ]]; then
  if [[ -z "$expected_ref" || -z "$expected_commit" || -z "$main_ref" ]]; then
    echo "release source verification requires tag ref, commit SHA, and main ref" >&2
    exit 2
  fi
  if [[ ! "$expected_commit" =~ ^[0-9a-f]{40}$ ]]; then
    echo "expected release commit must be a full lowercase SHA-1, got: $expected_commit" >&2
    exit 2
  fi
fi

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

  if [[ -n "$expected_commit" ]]; then
    tag_ref="refs/tags/$tag"
    if ! git show-ref --verify --quiet "$tag_ref"; then
      echo "release tag ref is missing: $tag_ref" >&2
      exit 1
    fi
    tag_commit="$(git rev-parse "${tag_ref}^{commit}")"
    head_commit="$(git rev-parse 'HEAD^{commit}')"
    if [[ "$tag_commit" != "$expected_commit" || "$head_commit" != "$expected_commit" ]]; then
      echo "release source mismatch: tag=$tag_commit HEAD=$head_commit expected=$expected_commit" >&2
      exit 1
    fi
    if ! git rev-parse --verify --quiet "${main_ref}^{commit}" >/dev/null; then
      echo "main ref is missing or invalid: $main_ref" >&2
      exit 1
    fi
    if ! git merge-base --is-ancestor "$tag_commit" "$main_ref"; then
      echo "release tag $tag is not an ancestor of $main_ref" >&2
      exit 1
    fi
  fi
fi

echo "$pom_version"
