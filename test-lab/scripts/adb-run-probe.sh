#!/usr/bin/env bash
set -euo pipefail

lab_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
adb_bin="${ADB:-adb}"
app_id="${RIPDPI_APP_ID:-com.poyka.ripdpi}"
profile="${RIPDPI_LAB_PROFILE:-emulator}"
mode="${RIPDPI_PROBE_MODE:-vpn}"
lab_host="${MACBOOK_LAN_IP:-}"
timeout_ms="${RIPDPI_PROBE_TIMEOUT_MS:-5000}"
out_dir="$lab_root/artifacts"

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
    --lab-host|--host)
      lab_host="${2:?missing --lab-host value}"
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
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

if [[ "$profile" == "device" || "$profile" == "physical" ]]; then
  if [[ -z "$lab_host" ]]; then
    lab_host="$(MACBOOK_LAN_IP= "$lab_root/scripts/print-host-env.sh" | awk -F= '/^MACBOOK_LAN_IP=/{print $2}')"
  fi
fi

mkdir -p "$out_dir"
remote_output="/sdcard/Android/data/$app_id/files/probe-result.json"
local_output="$out_dir/probe-${profile}-${mode}.json"

broadcast=(
  "$adb_bin" shell am broadcast
  -a com.poyka.ripdpi.DEBUG_PROBE
  --es profile "$profile"
  --es mode "$mode"
  --es output "$remote_output"
  --el timeout_ms "$timeout_ms"
)

if [[ -n "$lab_host" ]]; then
  broadcast+=(--es lab_host "$lab_host")
fi

"${broadcast[@]}" >/dev/null

for _ in {1..30}; do
  if "$adb_bin" shell "test -f '$remote_output'" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

"$adb_bin" pull "$remote_output" "$local_output" >/dev/null
jq -e '.runId and .profile and .mode and .verdict and .dns and .http and .tcp' "$local_output" >/dev/null

verdict="$(jq -r '.verdict' "$local_output")"
cat "$local_output"
echo

if [[ "$verdict" == "Fail" ]]; then
  exit 1
fi
