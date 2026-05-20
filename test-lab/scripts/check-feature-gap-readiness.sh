#!/usr/bin/env bash
set -euo pipefail

lab_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
artifact_path="$lab_root/artifacts/feature-gap-readiness.json"
adb_bin="${ADB:-adb}"
relay_matrix_config="${RIPDPI_RELAY_MATRIX_CONFIG:-}"
local_workflow_evidence="${RIPDPI_LOCAL_WORKFLOW_EVIDENCE:-}"
remote_compare_ref="${RIPDPI_REMOTE_COMPARE_REF:-origin/main}"
ignore_dirty_worktree="${RIPDPI_IGNORE_DIRTY_WORKTREE_FOR_READINESS:-false}"
netem_container_name="${RIPDPI_NETEM_CONTAINER:-ripdpi-linux-netem}"

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
selected_android_serial=""
selected_android_kind=""
device_selection_error=""

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

select_android_device() {
  if ! command -v "$adb_bin" >/dev/null 2>&1; then
    device_selection_error="adb is not available on PATH."
    return 1
  fi

  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    selected_android_serial="$ANDROID_SERIAL"
    if [[ "$selected_android_serial" == emulator-* ]]; then
      selected_android_kind="emulator"
    else
      selected_android_kind="physical"
    fi
    return 0
  fi

  local ready_serials=()
  local physical_serials=()
  local serial
  while read -r serial; do
    [[ -n "$serial" ]] || continue
    ready_serials+=("$serial")
    if [[ "$serial" != emulator-* ]]; then
      physical_serials+=("$serial")
    fi
  done < <("$adb_bin" devices 2>/dev/null | awk 'NR > 1 && $2 == "device" { print $1 }')

  if [[ "${#physical_serials[@]}" -eq 1 ]]; then
    selected_android_serial="${physical_serials[0]}"
    selected_android_kind="physical"
    return 0
  fi
  if [[ "${#physical_serials[@]}" -gt 1 ]]; then
    device_selection_error="Multiple physical adb devices are attached; set ANDROID_SERIAL for the release-readiness target."
    return 1
  fi
  if [[ "${#ready_serials[@]}" -eq 1 ]]; then
    selected_android_serial="${ready_serials[0]}"
    selected_android_kind="emulator"
    return 0
  fi
  if [[ "${#ready_serials[@]}" -gt 1 ]]; then
    device_selection_error="Multiple adb targets are attached and no physical target could be selected; set ANDROID_SERIAL."
    return 1
  fi

  device_selection_error="No attached adb device is ready."
  return 1
}

adb_cmd() {
  if [[ -n "$selected_android_serial" ]]; then
    "$adb_bin" -s "$selected_android_serial" "$@"
  else
    "$adb_bin" "$@"
  fi
}

adb_state() {
  if [[ -z "$selected_android_serial" ]]; then
    return 1
  fi
  adb_cmd get-state 2>/dev/null
}

adb_shell() {
  adb_cmd shell "$@" 2>/dev/null | tr -d '\r'
}

netem_container_running() {
  [[ -n "$netem_container_name" ]] || return 1
  command -v docker >/dev/null 2>&1 || return 1
  [[ "$(docker inspect -f '{{.State.Running}}' "$netem_container_name" 2>/dev/null || true)" == "true" ]]
}

netem_container_has_tc() {
  docker exec "$netem_container_name" sh -c \
    'command -v tc >/dev/null 2>&1 && tc qdisc show dev "${NETEM_DEV:-eth0}" >/dev/null 2>&1' \
    >/dev/null 2>&1
}

select_android_device || true
device_state="$(adb_state || true)"
if [[ "$device_state" == "device" ]]; then
  model="$(adb_shell getprop ro.product.model || true)"
  api_level="$(adb_shell getprop ro.build.version.sdk || true)"
  release="$(adb_shell getprop ro.build.version.release_or_codename || true)"
  add_check "android_device" "ready" "true" "Connected ${selected_android_kind:-adb} device: ${model:-unknown}, Android ${release:-unknown} / API ${api_level:-unknown}."
else
  add_check "android_device" "blocked" "true" "${device_selection_error:-No attached adb device is ready.}"
fi

if [[ "$device_state" == "device" && "$selected_android_kind" != "emulator" ]]; then
  root_probe="$(adb_shell su 0 id || true)"
  if [[ "$root_probe" == *"uid=0"* ]]; then
    add_check "rooted_physical_device" "ready" "true" "Root shell is available via su 0 id: $root_probe."
  else
    add_check "rooted_physical_device" "blocked" "true" "Attached device did not provide root via su 0 id."
  fi
elif [[ "$device_state" == "device" ]]; then
  add_check "rooted_physical_device" "blocked" "true" "Selected adb target is an emulator; rooted physical evidence requires a physical device."
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

