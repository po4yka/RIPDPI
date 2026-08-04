#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
lab_root="$(cd "$script_dir/.." && pwd)"
profile="${RIPDPI_LAB_PROFILE:-emulator}"
timeout_ms="${RIPDPI_PROBE_TIMEOUT_MS:-5000}"
out_dir=""
skip_install=false
skip_start=false
keep_lab=false
skip_maestro=false
maestro_bin=""
android_serial="${ANDROID_SERIAL:-}"
device_state_captured=false

usage() {
  cat <<USAGE
Usage: $0 [options]

Options:
  --profile emulator|device   Lab/device profile to use. Default: emulator.
  --timeout-ms VALUE          Debug probe timeout in milliseconds. Default: 5000.
  --out-dir PATH              Artifact directory. Default: test-lab/artifacts/vpn-e2e-<timestamp>.
  --skip-install              Do not install the debug APK.
  --skip-start                Do not restart the Docker lab.
  --keep-lab                  Leave the Docker lab running after the smoke.
  --skip-maestro              Do not run Maestro connect/disconnect flows.
  -h, --help                  Show this help.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile)
      profile="${2:?missing --profile value}"
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
    --skip-install)
      skip_install=true
      shift
      ;;
    --skip-start)
      skip_start=true
      shift
      ;;
    --keep-lab)
      keep_lab=true
      shift
      ;;
    --skip-maestro)
      skip_maestro=true
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

case "$profile" in
  emulator|device|physical) ;;
  *)
    echo "Unsupported profile: $profile" >&2
    exit 2
    ;;
esac

if [[ -z "$android_serial" ]]; then
  echo "ANDROID_SERIAL is required; destructive device tests never auto-select a target." >&2
  exit 2
fi

if [[ -z "$out_dir" ]]; then
  out_dir="$lab_root/artifacts/vpn-e2e-$(date +%Y%m%d-%H%M%S)"
fi
mkdir -p "$out_dir"
device_state_root="$(mktemp -d "${TMPDIR:-/tmp}/ripdpi-vpn-device-state.XXXXXX")"
device_state_dir="$device_state_root/session"

maestro_ran=false
lab_started=false
archive_path=""

resolve_maestro() {
  if [[ -n "${MAESTRO_BIN:-}" ]]; then
    if [[ -x "$MAESTRO_BIN" ]]; then
      maestro_bin="$MAESTRO_BIN"
      return 0
    fi
    echo "MAESTRO_BIN is set but is not executable: $MAESTRO_BIN" >&2
    return 1
  fi

  if maestro_bin="$(command -v maestro 2>/dev/null)"; then
    return 0
  fi

  local default_bin="$HOME/.maestro/bin/maestro"
  if [[ -x "$default_bin" ]]; then
    maestro_bin="$default_bin"
    return 0
  fi

  return 1
}

seed_debug_automation_state() {
  adb shell am start \
    -n com.poyka.ripdpi/.activities.MainActivity \
    --ez com.poyka.ripdpi.automation.ENABLED true \
    --ez com.poyka.ripdpi.automation.RESET_STATE true \
    --ez com.poyka.ripdpi.automation.DISABLE_MOTION true \
    --es com.poyka.ripdpi.automation.PERMISSION_PRESET granted \
    --es com.poyka.ripdpi.automation.SERVICE_PRESET live \
    --es com.poyka.ripdpi.automation.DATA_PRESET settings_ready >/dev/null
}

resume_debug_automation_state() {
  adb shell am start \
    -n com.poyka.ripdpi/.activities.MainActivity \
    --ez com.poyka.ripdpi.automation.ENABLED true \
    --ez com.poyka.ripdpi.automation.RESET_STATE false \
    --ez com.poyka.ripdpi.automation.DISABLE_MOTION true \
    --es com.poyka.ripdpi.automation.PERMISSION_PRESET granted \
    --es com.poyka.ripdpi.automation.SERVICE_PRESET live \
    --es com.poyka.ripdpi.automation.DATA_PRESET settings_ready >/dev/null
}

grant_runtime_permissions() {
  adb shell pm grant com.poyka.ripdpi android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
}

wake_unlock_device() {
  "$script_dir/adb-wake-unlock.sh"
}

run_maestro_flow() {
  local flow="$1"
  local maestro_args=(test)
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    maestro_args+=(--udid "$ANDROID_SERIAL")
  fi
  "$maestro_bin" "${maestro_args[@]}" "$flow"
}

