#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
config_path="${RIPDPI_NETWORK_EVIDENCE_CONFIG:-}"
output_dir=""
source_sha=""
client_artifact_sha256=""
applies_to="android-client-release"
workflow_run_id="${GITHUB_RUN_ID:-}"
workflow_run_attempt="${GITHUB_RUN_ATTEMPT:-}"

usage() {
  cat <<'USAGE'
Usage: run-dual-vantage-network-evidence.sh --config FILE --output-dir DIR \
       --source-sha SHA --client-artifact-sha256 SHA256 \
       [--applies-to android-client-release]

The private config must be mode 0600 (or stricter) and contain absolute paths
to fixed client, observer, and workload hooks. Hook output is discarded. Only
the two allowlisted observation summaries and their validated manifest leave
the private scratch directory.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --config)
      config_path="${2:?missing --config value}"
      shift 2
      ;;
    --output-dir)
      output_dir="${2:?missing --output-dir value}"
      shift 2
      ;;
    --source-sha)
      source_sha="${2:?missing --source-sha value}"
      shift 2
      ;;
    --client-artifact-sha256)
      client_artifact_sha256="${2:?missing --client-artifact-sha256 value}"
      shift 2
      ;;
    --applies-to)
      applies_to="${2:?missing --applies-to value}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

[[ -n "$config_path" ]] || { echo "network evidence runner config is required" >&2; exit 2; }
[[ -n "$output_dir" ]] || { echo "network evidence output directory is required" >&2; exit 2; }
[[ "$source_sha" =~ ^[0-9a-f]{40}$ ]] || { echo "source SHA must be 40 lowercase hex characters" >&2; exit 2; }
[[ "$client_artifact_sha256" =~ ^[0-9a-f]{64}$ ]] || { echo "client artifact SHA-256 must be 64 lowercase hex characters" >&2; exit 2; }
[[ "$applies_to" == "android-client-release" ]] || { echo "unsupported evidence scope" >&2; exit 2; }
[[ "$workflow_run_id" =~ ^[1-9][0-9]*$ ]] || { echo "GITHUB_RUN_ID is required" >&2; exit 2; }
[[ "$workflow_run_attempt" =~ ^[1-9][0-9]*$ ]] || { echo "GITHUB_RUN_ATTEMPT is required" >&2; exit 2; }

publication_names=(client-observation.json observer-observation.json manifest.json)

prepare_output_directory() {
  if [[ -L "$output_dir" ]]; then
    echo "network evidence output directory must not be a symlink" >&2
    return 1
  fi
  if [[ -e "$output_dir" ]]; then
    [[ -d "$output_dir" ]] || {
      echo "network evidence output path must be a directory" >&2
      return 1
    }
  else
    mkdir -m 700 "$output_dir"
  fi

  local unexpected
  unexpected="$(find "$output_dir" -mindepth 1 -maxdepth 1 \
    ! \( -name client-observation.json -o -name observer-observation.json -o -name manifest.json \) \
    -print -quit)"
  [[ -z "$unexpected" ]] || {
    echo "network evidence output directory contains an unexpected entry" >&2
    return 1
  }

  local name path
  for name in "${publication_names[@]}"; do
    path="$output_dir/$name"
    if [[ -e "$path" || -L "$path" ]]; then
      [[ -f "$path" || -L "$path" ]] || {
        echo "network evidence output entry must be a file" >&2
        return 1
      }
    fi
  done
  for name in "${publication_names[@]}"; do
    rm -f -- "$output_dir/$name"
  done
  chmod 700 "$output_dir"
}

prepare_output_directory

scratch="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/ripdpi-network-evidence.XXXXXX")"
chmod 700 "$scratch"
stop_file="$scratch/stop"
client_ready="$scratch/client.ready"
observer_ready="$scratch/observer.ready"
plan_path="$scratch/scenario-plan.json"
client_observation="$scratch/client-observation.json"
observer_observation="$scratch/observer-observation.json"
publish_dir="$scratch/publish"
client_pid=""
observer_pid=""

