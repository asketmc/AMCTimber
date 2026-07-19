#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only

set -euo pipefail

readonly REPOSITORY="asketmc/AMCTimber"
readonly USER_AGENT="AMCTimber-post-release-verifier/1.0 (+https://github.com/asketmc/AMCTimber)"

tag="${1:-}"
output_arg="${2:-}"
if [[ -z "$tag" || -z "$output_arg" || $# -ne 2 ]]; then
  echo "usage: verify-published-release-runtime.sh <vX.Y.Z> <output-dir>" >&2
  exit 2
fi
if [[ ! "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.-]+)?$ ]]; then
  echo "invalid release tag: $tag" >&2
  exit 2
fi
for command_name in awk curl date find git grep head java jq mktemp realpath sha256sum stat tail uname; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "required command not found: $command_name" >&2
    exit 2
  fi
done

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd -- "$script_dir/.." && pwd)"
version="${tag#v}"
jar_name="AMCTimber-${version}.jar"
base_url="https://github.com/${REPOSITORY}/releases/download/${tag}"

mkdir -p "$output_arg"
output_dir="$(realpath "$output_arg")"
if find "$output_dir" -mindepth 1 -maxdepth 1 -print -quit | grep -q .; then
  echo "output directory must be empty: $output_dir" >&2
  exit 2
fi

temp_root="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
mkdir -p "$temp_root"
download_dir="$(mktemp -d "$temp_root/amctimber-published.XXXXXX")"
cleanup() {
  rm -rf -- "$download_dir"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

download() {
  local url="$1"
  local destination="$2"
  curl --fail --silent --show-error --location \
    --proto '=https' --tlsv1.2 \
    --connect-timeout 20 --max-time 120 \
    --retry 4 --retry-delay 2 --retry-all-errors \
    --header "User-Agent: $USER_AGENT" \
    --output "$destination" "$url"
}

jar="$download_dir/$jar_name"
checksums="$download_dir/SHA256SUMS.txt"
release_json="$download_dir/release.json"
commit_json="$download_dir/commit.json"
download "https://api.github.com/repos/${REPOSITORY}/releases/tags/${tag}" "$release_json"
download "https://api.github.com/repos/${REPOSITORY}/commits/${tag}" "$commit_json"
download "$base_url/$jar_name" "$jar"
download "$base_url/SHA256SUMS.txt" "$checksums"

if ! jq -e \
  --arg tag "$tag" \
  --arg jar "$jar_name" \
  '.draft == false
   and .tag_name == $tag
   and ([.assets[].name] | index($jar) != null)
   and ([.assets[].name] | index("SHA256SUMS.txt") != null)' \
  "$release_json" >/dev/null; then
  echo "public release identity or required assets are invalid" >&2
  exit 1
fi
release_commit="$(jq -r .sha "$commit_json")"
if [[ ! "$release_commit" =~ ^[0-9a-f]{40}$ ]]; then
  echo "public release tag did not resolve to a commit" >&2
  exit 1
fi

expected_sha256="$(
  awk -v target="$jar_name" '
    NF == 2 { name=$2; sub(/^\*/, "", name); if (name == target) print $1 }
  ' "$checksums"
)"
if [[ ! "$expected_sha256" =~ ^[0-9a-f]{64}$ ]]; then
  echo "published checksum file has no unique SHA-256 for $jar_name" >&2
  exit 1
fi
actual_sha256="$(sha256sum "$jar" | awk '{print $1}')"
if [[ "$actual_sha256" != "$expected_sha256" ]]; then
  echo "published JAR checksum mismatch" >&2
  exit 1
fi
jar_size="$(stat --format='%s' "$jar")"
if ((jar_size <= 0 || jar_size > 20971520)); then
  echo "published JAR size is outside the accepted range: $jar_size" >&2
  exit 1
fi

summary_value() {
  local key="$1"
  local summary="$2"
  awk -F= -v key="$key" '$1 == key {sub(/^[^=]*=/, ""); print; found++} END {if (found != 1) exit 1}' "$summary"
}

started_at="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
harness_commit="$(git -C "$root" rev-parse HEAD)"
harness_dirty=false
if [[ -n "$(git -C "$root" status --porcelain --untracked-files=no)" ]]; then
  harness_dirty=true
fi
harness_sha256="$(sha256sum "$script_dir/verify-published-release-runtime.sh" | awk '{print $1}')"
smoke_entrypoint="$script_dir/smoke-paper.sh"
if [[ "${OS:-}" == "Windows_NT" ]]; then
  for command_name in cygpath powershell.exe; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
      echo "required Windows smoke command not found: $command_name" >&2
      exit 2
    fi
  done
  smoke_entrypoint="$script_dir/smoke-paper.ps1"
fi
smoke_sha256="$(sha256sum "$smoke_entrypoint" | awk '{print $1}')"
java_version="$(java -version 2>&1 | head -n 1)"
os_name="$(uname -s)"
os_arch="$(uname -m)"

run_smoke() {
  local selector="$1"
  local plugin_jar="$2"
  local smoke_dir="$3"
  if [[ "${OS:-}" == "Windows_NT" ]]; then
    powershell.exe -NoProfile -ExecutionPolicy Bypass \
      -File "$(cygpath -w "$smoke_entrypoint")" \
      -Selector "$selector" \
      -PluginJar "$(cygpath -w "$plugin_jar")" \
      -LogDir "$(cygpath -w "$smoke_dir")"
  else
    bash "$smoke_entrypoint" "$selector" "$plugin_jar" "$smoke_dir"
  fi
}

environment_files=()
for selector in 1.20.6 latest-1.21; do
  safe_selector="${selector//[^0-9A-Za-z._-]/_}"
  smoke_dir="$output_dir/smoke/$safe_selector"
  run_smoke "$selector" "$jar" "$smoke_dir"
  summary="$smoke_dir/smoke-summary.txt"
  if [[ "$(summary_value result "$summary")" != "PASS" ]]; then
    echo "runtime smoke did not pass for $selector" >&2
    exit 1
  fi
  if [[ "$(summary_value exit_code "$summary")" != "0" ]]; then
    echo "runtime smoke exited non-zero for $selector" >&2
    exit 1
  fi
  if [[ "$(summary_value plugin_sha256 "$summary")" != "$expected_sha256" ]]; then
    echo "runtime smoke used the wrong plugin JAR for $selector" >&2
    exit 1
  fi
  selftest="$(grep -Eo 'PASS [0-9]+/[0-9]+ AMCTimber selftest ok' "$smoke_dir/console.log" | tail -n 1)"
  if [[ ! "$selftest" =~ ^PASS\ ([0-9]+)/([0-9]+)\ AMCTimber\ selftest\ ok$ ]]; then
    echo "runtime smoke lacks a parseable selftest result for $selector" >&2
    exit 1
  fi
  selftest_passed="${BASH_REMATCH[1]}"
  selftest_total="${BASH_REMATCH[2]}"
  if ((selftest_total <= 0 || selftest_passed != selftest_total)); then
    echo "runtime selftest was empty or incomplete for $selector" >&2
    exit 1
  fi

  environment_file="$download_dir/environment-$safe_selector.json"
  jq -n \
    --arg id "paper-$safe_selector" \
    --arg selector "$selector" \
    --arg minecraft_version "$(summary_value minecraft_version "$summary")" \
    --arg paper_build "$(summary_value paper_build "$summary")" \
    --arg paper_sha256 "$(summary_value paper_sha256 "$summary")" \
    --arg plugin_sha256 "$(summary_value plugin_sha256 "$summary")" \
    --argjson selftest_passed "$selftest_passed" \
    --argjson selftest_total "$selftest_total" \
    '{
      id: $id,
      selector: $selector,
      server_software: "Paper",
      minecraft_version: $minecraft_version,
      server_build: $paper_build,
      server_sha256: $paper_sha256,
      plugin_sha256: $plugin_sha256,
      selftest_passed: $selftest_passed,
      selftest_total: $selftest_total,
      result: "PASS"
    }' > "$environment_file"
  environment_files+=("$environment_file")
done

completed_at="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
jq -n \
  --arg repository "$REPOSITORY" \
  --arg tag "$tag" \
  --arg release_commit "$release_commit" \
  --arg artifact "$jar_name" \
  --arg source_url "$base_url/$jar_name" \
  --arg sha256 "$expected_sha256" \
  --argjson size_bytes "$jar_size" \
  --arg harness_commit "$harness_commit" \
  --argjson harness_dirty "$harness_dirty" \
  --arg harness_sha256 "$harness_sha256" \
  --arg smoke_entrypoint "$(basename "$smoke_entrypoint")" \
  --arg smoke_sha256 "$smoke_sha256" \
  --arg started_at "$started_at" \
  --arg completed_at "$completed_at" \
  --arg os "$os_name" \
  --arg architecture "$os_arch" \
  --arg java "$java_version" \
  --slurpfile first "${environment_files[0]}" \
  --slurpfile second "${environment_files[1]}" \
  '{
    schema: "amctimber-post-release-runtime-v1",
    result: "PASS",
    repository: $repository,
    release: {tag: $tag, commit: $release_commit},
    artifact: {
      name: $artifact,
      source_url: $source_url,
      sha256: $sha256,
      size_bytes: $size_bytes
    },
    harness: {
      commit: $harness_commit,
      dirty: $harness_dirty,
      verifier_sha256: $harness_sha256,
      smoke_entrypoint: $smoke_entrypoint,
      smoke_sha256: $smoke_sha256
    },
    execution: {
      started_at: $started_at,
      completed_at: $completed_at,
      os: $os,
      architecture: $architecture,
      java: $java
    },
    environments: [$first[0], $second[0]]
  }' > "$output_dir/post-release-runtime-receipt.json"

jq -e '
  .result == "PASS"
  and (.environments | length == 2)
  and all(.environments[]; .result == "PASS" and .selftest_total > 0 and .selftest_passed == .selftest_total)
' "$output_dir/post-release-runtime-receipt.json" >/dev/null

echo "Post-release runtime verification PASS for $tag ($expected_sha256)"
