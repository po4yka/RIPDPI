#!/usr/bin/env bash
set -euo pipefail

lab_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo_root="$lab_root/.."
adb_bin="${ADB:-adb}"
serial="${ANDROID_SERIAL:-}"
netem_dev="${RIPDPI_NETEM_DEV:-wlan0}"
delay="${RIPDPI_NETEM_DELAY:-200ms}"
jitter="${RIPDPI_NETEM_JITTER:-40ms}"
loss="${RIPDPI_NETEM_LOSS:-1%}"
timeout_ms="${RIPDPI_PROBE_TIMEOUT_MS:-12000}"
out_dir="$lab_root/artifacts/rooted-emulator-netem-$(date +%Y%m%d-%H%M%S)-$(git -C "$repo_root" rev-parse --short HEAD)"
skip_start=false
skip_install=false

usage() {
  cat <<USAGE
Usage: $0 [options]

Runs emulator diagnostics before and during on-device Linux tc netem shaping.
The target must be a rooted emulator with Magisk su access.

Options:
  --serial SERIAL       required adb emulator serial (or ANDROID_SERIAL)
  --netem-dev DEV      device interface to shape, default RIPDPI_NETEM_DEV or wlan0
  --delay VALUE        netem delay, default RIPDPI_NETEM_DELAY or 200ms
  --jitter VALUE       netem jitter, default RIPDPI_NETEM_JITTER or 40ms
  --loss VALUE         netem loss, default RIPDPI_NETEM_LOSS or 1%
  --timeout-ms VALUE   debug probe timeout, default RIPDPI_PROBE_TIMEOUT_MS or 12000
  --out-dir PATH       artifact directory
  --skip-start         reuse the running test lab
  --skip-install       reuse the installed app build
  -h, --help
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial)
      serial="${2:?missing --serial value}"
      shift 2
      ;;
    --netem-dev)
      netem_dev="${2:?missing --netem-dev value}"
      shift 2
      ;;
    --delay)
      delay="${2:?missing --delay value}"
      shift 2
      ;;
    --jitter)
      jitter="${2:?missing --jitter value}"
      shift 2
      ;;
    --loss)
      loss="${2:?missing --loss value}"
      shift 2
      ;;
    --timeout-ms)
      timeout_ms="${2:?missing --timeout-ms value}"
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

case "$serial" in
  emulator-*) ;;
  *)
    echo "Refusing to run rooted-emulator netem against non-emulator serial: $serial" >&2
    exit 2
    ;;
esac

case "$netem_dev" in
  *[![:alnum:]_.:-]*|"")
    echo "Unsupported netem device name: $netem_dev" >&2
    exit 2
    ;;
esac

mkdir -p "$out_dir"
device_state_root="$(mktemp -d "${TMPDIR:-/tmp}/ripdpi-rooted-netem-device.XXXXXX")"
device_state_dir="$device_state_root/state"
device_state_captured=false
qdisc_baseline_captured=false

adb_cmd() {
  "$adb_bin" -s "$serial" "$@"
}

root_shell() {
  adb_cmd shell su -c "$1" | tr -d '\r'
}

clear_netem() {
  root_shell "tc qdisc del dev '$netem_dev' root 2>/dev/null || true" >/dev/null 2>&1 || true
}

cleanup_netem() {
  local status=${1:-0}
  if [[ "$qdisc_baseline_captured" == "true" ]]; then
    clear_netem
    if ! root_shell "tc qdisc show dev '$netem_dev'" >"$out_dir/qdisc-after-cleanup.txt" 2>/dev/null; then
      echo "Could not verify rooted-emulator qdisc cleanup" >&2
      status=1
    else
      sed -E 's/ refcnt [0-9]+//' "$out_dir/qdisc-before.txt" >"$out_dir/qdisc-before.normalized"
      sed -E 's/ refcnt [0-9]+//' "$out_dir/qdisc-after-cleanup.txt" >"$out_dir/qdisc-after.normalized"
      if ! cmp -s "$out_dir/qdisc-before.normalized" "$out_dir/qdisc-after.normalized"; then
        echo "Rooted-emulator qdisc was not restored exactly; artifacts retained at $out_dir" >&2
        status=1
      fi
    fi
  fi
  if [[ "$device_state_captured" == "true" ]]; then
    if ! python3 "$lab_root/scripts/android-device-session.py" restore \
      --adb "$adb_bin" --serial "$serial" --state-dir "$device_state_dir"; then
      echo "Failed to restore Android state; recovery backup retained at: $device_state_dir" >&2
      return 1
    fi
  fi
  if [[ $status -eq 0 ]]; then
    rm -rf "$device_state_root"
  fi
  return "$status"
}