if [[ "$device_state" == "device" && "$selected_android_kind" != "emulator" ]]; then
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
    telephony_registry="$(adb_shell dumpsys telephony.registry || true)"
    if [[ "$telephony_registry" == *"mDataRegState=0(IN_SERVICE)"* ]]; then
      cellular_service_state="in_service"
    elif [[ "$telephony_registry" == *"mDataRegState="* ]]; then
      cellular_service_state="out_of_service"
    else
      cellular_service_state="unknown"
    fi
    if [[ "$cellular_service_state" == "out_of_service" ]]; then
      add_check "physical_network_handover" "blocked" "true" "Wi-Fi and cellular transports are visible, but cellular data service is out of service; use a physical device/SIM with in-service cellular data before the handover run."
    elif [[ "$cellular_service_state" == "unknown" ]]; then
      add_check "physical_network_handover" "manual" "true" "Wi-Fi and cellular transports are both visible, but cellular service state could not be confirmed; a human or external harness still must perform the handover run."
    else
      add_check "physical_network_handover" "manual" "true" "Wi-Fi and in-service cellular transports are both visible; a human or external harness still must perform the handover run."
    fi
  else
    add_check "physical_network_handover" "blocked" "true" "Need both Wi-Fi and cellular transports visible; wifi=$wifi_state cellular=$cellular_state."
  fi
elif [[ "$device_state" == "device" ]]; then
  add_check "physical_network_handover" "blocked" "true" "Selected adb target is an emulator; physical network matrix evidence requires a physical device."
else
  add_check "physical_network_handover" "blocked" "true" "No adb device is ready for network matrix checks."
fi

if netem_container_running; then
  if netem_container_has_tc; then
    add_check "routed_netem_vm" "manual" "true" "Docker netem container '$netem_container_name' is running and has tc; confirm Android/device traffic is routed through this container or a Linux router namespace before running packet-loss scenarios."
  else
    add_check "routed_netem_vm" "blocked" "true" "Docker netem container '$netem_container_name' is running, but tc is unavailable or cannot inspect NETEM_DEV=${NETEM_DEV:-eth0}."
  fi
elif [[ "$(uname -s)" == "Linux" ]] && command -v tc >/dev/null 2>&1; then
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

repo_root="$lab_root/.."
if [[ "$ignore_dirty_worktree" == "true" ]]; then
  dirty_worktree=""
else
  dirty_worktree="$(git -C "$repo_root" status --porcelain --untracked-files=all 2>/dev/null || true)"
fi
ahead_count="$(git -C "$repo_root" rev-list --count "$remote_compare_ref..HEAD" 2>/dev/null || printf 'unknown')"
if [[ -n "$dirty_worktree" ]]; then
  dirty_count="$(printf '%s\n' "$dirty_worktree" | sed '/^$/d' | wc -l | tr -d ' ')"
  add_check "remote_workflow_confirmation" "blocked" "true" "Local working tree has $dirty_count uncommitted change(s); commit or discard them, then confirm fresh workflows before sign-off."
elif [[ -n "$local_workflow_evidence" ]]; then
  head_sha="$(git -C "$repo_root" rev-parse HEAD 2>/dev/null || true)"
  if [[ -z "$head_sha" ]]; then
    add_check "remote_workflow_confirmation" "blocked" "true" "Could not resolve the current git commit for local workflow evidence validation."
  elif local_workflow_output="$("$lab_root/scripts/check-local-workflow-evidence.sh" --evidence "$local_workflow_evidence" --head "$head_sha" 2>&1)"; then
    add_check "remote_workflow_confirmation" "ready" "true" "Local workflow evidence accepted for the current commit. $local_workflow_output"
  else
    add_check "remote_workflow_confirmation" "blocked" "true" "Local workflow evidence is invalid at $local_workflow_evidence: $local_workflow_output"
  fi
elif [[ "$ahead_count" == "0" ]]; then
  add_check "remote_workflow_confirmation" "manual" "true" "Local branch is not ahead of $remote_compare_ref; inspect GitHub workflow status for the pushed or merged commit."
elif [[ "$ahead_count" == "unknown" ]]; then
  add_check "remote_workflow_confirmation" "blocked" "true" "Could not compare the local branch to $remote_compare_ref; fetch the remote ref or run from a git worktree before sign-off."
else
  add_check "remote_workflow_confirmation" "blocked" "true" "Local branch is ahead of $remote_compare_ref; publish a review branch, complete pull request checks/reviews, merge to main, then confirm fresh workflows before sign-off."
fi

artifact_dir="$(dirname "$artifact_path")"
mkdir -p "$artifact_dir"
artifact_tmp="$(mktemp "$artifact_dir/.feature-gap-readiness.XXXXXX")"
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
} > "$artifact_tmp"

mv -f "$artifact_tmp" "$artifact_path"
cat "$artifact_path"
