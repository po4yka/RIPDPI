#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
lab_root="$repo_root/test-lab"
# shellcheck source=test-lab/scripts/python.sh
source "$lab_root/scripts/python.sh"
python_bin="$(ripdpi_resolve_python "host DNS/UDP fault matrix")"
adb_bin="${ADB:-adb}"
profile="${RIPDPI_LAB_PROFILE:-device}"
mode="${RIPDPI_PROBE_MODE:-diagnostics}"
timeout_ms="${RIPDPI_PROBE_TIMEOUT_MS:-5000}"
serial="${ANDROID_SERIAL:-}"
skip_start=false
out_dir="$lab_root/artifacts/host-fault-matrix-$(date +%Y%m%d-%H%M%S)-$(git -C "$repo_root" rev-parse --short HEAD)"
pids=()

usage() {
  cat <<USAGE
Usage: $0 [options]

Runs a physical/emulator diagnostics matrix against host-side DNS and UDP echo fault controls.

Options:
  --profile device|physical|emulator  Probe profile, default RIPDPI_LAB_PROFILE or device
  --serial SERIAL                      ADB serial, default ANDROID_SERIAL
  --mode diagnostics                  Probe mode, default diagnostics
  --timeout-ms VALUE                  Per-probe timeout, default RIPDPI_PROBE_TIMEOUT_MS or 5000
  --skip-start                        Do not restart the local lab before running scenarios
  --out-dir PATH                      Artifact directory
  -h, --help
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile)
      profile="${2:?missing --profile value}"
      shift 2
      ;;
    --mode)
      mode="${2:?missing --mode value}"
      shift 2
      ;;
    --serial)
      serial="${2:?missing --serial value}"
      shift 2
      ;;
    --timeout-ms)
      timeout_ms="${2:?missing --timeout-ms value}"
      shift 2
      ;;
    --skip-start)
      skip_start=true
      shift
      ;;
    --out-dir)
      out_dir="${2:?missing --out-dir value}"
      shift 2
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

case "$profile" in
  device|physical|emulator) ;;
  *)
    echo "Unsupported profile for host fault matrix: $profile" >&2
    exit 2
    ;;
esac

if [[ "$mode" != "diagnostics" ]]; then
  echo "Host fault matrix currently supports diagnostics mode only." >&2
  exit 2
fi

cleanup() {
  for pid in "${pids[@]}"; do
    kill "$pid" >/dev/null 2>&1 || true
    wait "$pid" >/dev/null 2>&1 || true
  done
}
trap cleanup EXIT

detect_lab_host() {
  MACBOOK_LAN_IP= "$lab_root/scripts/print-host-env.sh" | awk -F= -v profile="$profile" '
    profile == "emulator" && $1 == "EMULATOR_LAB_HOST" { print $2; found=1 }
    profile != "emulator" && $1 == "DEVICE_LAB_HOST" { print $2; found=1 }
    END { if (!found) exit 1 }
  '
}

free_udp_port() {
  "$python_bin" - <<'PY'
import socket

with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
}

free_dual_port() {
  "$python_bin" - <<'PY'
import socket

for _ in range(100):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as tcp_sock:
        tcp_sock.bind(("127.0.0.1", 0))
        port = tcp_sock.getsockname()[1]
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp_sock:
            try:
                udp_sock.bind(("127.0.0.1", port))
            except OSError:
                continue
            print(port)
            raise SystemExit(0)
raise SystemExit("no free TCP+UDP port found")
PY
}

wait_for_log() {
  local log_file="$1"
  local pattern="$2"
  for _ in {1..50}; do
    if grep -Fq "$pattern" "$log_file" 2>/dev/null; then
      return 0
    fi
    sleep 0.1
  done
  cat "$log_file" >&2 || true
  echo "Timed out waiting for '$pattern' in $log_file" >&2
  return 1
}

check_dns_sanity() {
  local port="$1"
  local expect_udp="$2"
  "$python_bin" - "$port" "$expect_udp" "$lab_host" <<'PY'
import socket
import struct
import sys

port = int(sys.argv[1])
expect_udp = sys.argv[2] == "true"
record_ip = sys.argv[3]


def build_query(transaction_id: int) -> bytes:
    qname = b"".join(bytes([len(part)]) + part.encode("ascii") for part in "ok.test".split(".")) + b"\x00"
    return struct.pack("!HHHHHH", transaction_id, 0x0100, 1, 0, 0, 0) + qname + struct.pack("!HH", 1, 1)


def assert_answer(packet: bytes) -> None:
    if socket.inet_aton(record_ip) not in packet:
        raise SystemExit(f"missing expected A record {record_ip} in {packet!r}")


with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
    sock.settimeout(0.5)
    sock.sendto(build_query(0x1201), ("127.0.0.1", port))
    try:
        response, _ = sock.recvfrom(4096)
    except socket.timeout:
        if expect_udp:
            raise
    else:
        if not expect_udp:
            raise SystemExit(f"expected DNS UDP drop, got {response!r}")
        assert_answer(response)

tcp_query = build_query(0x1202)
with socket.create_connection(("127.0.0.1", port), timeout=0.5) as sock:
    sock.sendall(struct.pack("!H", len(tcp_query)) + tcp_query)
    prefix = sock.recv(2)
    response_length = struct.unpack("!H", prefix)[0]
    response = sock.recv(response_length)
assert_answer(response)
PY
}

