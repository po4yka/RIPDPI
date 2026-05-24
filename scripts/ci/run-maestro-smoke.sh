#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FLOW_DIR="$ROOT_DIR/maestro"
LAB_FLOW_DIR="$ROOT_DIR/test-lab/maestro"

resolve_maestro() {
  if [[ -n "${MAESTRO_BIN:-}" ]]; then
    if [[ -x "$MAESTRO_BIN" ]]; then
      printf '%s\n' "$MAESTRO_BIN"
      return 0
    fi
    echo "MAESTRO_BIN is set but is not executable: $MAESTRO_BIN" >&2
    return 1
  fi

  if command -v maestro >/dev/null 2>&1; then
    command -v maestro
    return 0
  fi

  local default_bin="$HOME/.maestro/bin/maestro"
  if [[ -x "$default_bin" ]]; then
    printf '%s\n' "$default_bin"
    return 0
  fi

  return 1
}

if ! MAESTRO_BIN_RESOLVED="$(resolve_maestro)"; then
  echo "maestro CLI is required. Add it to PATH, set MAESTRO_BIN, or install it at ~/.maestro/bin/maestro." >&2
  exit 1
fi

adb wait-for-device

run_flow() {
  "$MAESTRO_BIN_RESOLVED" test "$1"
}

launch_automation() {
  local route="$1"
  local data_preset="${2:-settings_ready}"
  local permission_preset="${3:-granted}"
  local service_preset="${4:-idle}"
  local start_configured_mode="${5:-false}"

  adb shell am start -W \
    -a com.poyka.ripdpi.automation.LAUNCH \
    -n com.poyka.ripdpi/.activities.MainActivity \
    --ez com.poyka.ripdpi.automation.ENABLED true \
    --ez com.poyka.ripdpi.automation.RESET_STATE true \
    --ez com.poyka.ripdpi.automation.DISABLE_MOTION true \
    --ez com.poyka.ripdpi.extra.START_CONFIGURED_MODE "$start_configured_mode" \
    --es com.poyka.ripdpi.automation.START_ROUTE "$route" \
    --es com.poyka.ripdpi.automation.PERMISSION_PRESET "$permission_preset" \
    --es com.poyka.ripdpi.automation.SERVICE_PRESET "$service_preset" \
    --es com.poyka.ripdpi.automation.DATA_PRESET "$data_preset" >/dev/null
}

run_flow "$FLOW_DIR/01-cold-launch-home.yaml"
run_flow "$FLOW_DIR/02-settings-navigation.yaml"
run_flow "$FLOW_DIR/03-advanced-settings-edit-save.yaml"
run_flow "$FLOW_DIR/04-start-stop-configured-mode.yaml"
run_flow "$FLOW_DIR/05-top-tab-navigation.yaml"
run_flow "$FLOW_DIR/06-config-feature-coverage.yaml"
run_flow "$FLOW_DIR/07-dns-settings-coverage.yaml"
run_flow "$FLOW_DIR/08-settings-secondary-screens.yaml"
run_flow "$FLOW_DIR/09-advanced-control-coverage.yaml"
run_flow "$FLOW_DIR/10-diagnostics-ui-coverage.yaml"

if [[ "${RUN_MAESTRO_ROUTE_FLOWS:-1}" == "1" ]]; then
  launch_automation "history" "diagnostics_demo"
  run_flow "$FLOW_DIR/route/01-history-and-logs-coverage.yaml"

  launch_automation "logs" "diagnostics_demo"
  run_flow "$FLOW_DIR/route/02-logs-coverage.yaml"

  launch_automation "home" "settings_ready" "vpn_missing"
  run_flow "$FLOW_DIR/route/03-permission-banner-coverage.yaml"

  launch_automation "home" "settings_ready" "granted" "connected_proxy"
  run_flow "$FLOW_DIR/route/04-connected-service-state-coverage.yaml"

  launch_automation "home" "settings_ready" "granted" "connected_vpn"
  run_flow "$FLOW_DIR/route/04-connected-service-state-coverage.yaml"

  launch_automation "diagnostics" "diagnostics_report_demo"
  run_flow "$FLOW_DIR/route/05-diagnostics-report-coverage.yaml"
fi

if [[ "${RUN_MAESTRO_COMPLEX_FLOWS:-0}" == "1" ]]; then
  launch_automation "onboarding" "clean_home" "granted" "idle"
  run_flow "$FLOW_DIR/complex/01-onboarding-to-first-connect.yaml"

  launch_automation "config" "settings_ready" "granted" "idle"
  run_flow "$FLOW_DIR/complex/02-config-preset-editor-connect.yaml"

  launch_automation "home" "settings_ready" "granted" "idle"
  run_flow "$FLOW_DIR/complex/03-dns-round-trip.yaml"

  launch_automation "diagnostics" "diagnostics_report_demo" "granted" "connected_vpn"
  run_flow "$FLOW_DIR/complex/04-diagnostics-report-drilldown.yaml"

  launch_automation "home" "clean_home" "vpn_missing" "idle"
  run_flow "$FLOW_DIR/complex/05-permission-repair-journey.yaml"

  launch_automation "advanced_settings" "settings_ready" "granted" "idle"
  run_flow "$FLOW_DIR/complex/06-activation-window-edit.yaml"

  launch_automation "logs" "diagnostics_demo" "granted" "idle"
  run_flow "$FLOW_DIR/complex/07-logs-history-filter-journey.yaml"

  launch_automation "biometric_prompt" "biometric_locked_with_pin" "granted" "idle"
  run_flow "$FLOW_DIR/complex/08-biometric-locked-fallback.yaml"

  launch_automation "home" "clean_home" "granted" "idle"
  run_flow "$FLOW_DIR/complex/09-background-guidance-banners.yaml"
fi

if [[ "${RUN_MAESTRO_LAB_FLOWS:-0}" == "1" ]]; then
  run_flow "$LAB_FLOW_DIR/connect-proxy.yaml"
  run_flow "$LAB_FLOW_DIR/disconnect-proxy.yaml"
  run_flow "$LAB_FLOW_DIR/connect-vpn.yaml"
  run_flow "$LAB_FLOW_DIR/disconnect-vpn.yaml"
  run_flow "$LAB_FLOW_DIR/reconnect.yaml"
  run_flow "$LAB_FLOW_DIR/run-diagnostics.yaml"
  run_flow "$LAB_FLOW_DIR/settings-lab-profile.yaml"
  run_flow "$LAB_FLOW_DIR/complex-proxy-vpn-reconnect.yaml"
fi
