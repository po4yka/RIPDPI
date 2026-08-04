#!/usr/bin/env bash
set -euo pipefail

lab_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo_root="$lab_root/.."
# shellcheck source=test-lab/scripts/python.sh
source "$lab_root/scripts/python.sh"

adb_bin="${ADB:-adb}"
serial="${ANDROID_SERIAL:-}"
profile="${RIPDPI_LAB_PROFILE:-emulator}"
timeout_ms="${RIPDPI_PROBE_TIMEOUT_MS:-3000}"
config_path="${RIPDPI_RELAY_MATRIX_CONFIG:-$lab_root/relay/provider-matrix.example.json}"
out_dir="$lab_root/artifacts/mock-relay-matrix-$(date +%Y%m%d-%H%M%S)-$(git -C "$repo_root" rev-parse --short HEAD)"
skip_start=false
skip_install=false
python_bin=""
fault_pids=()

usage() {
  cat <<USAGE
Usage: $0 [options]

Runs the local mock-relay slice of the relay matrix against the debug network probe.
This is a controlled local harness and does not replace provider-backed relay evidence.

Options:
  --serial SERIAL       required adb serial (or ANDROID_SERIAL)
  --profile PROFILE     probe profile, default RIPDPI_LAB_PROFILE or emulator
  --timeout-ms VALUE    debug probe timeout, default RIPDPI_PROBE_TIMEOUT_MS or 3000
  --config PATH         relay matrix manifest, default RIPDPI_RELAY_MATRIX_CONFIG or example
  --out-dir PATH        artifact directory
  --skip-start          reuse the running test lab
  --skip-install        reuse the installed app build
  -h, --help
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial)
      serial="${2:?missing --serial value}"
      shift 2
      ;;
    --profile)
      profile="${2:?missing --profile value}"
      shift 2
      ;;
    --timeout-ms)
      timeout_ms="${2:?missing --timeout-ms value}"
      shift 2
      ;;
    --config)
      config_path="${2:?missing --config value}"
      shift 2
      ;;
    --out-dir)
      out_dir="${2:?missing --out-dir value}"
      shift 2
      ;;
    --skip-start)
      skip_start=true
      shift
      ;;
    --skip-install)
      skip_install=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -z "$serial" ]]; then
  echo "A target serial is required via --serial or ANDROID_SERIAL; this runner never auto-selects a target." >&2
  exit 2
fi

case "$profile" in
  emulator|device|physical) ;;
  *)
    echo "Unsupported profile for mock relay matrix: $profile" >&2
    exit 2
    ;;
esac

mkdir -p "$out_dir"
python_bin="$(ripdpi_resolve_python "mock relay matrix")"
device_state_root="$(mktemp -d "${TMPDIR:-/tmp}/ripdpi-relay-matrix-device.XXXXXX")"
device_state_dir="$device_state_root/state"
device_state_captured=false

cleanup() {
  local status=$?
  trap - EXIT
  set +e
  for pid in "${fault_pids[@]}"; do
    kill "$pid" >/dev/null 2>&1 || true
  done
  if [[ "$device_state_captured" == "true" ]]; then
    if ! python3 "$lab_root/scripts/android-device-session.py" restore \
      --adb "$adb_bin" --serial "$serial" --state-dir "$device_state_dir"; then
      echo "Failed to restore Android state; recovery backup retained at: $device_state_dir" >&2
      status=1
    fi
  fi
  if [[ $status -eq 0 ]]; then
    rm -rf "$device_state_root"
  fi
  exit "$status"
}
trap cleanup EXIT

host_for_profile() {
  if [[ "$profile" == "emulator" ]]; then
    printf '10.0.2.2'
  else
    MACBOOK_LAN_IP= "$lab_root/scripts/print-host-env.sh" | awk -F= '/^MACBOOK_LAN_IP=/{print $2}'
  fi
}

free_tcp_port() {
  "$python_bin" - <<'PY'
import socket

with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
}

start_fault_server() {
  local mode="$1"
  local port
  port="$(free_tcp_port)"
  local ready_file="$out_dir/${mode}.ready"
  local log_file="$out_dir/${mode}.server.log"

  "$python_bin" - "$mode" "$port" "$ready_file" >"$log_file" 2>&1 <<'PY' &
import pathlib
import socket
import sys
import time

mode = sys.argv[1]
port = int(sys.argv[2])
ready_file = pathlib.Path(sys.argv[3])

with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("0.0.0.0", port))
    sock.listen(1)
    ready_file.write_text(str(port), encoding="utf-8")
    conn, _ = sock.accept()
    with conn:
        if mode == "server_reset":
            sys.exit(0)
        conn.settimeout(2.0)
        try:
            conn.recv(4096)
        except OSError:
            pass
        if mode == "malformed_response":
            conn.sendall(b"not-json\n")
        elif mode == "timeout":
            time.sleep(6.0)
        else:
            conn.sendall(b'{"ok":false,"code":"UNKNOWN_MODE","message":"unknown fault"}\n')