check_udp_sanity() {
  local port="$1"
  local expect_udp="$2"
  "$python_bin" - "$port" "$expect_udp" <<'PY'
import socket
import sys

port = int(sys.argv[1])
expect_udp = sys.argv[2] == "true"
payload = b"ripdpi-host-fault-matrix"
with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
    sock.settimeout(0.5)
    sock.sendto(payload, ("127.0.0.1", port))
    try:
        data, _ = sock.recvfrom(65535)
    except socket.timeout:
        if expect_udp:
            raise
    else:
        if not expect_udp:
            raise SystemExit(f"expected UDP echo drop, got {data!r}")
        if data != payload:
            raise SystemExit(f"echo mismatch: {data!r}")
PY
}

start_scenario_helpers() {
  local scenario_dir="$1"
  local dns_drop_every="$2"
  local udp_drop_every="$3"
  dns_port="$(free_dual_port)"
  udp_port="$(free_udp_port)"
  dns_log="$scenario_dir/host-dns.log"
  udp_log="$scenario_dir/host-udp-echo.log"

  RIPDPI_DNS_HOST=0.0.0.0 \
    RIPDPI_DNS_PORT="$dns_port" \
    RIPDPI_LAB_RECORD_IP="$lab_host" \
    RIPDPI_DNS_UDP_DROP_EVERY="$dns_drop_every" \
    "$python_bin" "$lab_root/scripts/host-dns.py" >"$dns_log" 2>&1 &
  pids+=("$!")

  RIPDPI_UDP_ECHO_HOST=0.0.0.0 \
    RIPDPI_UDP_ECHO_PORT="$udp_port" \
    RIPDPI_UDP_ECHO_DROP_EVERY="$udp_drop_every" \
    "$python_bin" "$lab_root/scripts/host-udp-echo.py" >"$udp_log" 2>&1 &
  pids+=("$!")

  wait_for_log "$dns_log" "host dns listening"
  wait_for_log "$udp_log" "host udp echo listening"
}

assert_probe() {
  local probe_json="$1"
  local expected_dns_transport="$2"
  local expected_udp_ok="$3"
  jq -e \
    --arg expected_dns_transport "$expected_dns_transport" \
    --argjson expected_udp_ok "$expected_udp_ok" \
    '
      .verdict != "Fail" and
      .dns.ok == true and
      .dns.transport == $expected_dns_transport and
      .dns.fallbackErrorCode == null and
      .http.ok == true and
      .https.ok == true and
      .tcp.ok == true and
      .relayReady == true and
      .udp.ok == $expected_udp_ok and
      (if $expected_udp_ok then (.udp.errorCode == null and (.errors | length) == 0) else (.udp.errorCode == "SOCKETTIMEOUTEXCEPTION") end)
    ' "$probe_json" >/dev/null
}

run_scenario() {
  local name="$1"
  local dns_drop_every="$2"
  local udp_drop_every="$3"
  local expected_dns_transport="$4"
  local expected_udp_ok="$5"
  local scenario_dir="$out_dir/$name"
  mkdir -p "$scenario_dir"

  start_scenario_helpers "$scenario_dir" "$dns_drop_every" "$udp_drop_every"
  check_dns_sanity "$dns_port" "$([[ "$dns_drop_every" == "0" ]] && echo true || echo false)"
  check_udp_sanity "$udp_port" "$([[ "$udp_drop_every" == "0" ]] && echo true || echo false)"

  ANDROID_SERIAL="$serial" "$lab_root/scripts/adb-run-probe.sh" \
    --profile "$profile" \
    --mode "$mode" \
    --lab-host "$lab_host" \
    --timeout-ms "$timeout_ms" \
    --dns-server "$lab_host" \
    --dns-port "$dns_port" \
    --udp-host "$lab_host" \
    --udp-port "$udp_port" \
    --out-dir "$scenario_dir" >"$scenario_dir/probe.log"

  probe_json="$scenario_dir/probe-${profile}-${mode}.json"
  assert_probe "$probe_json" "$expected_dns_transport" "$expected_udp_ok"
  jq -r \
    --arg scenario "$name" \
    '[ $scenario, .verdict, .dns.transport, (.dns.latencyMs|tostring), (.udp.ok|tostring), (.udp.errorCode // "null"), (.errors|length|tostring) ] | @tsv' \
    "$probe_json" >>"$out_dir/summary.tsv"
}

mkdir -p "$out_dir"
lab_host="$(detect_lab_host)"
if [[ -z "$serial" ]]; then
  echo "ANDROID_SERIAL or --serial is required; the fault matrix never auto-selects a target." >&2
  exit 2
fi

if [[ "$skip_start" != "true" ]]; then
  "$lab_root/scripts/restart-lab.sh" --profile "$profile" >"$out_dir/restart-lab.log"
fi

"$adb_bin" devices -l >"$out_dir/adb-devices.txt"
{
  printf 'scenario\tverdict\tdns_transport\tdns_latency_ms\tudp_ok\tudp_error\terror_count\n'
} >"$out_dir/summary.tsv"

run_scenario baseline 0 0 udp true
run_scenario dns_udp_drop 1 0 tcp true
run_scenario udp_echo_drop 0 1 udp false
run_scenario dns_udp_and_udp_echo_drop 1 1 tcp false

cat "$out_dir/summary.tsv"
echo "Host DNS/UDP fault matrix passed. Artifacts: $out_dir"
