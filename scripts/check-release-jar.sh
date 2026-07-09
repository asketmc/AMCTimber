#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only

set -euo pipefail

jar_file="${1:-}"
report_file="${2:-jar-safety-report.txt}"

if [[ -z "$jar_file" ]]; then
  echo "usage: check-release-jar.sh <jar> [report-file]" >&2
  exit 2
fi

if [[ ! -f "$jar_file" ]]; then
  echo "jar not found: $jar_file" >&2
  exit 2
fi

tmp_listing="$(mktemp)"
tmp_blocked="$(mktemp)"
trap 'rm -f "$tmp_listing" "$tmp_blocked"' EXIT

jar tf "$jar_file" > "$tmp_listing"

entry_count="$(wc -l < "$tmp_listing" | tr -d ' ')"
plugin_yml_count="$(grep -c '^plugin.yml$' "$tmp_listing" || true)"
relocated_bstats_count="$(grep -c '^com/asketmc/timber/lib/bstats/' "$tmp_listing" || true)"

grep -E -i '(^|/).*\.(dll|so|dylib|exe|bat|cmd|ps1|vbs|sh|jar)$' "$tmp_listing" > "$tmp_blocked" || true
if [[ -s "$tmp_blocked" ]]; then
  {
    echo "AMCTimber release jar safety report"
    echo "status: failed"
    echo "jar: $jar_file"
    echo "reason: native binary, script, or nested jar entries found"
    cat "$tmp_blocked"
  } > "$report_file"
  cat "$report_file" >&2
  exit 1
fi

grep -E -i '(^|/)META-INF/.*\.(SF|DSA|RSA)$' "$tmp_listing" > "$tmp_blocked" || true
if [[ -s "$tmp_blocked" ]]; then
  {
    echo "AMCTimber release jar safety report"
    echo "status: failed"
    echo "jar: $jar_file"
    echo "reason: dependency signature metadata found after shading"
    cat "$tmp_blocked"
  } > "$report_file"
  cat "$report_file" >&2
  exit 1
fi

if [[ "$plugin_yml_count" != "1" ]]; then
  {
    echo "AMCTimber release jar safety report"
    echo "status: failed"
    echo "jar: $jar_file"
    echo "reason: expected exactly one plugin.yml, found $plugin_yml_count"
  } > "$report_file"
  cat "$report_file" >&2
  exit 1
fi

{
  echo "AMCTimber release jar safety report"
  echo "status: passed"
  echo "jar: $jar_file"
  echo "entries: $entry_count"
  echo "plugin_yml: present"
  echo "native_binaries: none"
  echo "script_files: none"
  echo "nested_jars: none"
  echo "shaded_signature_metadata: none"
  echo "relocated_bstats_entries: $relocated_bstats_count"
} > "$report_file"

cat "$report_file"