hook_process_running() {
  local pid="$1"
  local state
  [[ -n "$pid" ]] || return 1
  kill -0 "$pid" 2>/dev/null || return 1
  state="$(ps -o stat= -p "$pid" 2>/dev/null | tr -d ' ')"
  [[ -n "$state" && "${state:0:1}" != "Z" ]]
}

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  : > "$stop_file" 2>/dev/null || true
  local deadline=$((SECONDS + 2))
  while (( SECONDS < deadline )); do
    local running=false
    for pid in "$client_pid" "$observer_pid"; do
      if [[ -n "$pid" ]] && kill -0 -- "-$pid" 2>/dev/null; then
        running=true
      fi
    done
    [[ "$running" == false ]] && break
    sleep 0.1
  done
  for pid in "$client_pid" "$observer_pid"; do
    if [[ -n "$pid" ]] && kill -0 -- "-$pid" 2>/dev/null; then
      kill -TERM -- "-$pid" 2>/dev/null || true
    fi
  done
  sleep 0.1
  for pid in "$client_pid" "$observer_pid"; do
    if [[ -n "$pid" ]] && kill -0 -- "-$pid" 2>/dev/null; then
      kill -KILL -- "-$pid" 2>/dev/null || true
    fi
  done
  for pid in "$client_pid" "$observer_pid"; do
    if [[ -n "$pid" ]]; then
      wait "$pid" 2>/dev/null || true
    fi
  done
  rm -rf "$scratch"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

python3 - "$config_path" "$scratch" <<'PY'
import hashlib
import json
import os
import stat
import sys
from pathlib import Path

config_path = Path(sys.argv[1])
scratch = Path(sys.argv[2])
if config_path.is_symlink() or not config_path.is_file():
    raise SystemExit("network evidence runner config must be a regular file")
mode = stat.S_IMODE(config_path.stat().st_mode)
if mode & 0o077:
    raise SystemExit("network evidence runner config must not be group/world accessible")
if config_path.stat().st_uid not in {0, os.getuid()}:
    raise SystemExit("network evidence runner config has an unexpected owner")
with config_path.open(encoding="utf-8") as handle:
    config = json.load(handle)
expected = {
    "version",
    "clientHook",
    "observerHook",
    "workloadHook",
    "clientVantageId",
    "observerVantageId",
}
if not isinstance(config, dict) or set(config) != expected:
    raise SystemExit("network evidence runner config fields do not match the v1 contract")
if config["version"] != "ripdpi_network_evidence_runner_v1":
    raise SystemExit("unsupported network evidence runner config version")
for field in ("clientHook", "observerHook", "workloadHook"):
    raw = config[field]
    if not isinstance(raw, str) or not raw or "\n" in raw:
        raise SystemExit(f"{field} must be a non-empty absolute path")
    hook = Path(raw)
    if not hook.is_absolute() or hook.is_symlink() or not hook.is_file() or not os.access(hook, os.X_OK):
        raise SystemExit(f"{field} must identify an executable regular file")
    (scratch / f"{field}.path").write_text(str(hook), encoding="utf-8")
    (scratch / f"{field}.sha256").write_text(
        hashlib.sha256(hook.read_bytes()).hexdigest(), encoding="ascii"
    )
if os.path.samefile(config["clientHook"], config["observerHook"]):
    raise SystemExit("clientHook and observerHook must identify different collectors")
for field in ("clientVantageId", "observerVantageId"):
    value = config[field]
    if (
        not isinstance(value, str)
        or len(value) != 64
        or any(character not in "0123456789abcdef" for character in value)
    ):
        raise SystemExit(f"{field} must be a random 256-bit lowercase hex identifier")
    digest = hashlib.sha256(f"ripdpi:vantage:v1:{value}".encode()).hexdigest()
    (scratch / f"{field}.sha256").write_text(digest, encoding="ascii")
if config["clientVantageId"] == config["observerVantageId"]:
    raise SystemExit("client and observer vantage identifiers must differ")
PY

client_hook="$(<"$scratch/clientHook.path")"
observer_hook="$(<"$scratch/observerHook.path")"
workload_hook="$(<"$scratch/workloadHook.path")"
client_collector_sha256="$(<"$scratch/clientHook.sha256")"
observer_collector_sha256="$(<"$scratch/observerHook.sha256")"
workload_sha256="$(<"$scratch/workloadHook.sha256")"
client_vantage_sha256="$(<"$scratch/clientVantageId.sha256")"
observer_vantage_sha256="$(<"$scratch/observerVantageId.sha256")"
correlation_id="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"

python3 -c 'import os, sys; os.setsid(); os.execv(sys.argv[1], sys.argv[1:])' "$client_hook" \
  "$correlation_id" "$source_sha" "$client_ready" "$stop_file" "$plan_path" "$client_observation" \
  >/dev/null 2>&1 &
client_pid=$!
python3 -c 'import os, sys; os.setsid(); os.execv(sys.argv[1], sys.argv[1:])' "$observer_hook" \
  "$correlation_id" "$source_sha" "$observer_ready" "$stop_file" "$plan_path" "$observer_observation" \
  >/dev/null 2>&1 &
