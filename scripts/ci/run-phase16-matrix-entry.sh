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
network_condition="${PHASE16_NETWORK_CONDITION:-baseline}"
scenario_filter="${PHASE16_SCENARIO_FILTER:-}"
capture_mode="${PHASE16_CAPTURE_MODE:-auto}"
runner_required="${PHASE16_RUNNER_REQUIRED:-lab}"
evidence_tier="${PHASE16_EVIDENCE_TIER:-synthetic-lab}"
carrier_namespace="${PHASE16_CARRIER_NAMESPACE:-}"
prepare_hook="${RIPDPI_PHASE16_PREPARE_HOOK:-}"
cleanup_hook="${RIPDPI_PHASE16_CLEANUP_HOOK:-}"
real_provider_config="${RIPDPI_PHASE16_REAL_PROVIDER_CONFIG:-}"
l7_dryrun_script="${RIPDPI_PHASE16_L7_ADVERSARIAL_DRYRUN_SCRIPT:-${RIPDPI_PHASE16_TSPU_DRYRUN_SCRIPT:-$repo_root/scripts/ci/run-l7-adversarial-dryrun.sh}}"
l7_artifact_dir="$artifact_root/l7-adversarial"
l7_verdict_report="$l7_artifact_dir/verdict-report.json"
summary_script="$repo_root/scripts/ci/phase16_pcap_summary.py"
run_manifest="$artifact_root/phase16-run.json"
pcap_summary="$artifact_root/phase16-pcap-summary.json"
start_epoch="$(date +%s)"
status="success"
failure_message=""
real_provider_config_required="false"
real_provider_config_present="false"
real_provider_namespace_configured="false"
real_provider_pcap_scrub_required="false"
prepare_hook_configured="false"
prepare_hook_executed="false"
prepare_hook_status_path="$artifact_root/phase16-prepare-hook.json"
cleanup_hook_configured="false"
cleanup_hook_executed="false"
cleanup_hook_status="not-run"
cleanup_hook_exit_code=0
cleanup_hook_status_path="$artifact_root/phase16-cleanup-hook.json"

runner_unavailable() {
  status="runner_unavailable"
  failure_message="$1"
  echo "$failure_message" >&2
  exit 1
}

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
    "networkCondition": os.environ.get("PHASE16_NETWORK_CONDITION", "baseline"),
    "scenarioFilter": os.environ.get("PHASE16_SCENARIO_FILTER", ""),
    "captureMode": os.environ.get("PHASE16_CAPTURE_MODE", "auto"),
    "runnerRequired": os.environ.get("PHASE16_RUNNER_REQUIRED", "lab"),
    "evidenceTier": os.environ.get("PHASE16_EVIDENCE_TIER", "synthetic-lab"),
    "carrierNamespace": os.environ.get("PHASE16_CARRIER_NAMESPACE", ""),
    "l7VerdictReport": os.environ.get("PHASE16_L7_VERDICT_REPORT", ""),
    "realProvider": {
        "configRequired": os.environ.get("PHASE16_REAL_PROVIDER_CONFIG_REQUIRED") == "true",
        "configPresent": os.environ.get("PHASE16_REAL_PROVIDER_CONFIG_PRESENT") == "true",
        "namespaceConfigured": os.environ.get("PHASE16_REAL_PROVIDER_NAMESPACE_CONFIGURED") == "true",
        "pcapScrubRequired": os.environ.get("PHASE16_REAL_PROVIDER_PCAP_SCRUB_REQUIRED") == "true",
    },
    "prepareHook": {
        "configured": os.environ.get("PHASE16_PREPARE_HOOK_CONFIGURED") == "true",
        "executed": os.environ.get("PHASE16_PREPARE_HOOK_EXECUTED") == "true",
    },
    "cleanupHook": {
        "configured": os.environ.get("PHASE16_CLEANUP_HOOK_CONFIGURED") == "true",
        "executed": os.environ.get("PHASE16_CLEANUP_HOOK_EXECUTED") == "true",
        "status": os.environ.get("PHASE16_CLEANUP_HOOK_STATUS", "not-run"),
        "exitCode": int(os.environ.get("PHASE16_CLEANUP_HOOK_EXIT_CODE", "0")),
    },
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
  trap - EXIT
  set +e
  mkdir -p "$artifact_root"
  if [[ "$exit_code" -ne 0 && "$status" == "success" ]]; then
    status="failure"
  fi
  if [[ "$prepare_hook_executed" == "true" ]]; then
    cleanup_hook_executed="true"
    export RIPDPI_PHASE16_RUN_EXIT_CODE="$exit_code"
    "$cleanup_hook" \
      "$entry_id" "$transport" "$ip_family" "$rooted" "$mode" \
      "$artifact_root" "$network_condition" "$runner_required" \
      "$evidence_tier" "$carrier_namespace" "$exit_code" >/dev/null 2>&1
    cleanup_hook_exit_code=$?
    if [[ "$cleanup_hook_exit_code" -eq 0 ]]; then
      cleanup_hook_status="success"
    else
      cleanup_hook_status="failed"
      status="failure"
      failure_message="${failure_message:+$failure_message; }Phase 16 cleanup hook failed with exit code $cleanup_hook_exit_code"
      if [[ "$exit_code" -eq 0 ]]; then
        exit_code="$cleanup_hook_exit_code"
      fi
    fi
    python3 - "$cleanup_hook_status_path" "$cleanup_hook_status" "$cleanup_hook_exit_code" <<'PY' || true