assert_vpn_service_stopped() {
  local service_state
  service_state="$(
    adb shell dumpsys activity services com.poyka.ripdpi |
      rg 'RipDpi(Proxy|Vpn)Service|isForeground|ServiceRecord' || true
  )"
  if [[ -n "$service_state" ]]; then
    printf '%s\n' "$service_state" > "$out_dir/service-leak.txt"
    echo "VPN disconnect flow left a RIPDPI foreground service running; see $out_dir/service-leak.txt" >&2
    return 1
  fi
}

collect_failure_artifacts() {
  local status="$1"
  local logs_dir="$out_dir/device-logs"

  echo "VPN E2E failed with status $status; collecting artifacts in $out_dir" >&2
  "$script_dir/adb-collect-logs.sh" "$logs_dir" >/dev/null 2>&1 || true
  if [[ -d "$lab_root/artifacts" ]]; then
    find "$lab_root/artifacts" -maxdepth 1 -type f -name 'probe-*.json' -exec cp {} "$out_dir/" \; 2>/dev/null || true
    [[ -f "$lab_root/artifacts/lab-env.sh" ]] && cp "$lab_root/artifacts/lab-env.sh" "$out_dir/" || true
  fi
  archive_path="$out_dir.tar.gz"
  tar -czf "$archive_path" -C "$(dirname "$out_dir")" "$(basename "$out_dir")" 2>/dev/null || true
  if [[ -f "$archive_path" ]]; then
    echo "Archived failure artifacts: $archive_path" >&2
  fi
}

cleanup() {
  local status=$?
  trap - EXIT
  set +e
  if [[ $status -ne 0 ]]; then
    collect_failure_artifacts "$status"
  fi

  if [[ "$maestro_ran" == "true" ]]; then
    wake_unlock_device >/dev/null 2>&1 || true
    resume_debug_automation_state >/dev/null 2>&1 || true
    wake_unlock_device >/dev/null 2>&1 || true
    run_maestro_flow "$lab_root/maestro/disconnect-vpn.yaml" >/dev/null 2>&1 || true
    sleep 2
    assert_vpn_service_stopped >/dev/null 2>&1 || true
  fi

  if [[ "$lab_started" == "true" && "$keep_lab" != "true" ]]; then
    "$script_dir/stop-lab.sh" >/dev/null 2>&1 || true
  fi

  if [[ "$device_state_captured" == "true" ]]; then
    if ! python3 "$script_dir/android-device-session.py" restore \
      --serial "$android_serial" \
      --state-dir "$device_state_dir"; then
      echo "Failed to restore the pre-test Android device state." >&2
      status=1
    fi
  fi
  rm -rf "$device_state_root"

  exit "$status"
}
trap cleanup EXIT

python3 "$script_dir/android-device-session.py" capture \
  --serial "$android_serial" \
  --state-dir "$device_state_dir" \
  --package com.poyka.ripdpi \
  --package com.poyka.ripdpi.test
device_state_captured=true

if [[ "$skip_start" != "true" ]]; then
  "$script_dir/restart-lab.sh" --profile "$profile"
  lab_started=true
fi

if [[ "$skip_install" != "true" ]]; then
  "$script_dir/adb-install-debug.sh"
fi

if [[ "$skip_maestro" != "true" ]]; then
  if resolve_maestro; then
    grant_runtime_permissions
    wake_unlock_device
    seed_debug_automation_state
    wake_unlock_device
    maestro_ran=true
    run_maestro_flow "$lab_root/maestro/connect-vpn.yaml"
  else
    echo "maestro is not available; cannot drive the VPN connect flow." >&2
    echo "Install Maestro, set MAESTRO_BIN, add it to PATH, or rerun with --skip-maestro only after manually connecting VPN mode." >&2
    exit 2
  fi
fi

"$script_dir/adb-run-probe.sh" \
  --profile "$profile" \
  --mode vpn \
  --require-vpn-active true \
  --require-proxy-ready false \
  --timeout-ms "$timeout_ms" \
  --out-dir "$out_dir"

if [[ "$maestro_ran" == "true" ]]; then
  wake_unlock_device
  resume_debug_automation_state
  wake_unlock_device
  run_maestro_flow "$lab_root/maestro/disconnect-vpn.yaml"
  sleep 2
  assert_vpn_service_stopped
  maestro_ran=false
fi

echo "VPN E2E smoke passed. Artifacts: $out_dir"