observer_pid=$!

deadline=$((SECONDS + 30))
while [[ ! -f "$client_ready" || ! -f "$observer_ready" ]]; do
  kill -0 "$client_pid" 2>/dev/null || { echo "client capture hook exited before ready" >&2; exit 1; }
  kill -0 "$observer_pid" 2>/dev/null || { echo "observer capture hook exited before ready" >&2; exit 1; }
  (( SECONDS < deadline )) || { echo "dual-vantage capture hooks did not become ready" >&2; exit 1; }
  sleep 0.1
done

"$workload_hook" "$correlation_id" "$source_sha" "$plan_path" >/dev/null 2>&1 || {
  echo "network evidence workload failed" >&2
  exit 1
}
[[ -s "$plan_path" ]] || { echo "network evidence workload did not write a scenario plan" >&2; exit 1; }
: > "$stop_file"

shutdown_deadline=$((SECONDS + 30))
while hook_process_running "$client_pid" || hook_process_running "$observer_pid"; do
  if (( SECONDS >= shutdown_deadline )); then
    echo "dual-vantage capture hooks did not stop within 30 seconds" >&2
    exit 1
  fi
  sleep 0.1
done

client_status=0
observer_status=0
wait "$client_pid" || client_status=$?
wait "$observer_pid" || observer_status=$?
[[ "$client_status" -eq 0 ]] || { echo "client capture hook failed" >&2; exit 1; }
[[ "$observer_status" -eq 0 ]] || { echo "observer capture hook failed" >&2; exit 1; }
for pid in "$client_pid" "$observer_pid"; do
  if kill -0 -- "-$pid" 2>/dev/null; then
    echo "capture hook left a descendant running after shutdown" >&2
    exit 1
  fi
done
[[ -s "$client_observation" ]] || { echo "client observation is missing" >&2; exit 1; }
[[ -s "$observer_observation" ]] || { echo "observer observation is missing" >&2; exit 1; }

mkdir -m 700 "$publish_dir"
python3 "$repo_root/scripts/ci/network_evidence_manifest.py" stamp-observation \
  --input "$client_observation" \
  --output "$publish_dir/client-observation.json" \
  --role client-underlay \
  --source-sha "$source_sha" \
  --correlation-id "$correlation_id" \
  --vantage-id-sha256 "$client_vantage_sha256" \
  --collector-sha256 "$client_collector_sha256"
python3 "$repo_root/scripts/ci/network_evidence_manifest.py" stamp-observation \
  --input "$observer_observation" \
  --output "$publish_dir/observer-observation.json" \
  --role external-observer \
  --source-sha "$source_sha" \
  --correlation-id "$correlation_id" \
  --vantage-id-sha256 "$observer_vantage_sha256" \
  --collector-sha256 "$observer_collector_sha256"
python3 "$repo_root/scripts/ci/network_evidence_manifest.py" assemble \
  --client "$publish_dir/client-observation.json" \
  --observer "$publish_dir/observer-observation.json" \
  --source-sha "$source_sha" \
  --applies-to "$applies_to" \
  --workflow-run-id "$workflow_run_id" \
  --workflow-run-attempt "$workflow_run_attempt" \
  --workload-sha256 "$workload_sha256" \
  --client-artifact-sha256 "$client_artifact_sha256" \
  --output "$publish_dir/manifest.json"
python3 "$repo_root/scripts/ci/network_evidence_manifest.py" validate \
  --manifest "$publish_dir/manifest.json" \
  --artifact-root "$publish_dir" \
  --expected-source-sha "$source_sha" \
  --applies-to "$applies_to" \
  --expected-workflow-run-id "$workflow_run_id" \
  --expected-workflow-run-attempt "$workflow_run_attempt"

[[ -d "$output_dir" && ! -L "$output_dir" && \
  -z "$(find "$output_dir" -mindepth 1 -maxdepth 1 -print -quit)" ]] || {
  echo "network evidence output directory must remain empty until publication" >&2
  exit 1
}
for name in "${publication_names[@]}"; do
  install -m 600 "$publish_dir/$name" "$output_dir/$name"
done
published_count="$(find "$output_dir" -mindepth 1 -maxdepth 1 -type f | wc -l | tr -d ' ')"
[[ "$published_count" == "3" ]] || { echo "unexpected network evidence artifact count" >&2; exit 1; }

echo "Dual-vantage network evidence bundle validated for ${source_sha:0:12}."
