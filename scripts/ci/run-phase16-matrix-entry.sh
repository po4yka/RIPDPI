#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
entry_id="${PHASE16_ENTRY_ID:?PHASE16_ENTRY_ID is required}"
execution_kind="${PHASE16_EXECUTION_KIND:?PHASE16_EXECUTION_KIND is required}"
artifact_root="${RIPDPI_PHASE16_ARTIFACT_DIR:-$repo_root/build/phase16-matrix/$entry_id}"
transport="${PHASE16_TRANSPORT:-unknown}"
ip_family="${PHASE16_IP_FAMILY:-unknown}"
rooted="${PHASE16_ROOTED:-unknown}"
mode="${PHASE16_MODE:-unknown}"
scenario_filter="${PHASE16_SCENARIO_FILTER:-}"
capture_mode="${PHASE16_CAPTURE_MODE:-auto}"
prepare_hook="${RIPDPI_PHASE16_PREPARE_HOOK:-}"
summary_script="$repo_root/scripts/ci/phase16_pcap_summary.py"
run_manifest="$artifact_root/phase16-run.json"
pcap_summary="$artifact_root/phase16-pcap-summary.json"
start_epoch="$(date +%s)"
status="success"
failure_message=""

write_manifest() {
  local finish_epoch
  finish_epoch="$(date +%s)"
  python3 - "$run_manifest" <<'PY'
import json
import os
import sys

path = sys.argv[1]
payload = {
    "entryId": os.environ["PHASE16_ENTRY_ID"],
    "executionKind": os.environ["PHASE16_EXECUTION_KIND"],
    "transport": os.environ.get("PHASE16_TRANSPORT", "unknown"),
    "ipFamily": os.environ.get("PHASE16_IP_FAMILY", "unknown"),
    "rooted": os.environ.get("PHASE16_ROOTED", "unknown"),
    "mode": os.environ.get("PHASE16_MODE", "unknown"),
    "scenarioFilter": os.environ.get("PHASE16_SCENARIO_FILTER", ""),
    "captureMode": os.environ.get("PHASE16_CAPTURE_MODE", "auto"),
    "status": os.environ["PHASE16_RUN_STATUS"],
    "failureMessage": os.environ.get("PHASE16_FAILURE_MESSAGE", ""),
    "artifactRoot": os.environ["RIPDPI_PHASE16_ARTIFACT_DIR"],
    "startedAtEpoch": int(os.environ["PHASE16_STARTED_AT_EPOCH"]),
    "finishedAtEpoch": int(os.environ["PHASE16_FINISHED_AT_EPOCH"]),
}
with open(path, "w", encoding="utf-8") as handle:
    json.dump(payload, handle, indent=2, sort_keys=True)
    handle.write("\n")
PY
}

on_exit() {
  local exit_code=$?
  mkdir -p "$artifact_root"
  if [[ "$exit_code" -ne 0 ]]; then
    status="failure"
  fi
  export PHASE16_RUN_STATUS="$status"
  export PHASE16_FAILURE_MESSAGE="$failure_message"
  export PHASE16_STARTED_AT_EPOCH="$start_epoch"
  export PHASE16_FINISHED_AT_EPOCH="$(date +%s)"
  export RIPDPI_PHASE16_ARTIFACT_DIR="$artifact_root"
  write_manifest || true
  if [[ -d "$artifact_root" ]]; then
    python3 "$summary_script" --artifact-root "$artifact_root" --output "$pcap_summary" >/dev/null 2>&1 || true
  fi
  exit "$exit_code"
}
trap on_exit EXIT

mkdir -p "$artifact_root"
export RIPDPI_PHASE16_ARTIFACT_DIR="$artifact_root"

if [[ -n "$prepare_hook" ]]; then
  if [[ ! -x "$prepare_hook" ]]; then
    failure_message="prepare hook is not executable: $prepare_hook"
    echo "$failure_message" >&2
    exit 1
  fi
  "$prepare_hook" "$entry_id" "$transport" "$ip_family" "$rooted" "$mode" "$artifact_root"
fi

case "$execution_kind" in
  android_packet_smoke)
    export RIPDPI_PACKET_SMOKE_ARTIFACT_DIR="$artifact_root"
    export RIPDPI_PACKET_SMOKE_SCENARIO_FILTER="$scenario_filter"
    export RIPDPI_PACKET_SMOKE_CAPTURE_MODE="$capture_mode"
    bash "$repo_root/scripts/ci/run-android-packet-smoke.sh"
    ;;
  host_cli_packet_smoke)
    export RIPDPI_PACKET_SMOKE_ARTIFACT_DIR="$artifact_root"
    export RIPDPI_PACKET_SMOKE_SCENARIO_FILTER="$scenario_filter"
    export RIPDPI_PACKET_SMOKE_CAPTURE_MODE="$capture_mode"
    export RIPDPI_RUN_PACKET_SMOKE=1
    bash "$repo_root/scripts/ci/run-cli-packet-smoke.sh"
    ;;
  *)
    failure_message="unsupported execution kind: $execution_kind"
    echo "$failure_message" >&2
    exit 1
    ;;
esac
