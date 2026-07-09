#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only

set -euo pipefail

report_file="${1:-runtime-surface-report.txt}"
source_dir="${2:-src/main/java}"

if [[ ! -d "$source_dir" ]]; then
  echo "source directory not found: $source_dir" >&2
  exit 2
fi

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

process_report="$tmp_dir/process.txt"
native_report="$tmp_dir/native.txt"
classloader_report="$tmp_dir/classloader.txt"
reflection_report="$tmp_dir/reflection.txt"
compat_probe_report="$tmp_dir/compat-probe.txt"
network_report="$tmp_dir/network.txt"

grep -R -nE 'Runtime\.getRuntime|ProcessBuilder' "$source_dir" > "$process_report" || true
grep -R -nE 'System\.load|System\.loadLibrary' "$source_dir" > "$native_report" || true
grep -R -nE 'URLClassLoader|ClassLoader|defineClass' "$source_dir" > "$classloader_report" || true
grep -R -nE 'setAccessible|getDeclared(Method|Field|Constructor)|MethodHandles\.lookup' "$source_dir" > "$reflection_report" || true
grep -R -nE 'Class\.forName' "$source_dir" > "$compat_probe_report" || true
grep -R -nE 'java\.net\.|HttpClient|URLConnection|Socket|DatagramSocket|openConnection|openStream' "$source_dir" > "$network_report" || true

{
  echo "AMCTimber runtime surface report"
  echo "source: $source_dir"
  echo
  echo "process_execution:"
  if [[ -s "$process_report" ]]; then cat "$process_report"; else echo "  none"; fi
  echo
  echo "native_library_loading:"
  if [[ -s "$native_report" ]]; then cat "$native_report"; else echo "  none"; fi
  echo
  echo "dynamic_classloading:"
  if [[ -s "$classloader_report" ]]; then cat "$classloader_report"; else echo "  none"; fi
  echo
  echo "hidden_reflection:"
  if [[ -s "$reflection_report" ]]; then cat "$reflection_report"; else echo "  none"; fi
  echo
  echo "compatibility_class_probes:"
  if [[ -s "$compat_probe_report" ]]; then cat "$compat_probe_report"; else echo "  none"; fi
  echo
  echo "runtime_network_api_references:"
  if [[ -s "$network_report" ]]; then cat "$network_report"; else echo "  none"; fi
} > "$report_file"

cat "$report_file"

if [[ -s "$process_report" || -s "$native_report" || -s "$classloader_report" || -s "$reflection_report" || -s "$network_report" ]]; then
  echo "runtime surface check failed; see $report_file" >&2
  exit 1
fi
