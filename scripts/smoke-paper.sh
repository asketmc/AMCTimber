#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only

set -euo pipefail

readonly PAPER_API="https://fill.papermc.io/v3/projects/paper"
readonly USER_AGENT="AMCTimber-CI/1.0 (+https://github.com/asketmc/AMCTimber)"

selector="${1:-}"
plugin_arg="${2:-}"
log_arg="${3:-}"

if [[ -z "$selector" || -z "$plugin_arg" || -z "$log_arg" || $# -ne 3 ]]; then
  echo "usage: smoke-paper.sh <minecraft-version|latest-1.21> <plugin-jar> <log-dir>" >&2
  exit 2
fi

for command_name in curl jq java sha256sum; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "required command not found: $command_name" >&2
    exit 2
  fi
done

if [[ ! -f "$plugin_arg" ]]; then
  echo "plugin jar not found: $plugin_arg" >&2
  exit 2
fi

plugin_jar="$(realpath "$plugin_arg")"
plugin_sha256="$(sha256sum "$plugin_jar" | awk '{print $1}')"
mkdir -p "$log_arg"
log_dir="$(realpath "$log_arg")"
temp_root="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
mkdir -p "$temp_root"
runtime_dir="$(mktemp -d "$temp_root/amctimber-paper.XXXXXX")"
console_log="$log_dir/console.log"
summary_file="$log_dir/smoke-summary.txt"

server_pid=""
stdin_open=false
result="FAIL"
failure_reason="script exited before completing the smoke test"
resolved_version="unresolved"
resolved_build="unresolved"
paper_sha256="unresolved"

collect_runtime_logs() {
  if [[ -d "$runtime_dir/logs" ]]; then
    mkdir -p "$log_dir/server-logs"
    cp -a "$runtime_dir/logs/." "$log_dir/server-logs/"
  fi
  if [[ -d "$runtime_dir/crash-reports" ]]; then
    mkdir -p "$log_dir/crash-reports"
    cp -a "$runtime_dir/crash-reports/." "$log_dir/crash-reports/"
  fi
}

cleanup() {
  local exit_code=$?
  local i
  trap - EXIT INT TERM
  set +e

  if [[ -n "$server_pid" ]] && kill -0 "$server_pid" 2>/dev/null; then
    if [[ "$stdin_open" == true ]]; then
      printf 'stop\n' >&3
    fi
    for ((i = 0; i < 30; i++)); do
      kill -0 "$server_pid" 2>/dev/null || break
      sleep 1
    done
    if kill -0 "$server_pid" 2>/dev/null; then
      kill -TERM "$server_pid" 2>/dev/null
      sleep 2
    fi
    if kill -0 "$server_pid" 2>/dev/null; then
      kill -KILL "$server_pid" 2>/dev/null
    fi
    wait "$server_pid" 2>/dev/null
  fi

  if [[ "$stdin_open" == true ]]; then
    exec 3>&-
    stdin_open=false
  fi

  collect_runtime_logs
  {
    echo "result=$result"
    echo "selector=$selector"
    echo "minecraft_version=$resolved_version"
    echo "paper_build=$resolved_build"
    echo "paper_sha256=$paper_sha256"
    echo "plugin_sha256=$plugin_sha256"
    echo "reason=$failure_reason"
    echo "exit_code=$exit_code"
  } > "$summary_file"

  if ((exit_code != 0)) && [[ -f "$console_log" ]]; then
    echo "--- Paper console tail ---" >&2
    tail -n 100 "$console_log" >&2
  fi

  rm -rf -- "$runtime_dir"
  exit "$exit_code"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

fail() {
  failure_reason="$1"
  echo "ERROR: $failure_reason" >&2
  exit 1
}

curl_json() {
  local url="$1"
  local output="$2"
  curl --fail --silent --show-error --location \
    --connect-timeout 20 --max-time 120 \
    --retry 4 --retry-delay 2 --retry-all-errors \
    --header "User-Agent: $USER_AGENT" \
    --output "$output" "$url"
}

selected_build_json=""
select_stable_build() {
  local version="$1"
  local safe_version="${version//[^0-9A-Za-z._-]/_}"
  local builds_file="$log_dir/paper-builds-$safe_version.json"

  if ! curl_json "$PAPER_API/versions/$version/builds" "$builds_file"; then
    return 1
  fi
  if ! jq -e 'type == "array"' "$builds_file" >/dev/null; then
    return 1
  fi

  selected_build_json="$(
    jq -c '[.[] | select(.channel == "STABLE" and .downloads["server:default"] != null)]
      | if length == 0 then empty else max_by(.id) end' "$builds_file"
  )"
  [[ -n "$selected_build_json" ]]
}

if [[ "$selector" == "latest-1.21" ]]; then
  project_file="$log_dir/paper-project.json"
  curl_json "$PAPER_API" "$project_file" || fail "could not query the Paper project index"
  jq -e '.versions["1.21"] | type == "array"' "$project_file" >/dev/null \
    || fail "Paper project index did not contain a valid 1.21 version list"

  mapfile -t candidate_versions < <(
    jq -r '.versions["1.21"][]? | select(test("^1[.]21([.][0-9]+)?$"))' "$project_file" \
      | sort -V -r \
      | awk '!seen[$0]++'
  )
  ((${#candidate_versions[@]} > 0)) || fail "the Paper project index contains no release versions in the 1.21 line"

  for version in "${candidate_versions[@]}"; do
    if select_stable_build "$version"; then
      resolved_version="$version"
      break
    fi
  done
  [[ "$resolved_version" != "unresolved" ]] || fail "no stable Paper build exists in the 1.21 release line"
elif [[ "$selector" =~ ^[0-9]+[.][0-9]+([.][0-9]+)?$ ]]; then
  resolved_version="$selector"
  select_stable_build "$resolved_version" || fail "no stable Paper build exists for $resolved_version"
else
  fail "invalid Paper selector: $selector"
fi

resolved_build="$(jq -r '.id' <<<"$selected_build_json")"
paper_url="$(jq -r '.downloads["server:default"].url' <<<"$selected_build_json")"
paper_sha256="$(jq -r '.downloads["server:default"].checksums.sha256' <<<"$selected_build_json")"
case "$paper_url" in
  https://fill-data.papermc.io/*) ;;
  *) fail "Paper API returned a non-official download URL" ;;
esac
[[ "$paper_sha256" =~ ^[0-9a-f]{64}$ ]] || fail "Paper API returned an invalid SHA-256 digest"
jq . <<<"$selected_build_json" > "$log_dir/selected-paper-build.json"

paper_jar="$runtime_dir/paper.jar"
curl --fail --silent --show-error --location \
  --connect-timeout 20 --max-time 180 \
  --retry 4 --retry-delay 2 --retry-all-errors \
  --header "User-Agent: $USER_AGENT" \
  --output "$paper_jar" "$paper_url" \
  || fail "Paper download failed"
actual_sha256="$(sha256sum "$paper_jar" | awk '{print $1}')"
[[ "$actual_sha256" == "$paper_sha256" ]] || fail "Paper download checksum mismatch"

mkdir -p "$runtime_dir/plugins"
cp "$plugin_jar" "$runtime_dir/plugins/AMCTimber.jar"
printf 'eula=true\n' > "$runtime_dir/eula.txt"
cat > "$runtime_dir/server.properties" <<'PROPERTIES'
allow-flight=false
enable-query=false
enable-rcon=false
generate-structures=false
level-name=world
level-seed=1
level-type=minecraft:normal
max-players=1
motd=AMCTimber CI smoke
online-mode=false
server-ip=127.0.0.1
server-port=25565
simulation-distance=2
spawn-protection=0
view-distance=2
PROPERTIES

stdin_fifo="$runtime_dir/server.stdin"
mkfifo "$stdin_fifo"
exec 3<> "$stdin_fifo"
stdin_open=true
(
  cd "$runtime_dir"
  exec java -Xms512M -Xmx2G \
    -Dterminal.jline=false -Dterminal.ansi=false \
    -jar paper.jar --nogui
) <&3 > "$console_log" 2>&1 &
server_pid=$!

startup_deadline=$((SECONDS + 300))
while ((SECONDS < startup_deadline)); do
  if grep -Eq 'Done \([^)]*\)! For help, type "help"' "$console_log"; then
    break
  fi
  if ! kill -0 "$server_pid" 2>/dev/null; then
    wait "$server_pid" || true
    server_pid=""
    fail "Paper exited before startup completed"
  fi
  sleep 1
done
grep -Eq 'Done \([^)]*\)! For help, type "help"' "$console_log" \
  || fail "Paper did not complete startup within 300 seconds"

command_start_line="$(wc -l < "$console_log")"
printf 'amctimber selftest\n' >&3
selftest_deadline=$((SECONDS + 60))
selftest_pattern='PASS [0-9]+/[0-9]+ AMCTimber selftest ok'
while ((SECONDS < selftest_deadline)); do
  if tail -n "+$((command_start_line + 1))" "$console_log" | grep -Eq "$selftest_pattern"; then
    break
  fi
  if tail -n "+$((command_start_line + 1))" "$console_log" | grep -Eq 'FAIL [0-9]+/[0-9]+ AMCTimber'; then
    fail "AMCTimber selftest reported FAIL"
  fi
  if ! kill -0 "$server_pid" 2>/dev/null; then
    wait "$server_pid" || true
    server_pid=""
    fail "Paper exited while waiting for the AMCTimber selftest"
  fi
  sleep 1
done
tail -n "+$((command_start_line + 1))" "$console_log" | grep -Eq "$selftest_pattern" \
  || fail "AMCTimber selftest did not report PASS within 60 seconds"

printf 'stop\n' >&3
shutdown_deadline=$((SECONDS + 90))
while ((SECONDS < shutdown_deadline)); do
  kill -0 "$server_pid" 2>/dev/null || break
  sleep 1
done
if kill -0 "$server_pid" 2>/dev/null; then
  fail "Paper did not stop cleanly within 90 seconds"
fi

set +e
wait "$server_pid"
server_exit=$?
set -e
server_pid=""
((server_exit == 0)) || fail "Paper exited with status $server_exit after the stop command"
grep -Eq 'Stopping server|Closing Server' "$console_log" \
  || fail "Paper exited without a clean shutdown marker"

result="PASS"
failure_reason="startup, AMCTimber selftest, and clean shutdown completed"
echo "Paper $resolved_version build $resolved_build: AMCTimber selftest PASS"