cleanup() {
  local status=$?
  trap - EXIT
  if ! cleanup_netem "$status"; then
    status=1
  fi
  exit "$status"
}
trap cleanup EXIT

require_root() {
  local root_id
  root_id="$(root_shell id || true)"
  printf '%s\n' "$root_id" >"$out_dir/root-id.txt"
  if [[ "$root_id" != *"uid=0"* ]]; then
    echo "Root shell is unavailable on $serial; expected Magisk su access." >&2
    exit 1
  fi
}

assert_probe_ok() {
  local probe_json="$1"
  jq -e '
    (.errors | length) == 0 and
    .dns.ok == true and
    .http.ok == true and
    .https.ok == true and
    .tcp.ok == true and
    .udp.ok == true and
    .relayReady == true
  ' "$probe_json" >/dev/null
}

run_probe() {
  local name="$1"
  local probe_dir="$out_dir/$name"
  mkdir -p "$probe_dir"
  ANDROID_SERIAL="$serial" "$lab_root/scripts/adb-run-probe-emulator.sh" \
    --mode diagnostics \
    --timeout-ms "$timeout_ms" \
    --out-dir "$probe_dir" | tee "$out_dir/$name.log"
  assert_probe_ok "$probe_dir/probe-emulator-diagnostics.json"
}

python3 "$lab_root/scripts/android-device-session.py" capture \
  --adb "$adb_bin" --serial "$serial" --state-dir "$device_state_dir" \
  --package com.poyka.ripdpi --package com.poyka.ripdpi.test
device_state_captured=true

if [[ "$skip_start" != "true" ]]; then
  ANDROID_SERIAL="$serial" "$lab_root/scripts/restart-lab.sh" --profile emulator | tee "$out_dir/restart-lab.log"
fi

if [[ "$skip_install" != "true" ]]; then
  (
    cd "$repo_root"
    ANDROID_SERIAL="$serial" "$lab_root/scripts/adb-install-debug.sh"
  ) | tee "$out_dir/install.log"
fi

require_root
root_shell "ip route" >"$out_dir/ip-route.txt"
root_shell "tc qdisc show dev '$netem_dev'" >"$out_dir/qdisc-before.txt"
if ! grep -Eq '^qdisc noqueue 0: root( refcnt [0-9]+)?$' "$out_dir/qdisc-before.txt" ||
  [[ "$(wc -l <"$out_dir/qdisc-before.txt" | tr -d ' ')" != "1" ]]; then
  echo "Refusing rooted-emulator netem over a qdisc that cannot be restored exactly" >&2
  exit 1
fi
qdisc_baseline_captured=true

run_probe baseline

netem_args=(delay "$delay")
if [[ -n "$jitter" ]]; then
  netem_args+=("$jitter")
fi
netem_args+=(loss "$loss")
root_shell "tc qdisc replace dev '$netem_dev' root netem ${netem_args[*]}" >"$out_dir/tc-apply.log"
root_shell "tc qdisc show dev '$netem_dev'" >"$out_dir/qdisc-netem.txt"

run_probe netem-delay-loss

cleanup_netem 0
device_state_captured=false
qdisc_baseline_captured=false
trap - EXIT

{
  printf 'run\tverdict\terrors\tdns\thttp\thttps\ttcp\tudp\trelayReady\n'
  for run_name in baseline netem-delay-loss; do
    probe_json="$out_dir/$run_name/probe-emulator-diagnostics.json"
    jq -r --arg run "$run_name" '
      [
        $run,
        .verdict,
        (.errors | length),
        .dns.ok,
        .http.ok,
        .https.ok,
        .tcp.ok,
        .udp.ok,
        .relayReady
      ] | @tsv
    ' "$probe_json"
  done
} >"$out_dir/summary.tsv"

cat <<OUT
Rooted-emulator netem diagnostics passed.
Artifact: $out_dir
OUT
