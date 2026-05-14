#!/usr/bin/env bash
set -euo pipefail

lab_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
artifact_path="$lab_root/artifacts/feature-gap-readiness.json"
adb_bin="${ADB:-adb}"
relay_matrix_config="${RIPDPI_RELAY_MATRIX_CONFIG:-}"

usage() {
  cat <<USAGE
Usage: $0 [--output PATH]

Checks whether the current host and attached Android device can execute the
manual/environment rows that remain open in docs/feature-test-checklist.md.
The script is read-only: it does not enable TalkBack, change network state,
toggle VPN, or start services.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --output)
      artifact_path="${2:?missing --output value}"
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

declare -a check_names=()
declare -a check_statuses=()
declare -a check_required=()
declare -a check_messages=()

json_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\n'/\\n}"
  value="${value//$'\r'/\\r}"
  value="${value//$'\t'/\\t}"
  printf '%s' "$value"
}

add_check() {
  check_names+=("$1")
  check_statuses+=("$2")
  check_required+=("$3")
  check_messages+=("$4")
}

adb_state() {
  if ! command -v "$adb_bin" >/dev/null 2>&1; then
    return 1
  fi
  "$adb_bin" get-state 2>/dev/null
}

adb_shell() {
  "$adb_bin" shell "$@" 2>/dev/null | tr -d '\r'
}

device_state="$(adb_state || true)"
if [[ "$device_state" == "device" ]]; then
  model="$(adb_shell getprop ro.product.model || true)"
  api_level="$(adb_shell getprop ro.build.version.sdk || true)"
  release="$(adb_shell getprop ro.build.version.release_or_codename || true)"
  add_check "android_device" "ready" "true" "Connected device: ${model:-unknown}, Android ${release:-unknown} / API ${api_level:-unknown}."
else
  add_check "android_device" "blocked" "true" "No attached adb device is ready."
fi

if [[ "$device_state" == "device" ]]; then
  root_probe="$(adb_shell su 0 id || true)"
  if [[ "$root_probe" == *"uid=0"* ]]; then
    add_check "rooted_physical_device" "ready" "true" "Root shell is available via su 0 id: $root_probe."
  else
    add_check "rooted_physical_device" "blocked" "true" "Attached device did not provide root via su 0 id."
  fi
else
  add_check "rooted_physical_device" "blocked" "true" "No adb device is ready for the root-helper pass."
fi

if [[ "$device_state" == "device" ]]; then
  enabled_services="$(adb_shell settings get secure enabled_accessibility_services || true)"
  accessibility_enabled="$(adb_shell settings get secure accessibility_enabled || true)"
  talkback_packages="$(adb_shell pm list packages | grep -Ei 'talkback|marvin' || true)"
  if [[ "$enabled_services" == *"com.google.android.marvin.talkback"* ]]; then
    add_check "manual_talkback" "ready" "true" "TalkBack is the active accessibility service."
  elif [[ -n "$talkback_packages" ]]; then
    add_check "manual_talkback" "blocked" "true" "TalkBack is installed but not active; accessibility_enabled=$accessibility_enabled active_services=${enabled_services:-none}."
  else
    add_check "manual_talkback" "blocked" "true" "TalkBack package is not installed on the attached device."
  fi
else
  add_check "manual_talkback" "blocked" "true" "No adb device is ready for TalkBack verification."
fi

if [[ "$device_state" == "device" ]]; then
  connectivity="$(adb_shell dumpsys connectivity || true)"
  if [[ "$connectivity" == *"Transports: WIFI"* ]]; then
    wifi_state="present"
  else
    wifi_state="absent"
  fi
  if [[ "$connectivity" == *"Transports: CELLULAR"* ]]; then
    cellular_state="present"
  else
    cellular_state="absent"
  fi
  if [[ "$wifi_state" == "present" && "$cellular_state" == "present" ]]; then
    add_check "physical_network_handover" "manual" "true" "Wi-Fi and cellular transports are both visible; a human or external harness still must perform the handover run."
  else
    add_check "physical_network_handover" "blocked" "true" "Need both Wi-Fi and cellular transports visible; wifi=$wifi_state cellular=$cellular_state."
  fi
else
  add_check "physical_network_handover" "blocked" "true" "No adb device is ready for network matrix checks."
fi

if [[ "$(uname -s)" == "Linux" ]] && command -v tc >/dev/null 2>&1; then
  if [[ -c /dev/net/tun || -e /proc/sys/net/ipv4/ip_forward ]]; then
    add_check "routed_netem_vm" "manual" "true" "Linux netem tools are present; confirm this host is routing Android/device traffic before running packet-loss scenarios."
  else
    add_check "routed_netem_vm" "blocked" "true" "Linux netem tools are present, but no routing/TUN evidence was found."
  fi
else
  add_check "routed_netem_vm" "blocked" "true" "This host is $(uname -s); routed netem scenarios require a Linux VM or router namespace with tc."
fi

if [[ -n "$relay_matrix_config" && -f "$relay_matrix_config" ]]; then
  if relay_matrix_output="$("$lab_root/scripts/check-relay-matrix-config.sh" --config "$relay_matrix_config" 2>&1)"; then
    add_check "production_relay_matrix" "manual" "true" "Relay matrix config is valid at $relay_matrix_config; provider-backed runs still need execution. $relay_matrix_output"
  else
    add_check "production_relay_matrix" "blocked" "true" "Relay matrix config exists but failed validation at $relay_matrix_config: $relay_matrix_output"
  fi
else
  add_check "production_relay_matrix" "blocked" "true" "Set RIPDPI_RELAY_MATRIX_CONFIG to an operator-provided provider matrix before running production relay checks. Template: test-lab/relay/provider-matrix.example.json."
fi

ahead_count="$(git -C "$lab_root/.." rev-list --count origin/main..HEAD 2>/dev/null || printf 'unknown')"
if [[ "$ahead_count" == "0" ]]; then
  add_check "remote_workflow_confirmation" "manual" "true" "Local branch is not ahead of origin/main; inspect GitHub workflow status for the pushed commit."
else
  add_check "remote_workflow_confirmation" "blocked" "true" "Local branch is ahead of origin/main; push or dispatch fresh workflows before sign-off."
fi

mkdir -p "$(dirname "$artifact_path")"
{
  printf '{\n'
  printf '  "generatedAtEpoch": %s,\n' "$(date +%s)"
  printf '  "host": "%s",\n' "$(json_escape "$(hostname 2>/dev/null || true)")"
  printf '  "checks": [\n'
  for index in "${!check_names[@]}"; do
    if [[ "$index" -gt 0 ]]; then
      printf ',\n'
    fi
    printf '    {"name": "%s", "status": "%s", "required": %s, "message": "%s"}' \
      "$(json_escape "${check_names[$index]}")" \
      "$(json_escape "${check_statuses[$index]}")" \
      "${check_required[$index]}" \
      "$(json_escape "${check_messages[$index]}")"
  done
  printf '\n  ]\n'
  printf '}\n'
} > "$artifact_path"

cat "$artifact_path"
