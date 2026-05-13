#!/usr/bin/env bash
set -euo pipefail

app_id="${RIPDPI_APP_ID:-com.poyka.ripdpi}"
adb_bin="${ADB:-adb}"

"$adb_bin" devices -l
echo
echo "App external files result path:"
echo "/sdcard/Android/data/$app_id/files/probe-result.json"
echo
"$adb_bin" shell ip route 2>/dev/null || true
