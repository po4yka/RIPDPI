#!/usr/bin/env bash
set -euo pipefail

lab_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
adb_bin="${ADB:-adb}"
app_id="${RIPDPI_APP_ID:-com.poyka.ripdpi}"
artifact_dir="${1:-$lab_root/artifacts/logs-$(date +%Y%m%d-%H%M%S)}"
remote_output="/sdcard/Android/data/$app_id/files/probe-result.json"

mkdir -p "$artifact_dir"
"$adb_bin" logcat -d > "$artifact_dir/logcat.raw.txt" || true
"$adb_bin" shell dumpsys connectivity > "$artifact_dir/dumpsys-connectivity.txt" || true
"$adb_bin" shell dumpsys vpn > "$artifact_dir/dumpsys-vpn.txt" || true
"$adb_bin" pull "$remote_output" "$artifact_dir/probe-result.json" >/dev/null 2>&1 || true

perl -pe 's/(token|password|auth|secret|private_key|ssid|bssid|imsi|operator|subscription)=\S+/$1=<redacted>/ig' \
  "$artifact_dir/logcat.raw.txt" > "$artifact_dir/logcat.redacted.txt"

echo "$artifact_dir"