PY
  local pid=$!
  fault_pids+=("$pid")
  for _ in {1..50}; do
    [[ -f "$ready_file" ]] && break
    sleep 0.1
  done
  if [[ ! -f "$ready_file" ]]; then
    echo "Timed out starting mock relay fault server: $mode" >&2
    cat "$log_file" >&2 || true
    exit 1
  fi
  printf '%s' "$port"
}

run_probe() {
  local scenario="$1"
  local expected_exit="$2"
  local expected_relay_ready="$3"
  shift 3
  local scenario_dir="$out_dir/$scenario"
  mkdir -p "$scenario_dir"

  set +e
  ANDROID_SERIAL="$serial" ADB="$adb_bin" "$lab_root/scripts/adb-run-probe.sh" \
    --profile "$profile" \
    --mode diagnostics \
    --timeout-ms "$timeout_ms" \
    --out-dir "$scenario_dir" \
    "$@" >"$scenario_dir/stdout.txt" 2>"$scenario_dir/stderr.txt"
  local probe_exit=$?
  set -e

  local probe_json="$scenario_dir/probe-${profile}-diagnostics.json"
  if [[ ! -f "$probe_json" ]]; then
    echo "Missing probe result for $scenario: $probe_json" >&2
    cat "$scenario_dir/stdout.txt" >&2 || true
    cat "$scenario_dir/stderr.txt" >&2 || true
    exit 1
  fi

  if [[ "$expected_exit" == "zero" && "$probe_exit" -ne 0 ]]; then
    echo "$scenario expected exit 0, got $probe_exit" >&2
    cat "$probe_json" >&2
    exit 1
  fi
  if [[ "$expected_exit" == "nonzero" && "$probe_exit" -eq 0 ]]; then
    echo "$scenario expected non-zero exit" >&2
    cat "$probe_json" >&2
    exit 1
  fi

  jq -e --argjson expected "$expected_relay_ready" '.relayReady == $expected' "$probe_json" >/dev/null
  jq -e '.dns.ok == true and .http.ok == true and .https.ok == true and .tcp.ok == true and .udp.ok == true' \
    "$probe_json" >/dev/null
  if [[ "$expected_relay_ready" == "false" ]]; then
    jq -e '.verdict == "Fail" and any(.errors[]; .stage == "relay" and .code == "RELAY_NOT_READY")' \
      "$probe_json" >/dev/null
  else
    jq -e '(.errors | length) == 0' "$probe_json" >/dev/null
  fi
}

"$lab_root/scripts/check-relay-matrix-config.sh" --config "$config_path" >"$out_dir/config-validation.txt"

python3 "$lab_root/scripts/android-device-session.py" capture \
  --adb "$adb_bin" --serial "$serial" --state-dir "$device_state_dir" \
  --package com.poyka.ripdpi --package com.poyka.ripdpi.test
device_state_captured=true

if [[ "$skip_start" != "true" ]]; then
  ANDROID_SERIAL="$serial" "$lab_root/scripts/restart-lab.sh" --profile "$profile" | tee "$out_dir/restart-lab.log"
fi

if [[ "$skip_install" != "true" ]]; then
  (
    cd "$repo_root"
    ANDROID_SERIAL="$serial" "$lab_root/scripts/adb-install-debug.sh"
  ) | tee "$out_dir/install.log"
fi

lab_host="$(host_for_profile)"
printf '%s\n' "$lab_host" >"$out_dir/lab-host.txt"

run_probe ready zero true
run_probe invalid_credentials nonzero false --relay-auth fail

malformed_port="$(start_fault_server malformed_response)"
run_probe malformed_response nonzero false --relay-endpoint "$lab_host:$malformed_port"

reset_port="$(start_fault_server server_reset)"
run_probe server_reset nonzero false --relay-endpoint "$lab_host:$reset_port"

timeout_port="$(start_fault_server timeout)"
run_probe timeout nonzero false --timeout-ms 2000 --relay-endpoint "$lab_host:$timeout_port"

{
  printf 'scenario\texitExpectation\tverdict\trelayReady\terrors\tdns\thttp\thttps\ttcp\tudp\n'
  for scenario in ready invalid_credentials malformed_response server_reset timeout; do
    probe_json="$out_dir/$scenario/probe-${profile}-diagnostics.json"
    expectation="nonzero"
    if [[ "$scenario" == "ready" ]]; then
      expectation="zero"
    fi
    jq -r --arg scenario "$scenario" --arg expectation "$expectation" '
      [
        $scenario,
        $expectation,
        .verdict,
        .relayReady,
        (.errors | length),
        .dns.ok,
        .http.ok,
        .https.ok,
        .tcp.ok,
        .udp.ok
      ] | @tsv
    ' "$probe_json"
  done
} >"$out_dir/summary.tsv"

cat <<OUT
Mock relay matrix passed.
Artifact: $out_dir
OUT