import json
import os
import sys

path, hook_status, hook_exit_code = sys.argv[1:4]
with open(path, "w", encoding="utf-8") as handle:
    json.dump(
        {
            "entryId": os.environ["PHASE16_ENTRY_ID"],
            "status": hook_status,
            "exitCode": int(hook_exit_code),
        },
        handle,
        indent=2,
        sort_keys=True,
    )
    handle.write("\n")
PY
  fi
  export PHASE16_RUN_STATUS="$status"
  export PHASE16_FAILURE_MESSAGE="$failure_message"
  export PHASE16_STARTED_AT_EPOCH="$start_epoch"
  export PHASE16_FINISHED_AT_EPOCH="$(date +%s)"
  export PHASE16_REAL_PROVIDER_CONFIG_REQUIRED="$real_provider_config_required"
  export PHASE16_REAL_PROVIDER_CONFIG_PRESENT="$real_provider_config_present"
  export PHASE16_REAL_PROVIDER_NAMESPACE_CONFIGURED="$real_provider_namespace_configured"
  export PHASE16_REAL_PROVIDER_PCAP_SCRUB_REQUIRED="$real_provider_pcap_scrub_required"
  export PHASE16_PREPARE_HOOK_CONFIGURED="$prepare_hook_configured"
  export PHASE16_PREPARE_HOOK_EXECUTED="$prepare_hook_executed"
  export PHASE16_CLEANUP_HOOK_CONFIGURED="$cleanup_hook_configured"
  export PHASE16_CLEANUP_HOOK_EXECUTED="$cleanup_hook_executed"
  export PHASE16_CLEANUP_HOOK_STATUS="$cleanup_hook_status"
  export PHASE16_CLEANUP_HOOK_EXIT_CODE="$cleanup_hook_exit_code"
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

write_prepare_hook_status() {
  local hook_status="$1"
  local hook_exit_code="$2"
  python3 - "$prepare_hook_status_path" "$hook_status" "$hook_exit_code" <<'PY'
import json
import os
import sys

path, hook_status, hook_exit_code = sys.argv[1:4]
payload = {
    "entryId": os.environ["PHASE16_ENTRY_ID"],
    "carrierNamespace": os.environ.get("PHASE16_CARRIER_NAMESPACE", ""),
    "runnerRequired": os.environ.get("PHASE16_RUNNER_REQUIRED", "lab"),
    "status": hook_status,
    "exitCode": int(hook_exit_code),
}
with open(path, "w", encoding="utf-8") as handle:
    json.dump(payload, handle, indent=2, sort_keys=True)
    handle.write("\n")
PY
}

run_real_provider_prepare_hook() {
  export RIPDPI_PHASE16_REQUESTED_NAMESPACE="$carrier_namespace"
  export RIPDPI_PHASE16_REAL_PROVIDER_CONFIG="$real_provider_config"
  local prepare_args=(
    "$entry_id"
    "$transport"
    "$ip_family"
    "$rooted"
    "$mode"
    "$artifact_root"
    "$network_condition"
    "$runner_required"
    "$evidence_tier"
    "$carrier_namespace"
  )
  prepare_hook_executed="true"
  set +e
  "$prepare_hook" "${prepare_args[@]}" >/dev/null 2>&1
  local hook_exit_code=$?
  set -e
  if [[ "$hook_exit_code" -ne 0 ]]; then
    write_prepare_hook_status "failed" "$hook_exit_code" || true
    runner_unavailable "real-provider prepare hook failed for namespace $carrier_namespace"
  fi
  write_prepare_hook_status "success" "$hook_exit_code"
}

