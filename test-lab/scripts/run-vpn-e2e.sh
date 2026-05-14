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

if [[ -z "$out_dir" ]]; then
  out_dir="$lab_root/artifacts/vpn-e2e-$(date +%Y%m%d-%H%M%S)"
fi
mkdir -p "$out_dir"

maestro_ran=false
lab_started=false
failure=false
archive_path=""

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
  if [[ $status -ne 0 ]]; then
    failure=true
    collect_failure_artifacts "$status"
  fi

  if [[ "$maestro_ran" == "true" ]]; then
    maestro test "$lab_root/maestro/disconnect-vpn.yaml" >/dev/null 2>&1 || true
  fi

  if [[ "$lab_started" == "true" && "$keep_lab" != "true" ]]; then
    "$script_dir/stop-lab.sh" >/dev/null 2>&1 || true
  fi

  if [[ "$failure" == "true" ]]; then
    exit "$status"
  fi
}
trap cleanup EXIT

if [[ "$skip_start" != "true" ]]; then
  "$script_dir/restart-lab.sh" --profile "$profile"
  lab_started=true
fi

if [[ "$skip_install" != "true" ]]; then
  "$script_dir/adb-install-debug.sh"
fi

if [[ "$skip_maestro" != "true" ]]; then
  if command -v maestro >/dev/null 2>&1; then
    seed_debug_automation_state
    maestro_ran=true
    maestro test "$lab_root/maestro/connect-vpn.yaml"
  else
    echo "maestro is not available; cannot drive the VPN connect flow." >&2
    echo "Install Maestro or rerun with --skip-maestro only after manually connecting VPN mode." >&2
    exit 2
  fi
fi

"$script_dir/adb-run-probe.sh" \
  --profile "$profile" \
  --mode vpn \
  --timeout-ms "$timeout_ms" \
  --out-dir "$out_dir"

echo "VPN E2E smoke passed. Artifacts: $out_dir"