case "$runner_required" in
  lab)
    ;;
  real-provider)
    real_provider_config_required="true"
    if [[ -z "$carrier_namespace" ]]; then
      runner_unavailable "real-provider Phase 16 entry requires PHASE16_CARRIER_NAMESPACE"
    fi
    if [[ -z "$real_provider_config" || ! -r "$real_provider_config" ]]; then
      runner_unavailable "real-provider Phase 16 entry requires readable RIPDPI_PHASE16_REAL_PROVIDER_CONFIG"
    fi
    real_provider_config_present="true"
    if ! python3 "$repo_root/scripts/ci/phase16_matrix.py" validate-real-provider-config \
      --config "$real_provider_config" \
      --namespace "$carrier_namespace"; then
      runner_unavailable \
        "real-provider Phase 16 runner config must define namespace $carrier_namespace with pcapScrubPolicy=required"
    fi
    real_provider_namespace_configured="true"
    real_provider_pcap_scrub_required="true"
    if [[ -z "$prepare_hook" ]]; then
      runner_unavailable "real-provider Phase 16 entry requires RIPDPI_PHASE16_PREPARE_HOOK"
    fi
    prepare_hook_configured="true"
    ;;
  *)
    failure_message="unsupported Phase 16 runner requirement: $runner_required"
    echo "$failure_message" >&2
    exit 1
    ;;
esac

if [[ -n "$prepare_hook" ]]; then
  if [[ ! -x "$prepare_hook" ]]; then
    failure_message="prepare hook is not executable: $prepare_hook"
    if [[ "$runner_required" == "real-provider" ]]; then
      runner_unavailable "$failure_message"
    fi
    echo "$failure_message" >&2
    exit 1
  fi
  if [[ -z "$cleanup_hook" || ! -x "$cleanup_hook" ]]; then
    failure_message="an executable RIPDPI_PHASE16_CLEANUP_HOOK is required with every prepare hook"
    echo "$failure_message" >&2
    exit 1
  fi
  prepare_hook_configured="true"
  cleanup_hook_configured="true"
  if [[ "$runner_required" == "real-provider" ]]; then
    run_real_provider_prepare_hook
  else
    prepare_args=(
      "$entry_id"
      "$transport"
      "$ip_family"
      "$rooted"
      "$mode"
      "$artifact_root"
      "$network_condition"
      "$runner_required"
      "$evidence_tier"
      "$carrier_namespace"
    )
    prepare_hook_executed="true"
    "$prepare_hook" "${prepare_args[@]}"
  fi
elif [[ "$network_condition" != "baseline" && "$execution_kind" != "l7_adversarial_emulator" ]]; then
  failure_message="non-baseline Phase 16 entry requires RIPDPI_PHASE16_PREPARE_HOOK: $network_condition"
  echo "$failure_message" >&2
  exit 1
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
  l7_adversarial_emulator)
    if [[ "$runner_required" != "lab" ]]; then
      failure_message="L7 adversarial Phase 16 entry must use lab runner requirement"
      echo "$failure_message" >&2
      exit 1
    fi
    if [[ "$evidence_tier" != "synthetic-adversarial" ]]; then
      failure_message="L7 adversarial Phase 16 entry must use synthetic-adversarial evidence tier"
      echo "$failure_message" >&2
      exit 1
    fi
    if [[ ! -x "$l7_dryrun_script" ]]; then
      failure_message="L7 adversarial dry-run script is not executable: $l7_dryrun_script"
      echo "$failure_message" >&2
      exit 1
    fi
    export RIPDPI_L7_ADVERSARIAL_ARTIFACT_DIR="$l7_artifact_dir"
    export RIPDPI_TSPU_ARTIFACT_DIR="$l7_artifact_dir"
    export PHASE16_L7_VERDICT_REPORT="l7-adversarial/verdict-report.json"
    bash "$l7_dryrun_script"
    if [[ ! -s "$l7_verdict_report" ]]; then
      failure_message="L7 adversarial verdict report missing or empty: $l7_verdict_report"
      echo "$failure_message" >&2
      exit 1
    fi
    if ! python3 - "$l7_verdict_report" <<'PY'
import json
import sys

report_path = sys.argv[1]
with open(report_path, "r", encoding="utf-8") as handle:
    report = json.load(handle)
cells = report.get("cells", [])
if not isinstance(cells, list) or not cells:
    print(f"L7 adversarial verdict report has no cells: {report_path}", file=sys.stderr)
    sys.exit(2)
failed = [
    f"{cell.get('desync_mode_id', '<unknown>')}::{cell.get('pattern_id', '<unknown>')}"
    for cell in cells
    if cell.get("verdict") == "blocked"
]
if failed:
    print("L7 adversarial release gate failed cells: " + ", ".join(failed[:10]), file=sys.stderr)
    if len(failed) > 10:
        print(f"... plus {len(failed) - 10} additional failed cells", file=sys.stderr)
    sys.exit(1)
print("L7 adversarial release gate passed")
PY
    then
      failure_message="L7 adversarial verdict report contains failed cells"
      exit 1
    fi
    ;;
  *)
    failure_message="unsupported execution kind: $execution_kind"
    echo "$failure_message" >&2
    exit 1
    ;;
esac
