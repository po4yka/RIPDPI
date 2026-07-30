#!/usr/bin/env bash
set -euo pipefail

original_args=("$@")
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
config_path="${RIPDPI_NETWORK_EVIDENCE_CONFIG:-}"
output_dir=""
source_sha=""
source_root="${RIPDPI_EVIDENCE_SOURCE_ROOT:-$repo_root}"
client_artifact=""
test_artifact=""
applies_to="android-client-release"
execution_kind=""
execution_id=""
execution_attempt=""

run_bounded() {
  local timeout_seconds="$1"
  shift
  python3 - "$timeout_seconds" "$@" <<'PY'
import subprocess
import sys

timeout_seconds = int(sys.argv[1])
try:
    completed = subprocess.run(sys.argv[2:], timeout=timeout_seconds)
except subprocess.TimeoutExpired:
    raise SystemExit(124)
raise SystemExit(completed.returncode)
PY
}

run_workload_bounded() {
  local timeout_seconds="$1"
  shift
  python3 - "$timeout_seconds" "$@" <<'PY'
import os
import signal
import subprocess
import sys

timeout_seconds = int(sys.argv[1])
process = subprocess.Popen(sys.argv[2:], start_new_session=True)
try:
    raise SystemExit(process.wait(timeout=timeout_seconds))
except subprocess.TimeoutExpired:
    os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait()
    raise SystemExit(124)
PY
}

usage() {
  cat <<'USAGE'
Usage: run-dual-vantage-network-evidence.sh --config FILE --output-dir DIR \
       --client-artifact FILE --test-artifact FILE \
       [--source-root CLEAN_GIT_CHECKOUT] \
       [--source-sha SHA] \
       [--applies-to android-client-release] \
       [--execution-kind github-actions|local --execution-id ID \
        --execution-attempt N]

The private config must be mode 0600 (or stricter) and contain absolute paths
to fixed client, observer, and workload hooks. Hook output is discarded. Only
the two allowlisted observation summaries and their validated manifest leave
the private scratch directory.

GitHub Actions executions may omit the execution arguments and use the
GITHUB_RUN_ID/GITHUB_RUN_ATTEMPT environment. Local executions must pass an
explicit local execution id and do not need GitHub environment variables.
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
    --source-root)
      source_root="${2:?missing --source-root value}"
      shift 2
      ;;
    --client-artifact)
      client_artifact="${2:?missing --client-artifact value}"
      shift 2
      ;;
    --test-artifact)
      test_artifact="${2:?missing --test-artifact value}"
      shift 2
      ;;
    --applies-to)
      applies_to="${2:?missing --applies-to value}"
      shift 2
      ;;
    --execution-kind)
      execution_kind="${2:?missing --execution-kind value}"
      shift 2
      ;;
    --execution-id)
      execution_id="${2:?missing --execution-id value}"
      shift 2
      ;;
    --execution-attempt)
      execution_attempt="${2:?missing --execution-attempt value}"
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
[[ -d "$source_root" && ! -L "$source_root" ]] || { echo "source root must be a non-symlink directory" >&2; exit 2; }
snapshot_active=false
if ! python3 - "$repo_root" "$source_root" <<'PY'
import os
import sys

raise SystemExit(0 if os.path.samefile(sys.argv[1], sys.argv[2]) else 1)
PY
then
  snapshot_active=true
fi
actual_source_sha="$(git -C "$source_root" rev-parse --verify HEAD 2>/dev/null)" || {
  echo "source root must be a Git checkout" >&2
  exit 2
}
[[ "$actual_source_sha" =~ ^[0-9a-f]{40}$ ]] || { echo "source HEAD is not a SHA-1 commit" >&2; exit 2; }
if [[ -n "$source_sha" && "$source_sha" != "$actual_source_sha" ]]; then
  echo "claimed source SHA does not match source checkout HEAD" >&2
  exit 2
fi
source_sha="$actual_source_sha"
[[ -z "$(git -C "$source_root" status --porcelain=v1 --untracked-files=normal)" ]] || {
  echo "source checkout must be clean" >&2
  exit 2
}
if [[ "$snapshot_active" == false ]]; then
  source_snapshot="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/ripdpi-network-evidence-source.XXXXXX")"
  chmod 700 "$source_snapshot"
  snapshot_paths=(
    test-lab/scripts/run-dual-vantage-network-evidence.sh
    test-lab/scripts/network-evidence-pcap-oracle.py
    scripts/ci/network_evidence_manifest.py
    scripts/ci/release_evidence_relaxations.py
    quality/release-gates/dns-ipv6-killswitch-gates.json
    quality/release-gates/android-network-evidence-actions.json
    quality/release-gates/network-evidence-producers.json
  )
  for relative_path in "${snapshot_paths[@]}"; do
    mkdir -p "$source_snapshot/$(dirname "$relative_path")"
    git -C "$source_root" show "$source_sha:$relative_path" > "$source_snapshot/$relative_path" || {
      rm -rf "$source_snapshot"
      echo "required evidence producer blob is missing from source SHA: $relative_path" >&2
      exit 2
    }
  done
  chmod 500 "$source_snapshot/test-lab/scripts/run-dual-vantage-network-evidence.sh"
  chmod 400 \
    "$source_snapshot/test-lab/scripts/network-evidence-pcap-oracle.py" \
    "$source_snapshot/scripts/ci/network_evidence_manifest.py" \
    "$source_snapshot/scripts/ci/release_evidence_relaxations.py" \
    "$source_snapshot/quality/release-gates/dns-ipv6-killswitch-gates.json" \
    "$source_snapshot/quality/release-gates/android-network-evidence-actions.json" \
    "$source_snapshot/quality/release-gates/network-evidence-producers.json"
  snapshot_status=0
  RIPDPI_EVIDENCE_SOURCE_ROOT="$source_root" \
    "$source_snapshot/test-lab/scripts/run-dual-vantage-network-evidence.sh" \
    "${original_args[@]}" || snapshot_status=$?
  rm -rf "$source_snapshot"
  exit "$snapshot_status"
fi
python3 - "$repo_root" "$source_root" "$source_sha" <<'PY'
import hashlib
import subprocess
import sys
from pathlib import Path

snapshot_root = Path(sys.argv[1])
source_root = Path(sys.argv[2])
source_sha = sys.argv[3]
paths = (
    "test-lab/scripts/run-dual-vantage-network-evidence.sh",
    "test-lab/scripts/network-evidence-pcap-oracle.py",
    "scripts/ci/network_evidence_manifest.py",
    "scripts/ci/release_evidence_relaxations.py",
    "quality/release-gates/dns-ipv6-killswitch-gates.json",
    "quality/release-gates/android-network-evidence-actions.json",
    "quality/release-gates/network-evidence-producers.json",
)
for relative in paths:
    expected = subprocess.run(
        ["git", "-C", str(source_root), "show", f"{source_sha}:{relative}"],
        check=True,
        capture_output=True,
    ).stdout
    actual = (snapshot_root / relative).read_bytes()
    if not hashlib.sha256(actual).digest() == hashlib.sha256(expected).digest():
        raise SystemExit(f"snapshot blob does not match source SHA: {relative}")
PY
[[ -n "$client_artifact" && -f "$client_artifact" && ! -L "$client_artifact" ]] || {
  echo "client artifact must be a regular non-symlink file" >&2
  exit 2
}
[[ -n "$test_artifact" && -f "$test_artifact" && ! -L "$test_artifact" ]] || {
  echo "test artifact must be a regular non-symlink file" >&2
  exit 2
}
[[ "$applies_to" == "android-client-release" ]] || { echo "unsupported evidence scope" >&2; exit 2; }
if [[ -z "$execution_kind" && -n "${GITHUB_RUN_ID:-}" ]]; then
  execution_kind="github-actions"
  execution_id="$GITHUB_RUN_ID"
  execution_attempt="${GITHUB_RUN_ATTEMPT:-}"
fi
[[ "$execution_kind" == "github-actions" || "$execution_kind" == "local" ]] || {
  echo "execution kind must be github-actions or local" >&2
  exit 2
}
[[ "$execution_id" =~ ^[a-z0-9][a-z0-9_-]{0,127}$ ]] || {
  echo "execution id has invalid format" >&2
  exit 2
}
[[ "$execution_attempt" =~ ^[1-9][0-9]*$ ]] || {
  echo "execution attempt must be a positive integer" >&2
  exit 2
}
if [[ "$execution_kind" == "github-actions" ]]; then
  [[ -n "${GITHUB_RUN_ID:-}" && "$execution_id" == "$GITHUB_RUN_ID" ]] || {
    echo "GitHub execution id must match GITHUB_RUN_ID" >&2
    exit 2
  }
  [[ -n "${GITHUB_RUN_ATTEMPT:-}" && "$execution_attempt" == "$GITHUB_RUN_ATTEMPT" ]] || {
    echo "GitHub execution attempt must match GITHUB_RUN_ATTEMPT" >&2
    exit 2
  }
  execution_definition=".github/workflows/dns-ipv6-killswitch-evidence.yml"
else
  execution_definition="local"
fi

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

copy_artifact() {
  local source="$1"
  local destination="$2"
  local digest_path="$3"
  local label="$4"
  python3 - "$source" "$destination" "$digest_path" "$label" <<'PY'
import hashlib
import os
import stat
import sys

source, destination, digest_path, label = sys.argv[1:]
flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
descriptor = os.open(source, flags)
try:
    metadata = os.fstat(descriptor)
    if not stat.S_ISREG(metadata.st_mode):
        raise SystemExit(f"{label} artifact must remain a regular file")
    chunks = []
    while chunk := os.read(descriptor, 1024 * 1024):
        chunks.append(chunk)
finally:
    os.close(descriptor)
payload = b"".join(chunks)
with open(destination, "xb") as handle:
    handle.write(payload)
os.chmod(destination, 0o400)
with open(digest_path, "x", encoding="ascii") as handle:
    handle.write(hashlib.sha256(payload).hexdigest())
PY
}
copy_artifact "$client_artifact" "$scratch/client-artifact.apk" \
  "$scratch/client-artifact.sha256" client
copy_artifact "$test_artifact" "$scratch/test-artifact.apk" \
  "$scratch/test-artifact.sha256" test
client_artifact="$scratch/client-artifact.apk"
test_artifact="$scratch/test-artifact.apk"
client_artifact_sha256="$(<"$scratch/client-artifact.sha256")"
test_artifact_sha256="$(<"$scratch/test-artifact.sha256")"
[[ "$client_artifact_sha256" != "$test_artifact_sha256" ]] || {
  echo "client and test artifacts must be distinct" >&2
  exit 2
}

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

python3 - "$config_path" "$scratch" \
  "$repo_root/quality/release-gates/network-evidence-producers.json" <<'PY'
import hashlib
import json
import os
import stat
import sys
from pathlib import Path

config_path = Path(sys.argv[1])
scratch = Path(sys.argv[2])
producer_policy_path = Path(sys.argv[3])
descriptor = os.open(config_path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
try:
    metadata = os.fstat(descriptor)
    if not stat.S_ISREG(metadata.st_mode):
        raise SystemExit("network evidence runner config must be a regular file")
    if stat.S_IMODE(metadata.st_mode) & 0o077:
        raise SystemExit("network evidence runner config must not be group/world accessible")
    if metadata.st_uid not in {0, os.getuid()}:
        raise SystemExit("network evidence runner config has an unexpected owner")
    chunks = []
    while chunk := os.read(descriptor, 1024 * 1024):
        chunks.append(chunk)
finally:
    os.close(descriptor)
config_payload = b"".join(chunks)
private_config = scratch / "private-config.json"
with private_config.open("xb") as handle:
    handle.write(config_payload)
private_config.chmod(0o400)
config = json.loads(config_payload.decode("utf-8"))
producer_policy = json.loads(producer_policy_path.read_text(encoding="utf-8"))
expected_policy_fields = {
    "version",
    "clientCollectorSha256",
    "observerCollectorSha256",
    "workloadSha256",
}
if not isinstance(producer_policy, dict) or set(producer_policy) != expected_policy_fields:
    raise SystemExit("network evidence producer policy fields do not match the v3 contract")
if producer_policy["version"] != "network_evidence_producers_v3":
    raise SystemExit("unsupported network evidence producer policy version")
expected = {
    "version",
    "clientHook",
    "observerHook",
    "workloadHook",
    "clientVantageId",
    "observerVantageId",
    "clientNetworkId",
    "observerNetworkId",
}
if not isinstance(config, dict) or set(config) != expected:
    raise SystemExit("network evidence runner config fields do not match the v1 contract")
if config["version"] != "ripdpi_network_evidence_runner_v1":
    raise SystemExit("unsupported network evidence runner config version")
hook_identities = {}
policy_fields = {
    "clientHook": "clientCollectorSha256",
    "observerHook": "observerCollectorSha256",
    "workloadHook": "workloadSha256",
}
for field in ("clientHook", "observerHook", "workloadHook"):
    raw = config[field]
    if not isinstance(raw, str) or not raw or "\n" in raw:
        raise SystemExit(f"{field} must be a non-empty absolute path")
    hook = Path(raw)
    if not hook.is_absolute() or hook.is_symlink() or not hook.is_file() or not os.access(hook, os.X_OK):
        raise SystemExit(f"{field} must identify an executable regular file")
    descriptor = os.open(hook, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    try:
        metadata = os.fstat(descriptor)
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_mode & 0o111 == 0:
            raise SystemExit(f"{field} must remain an executable regular file")
        hook_identities[field] = (metadata.st_dev, metadata.st_ino)
        chunks = []
        while chunk := os.read(descriptor, 1024 * 1024):
            chunks.append(chunk)
    finally:
        os.close(descriptor)
    payload = b"".join(chunks)
    digest = hashlib.sha256(payload).hexdigest()
    allowlist = producer_policy[policy_fields[field]]
    if (
        not isinstance(allowlist, list)
        or any(not isinstance(value, str) or len(value) != 64 for value in allowlist)
        or digest not in allowlist
    ):
        raise SystemExit(f"{field} digest is not approved by the source producer policy")
    copied_hook = scratch / f"{field}.exec"
    with copied_hook.open("xb") as handle:
        handle.write(payload)
    copied_hook.chmod(0o500)
    (scratch / f"{field}.path").write_text(str(copied_hook), encoding="utf-8")
    (scratch / f"{field}.sha256").write_text(
        digest, encoding="ascii"
    )
if hook_identities["clientHook"] == hook_identities["observerHook"]:
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
for field in ("clientNetworkId", "observerNetworkId"):
    value = config[field]
    if (
        not isinstance(value, str)
        or len(value) != 64
        or any(character not in "0123456789abcdef" for character in value)
    ):
        raise SystemExit(f"{field} must be a random 256-bit lowercase hex identifier")
    digest = hashlib.sha256(f"ripdpi:network:v1:{value}".encode()).hexdigest()
    (scratch / f"{field}.sha256").write_text(digest, encoding="ascii")
if config["clientNetworkId"] == config["observerNetworkId"]:
    raise SystemExit("client and observer network identifiers must differ")
private_identity_fields = (
    "clientVantageId",
    "observerVantageId",
    "clientNetworkId",
    "observerNetworkId",
)
if len({config[field] for field in private_identity_fields}) != len(private_identity_fields):
    raise SystemExit("vantage and network identifiers must be independently generated")
PY

client_hook="$(<"$scratch/clientHook.path")"
observer_hook="$(<"$scratch/observerHook.path")"
workload_hook="$(<"$scratch/workloadHook.path")"
client_collector_sha256="$(<"$scratch/clientHook.sha256")"
observer_collector_sha256="$(<"$scratch/observerHook.sha256")"
workload_sha256="$(<"$scratch/workloadHook.sha256")"
client_vantage_sha256="$(<"$scratch/clientVantageId.sha256")"
observer_vantage_sha256="$(<"$scratch/observerVantageId.sha256")"
client_network_sha256="$(<"$scratch/clientNetworkId.sha256")"
observer_network_sha256="$(<"$scratch/observerNetworkId.sha256")"

verify_installed_artifacts() {
  local phase="$1"
  local device_lines device_count
  device_lines="$(run_bounded 30 adb devices | awk '$2 == "device" { print $1 }')"
  device_count="$(printf '%s\n' "$device_lines" | awk 'NF { count++ } END { print count + 0 }')"
  [[ "$device_count" -eq 1 ]] || {
    echo "exactly one connected physical Android device is required" >&2
    return 1
  }
  local serial="$device_lines"
  local qemu product package_name artifact label remote_apk pulled path_count
  qemu="$(run_bounded 30 adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')"
  product="$(run_bounded 30 adb -s "$serial" shell getprop ro.product.name | tr -d '\r')"
  [[ "$qemu" != "1" && "$product" != *sdk* && "$product" != *emulator* ]] || {
    echo "network evidence requires a physical Android device" >&2
    return 1
  }
  for package_name in com.poyka.ripdpi com.poyka.ripdpi.test; do
    if [[ "$package_name" == "com.poyka.ripdpi" ]]; then
      artifact="$client_artifact"
      label="client"
    else
      artifact="$test_artifact"
      label="test"
    fi
    remote_apk="$(run_bounded 30 adb -s "$serial" shell pm path "$package_name" | sed -n 's/^package://p' | tr -d '\r')"
    path_count="$(printf '%s\n' "$remote_apk" | awk 'NF { count++ } END { print count + 0 }')"
    [[ "$path_count" -eq 1 ]] || {
      echo "installed RIPDPI $label package must resolve to exactly one APK" >&2
      return 1
    }
    pulled="$scratch/installed-${label}-${phase}.apk"
    run_bounded 60 adb -s "$serial" pull "$remote_apk" "$pulled" >/dev/null
    cmp -s "$artifact" "$pulled" || {
      echo "installed RIPDPI $label APK does not match the supplied artifact" >&2
      return 1
    }
    rm -f "$pulled"
  done
}

verify_installed_artifacts before
correlation_id="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
runner_sha256="$(python3 - "$repo_root/test-lab/scripts/run-dual-vantage-network-evidence.sh" <<'PY'
import hashlib
import sys

with open(sys.argv[1], "rb") as handle:
    print(hashlib.sha256(handle.read()).hexdigest())
PY
)"
validator_sha256="$(python3 - "$repo_root/scripts/ci/network_evidence_manifest.py" <<'PY'
import hashlib
import sys

with open(sys.argv[1], "rb") as handle:
    print(hashlib.sha256(handle.read()).hexdigest())
PY
)"
policy_sha256="$(python3 - "$repo_root/quality/release-gates/dns-ipv6-killswitch-gates.json" <<'PY'
import hashlib
import sys

with open(sys.argv[1], "rb") as handle:
    print(hashlib.sha256(handle.read()).hexdigest())
PY
)"
producer_policy_sha256="$(python3 - "$repo_root/quality/release-gates/network-evidence-producers.json" <<'PY'
import hashlib
import sys

with open(sys.argv[1], "rb") as handle:
    print(hashlib.sha256(handle.read()).hexdigest())
PY
)"
RIPDPI_EVIDENCE_GATE_IDS="$(python3 - "$repo_root/quality/release-gates/dns-ipv6-killswitch-gates.json" "$applies_to" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    policy = json.load(handle)
scope = sys.argv[2]
policy_scopes = policy.get("appliesTo", [])
gate_ids = [
    gate["id"]
    for gate in policy["gates"]
    if gate.get("evidenceSources", {}).get(scope, gate.get("evidenceSource"))
    == "dual-vantage-network-manifest"
    and scope in gate.get("appliesTo", policy_scopes)
]
if not gate_ids:
    raise SystemExit(f"policy has no dual-vantage gates for {scope}")
print(",".join(gate_ids))
PY
)"
export RIPDPI_EVIDENCE_GATE_IDS

# Hooks are copied into scratch and executed in isolation, so they cannot import
# a sibling module. The oracle is handed over as the snapshot copy, which came
# from `git show` at the exact source SHA and is mode 400 for the whole run --
# a hook cannot silently substitute a different derivation of the counters.
RIPDPI_EVIDENCE_ORACLE="$repo_root/test-lab/scripts/network-evidence-pcap-oracle.py"
[[ -f "$RIPDPI_EVIDENCE_ORACLE" ]] || {
  echo "source-bound pcap oracle is missing from the snapshot" >&2
  exit 2
}
export RIPDPI_EVIDENCE_ORACLE

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

workload_timeout_seconds=600
if [[ -n "${RIPDPI_TEST_WORKLOAD_TIMEOUT_SECONDS:-}" ]]; then
  [[ "$RIPDPI_TEST_WORKLOAD_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]] || {
    echo "test workload timeout must be a positive integer" >&2
    exit 2
  }
  (( RIPDPI_TEST_WORKLOAD_TIMEOUT_SECONDS <= workload_timeout_seconds )) || {
    echo "test workload timeout cannot extend the production bound" >&2
    exit 2
  }
  workload_timeout_seconds="$RIPDPI_TEST_WORKLOAD_TIMEOUT_SECONDS"
fi
run_workload_bounded "$workload_timeout_seconds" "$workload_hook" \
  "$correlation_id" \
  "$source_sha" \
  "$plan_path" \
  "$client_artifact" \
  "$client_artifact_sha256" \
  "$test_artifact" \
  "$test_artifact_sha256" >/dev/null 2>&1 || {
  echo "network evidence workload failed" >&2
  exit 1
}
[[ -s "$plan_path" ]] || { echo "network evidence workload did not write a scenario plan" >&2; exit 1; }
validated_plan_path="$scratch/validated-scenario-plan.json"
python3 "$repo_root/scripts/ci/network_evidence_manifest.py" validate-plan \
  --input "$plan_path" \
  --output "$validated_plan_path" \
  --source-sha "$source_sha" \
  --correlation-id "$correlation_id" \
  --client-artifact-sha256 "$client_artifact_sha256" \
  --test-artifact-sha256 "$test_artifact_sha256" \
  --applies-to "$applies_to"
mv "$validated_plan_path" "$plan_path"
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
verify_installed_artifacts after
[[ -s "$client_observation" ]] || { echo "client observation is missing" >&2; exit 1; }
[[ -s "$observer_observation" ]] || { echo "observer observation is missing" >&2; exit 1; }

mkdir -m 700 "$publish_dir"
python3 "$repo_root/scripts/ci/network_evidence_manifest.py" stamp-observation \
  --input "$client_observation" \
  --plan "$plan_path" \
  --output "$publish_dir/client-observation.json" \
  --role client-underlay \
  --source-sha "$source_sha" \
  --correlation-id "$correlation_id" \
  --vantage-id-sha256 "$client_vantage_sha256" \
  --network-id-sha256 "$client_network_sha256" \
  --collector-sha256 "$client_collector_sha256" \
  --client-artifact-sha256 "$client_artifact_sha256" \
  --test-artifact-sha256 "$test_artifact_sha256" \
  --applies-to "$applies_to"
python3 "$repo_root/scripts/ci/network_evidence_manifest.py" stamp-observation \
  --input "$observer_observation" \
  --plan "$plan_path" \
  --output "$publish_dir/observer-observation.json" \
  --role external-observer \
  --source-sha "$source_sha" \
  --correlation-id "$correlation_id" \
  --vantage-id-sha256 "$observer_vantage_sha256" \
  --network-id-sha256 "$observer_network_sha256" \
  --collector-sha256 "$observer_collector_sha256" \
  --client-artifact-sha256 "$client_artifact_sha256" \
  --test-artifact-sha256 "$test_artifact_sha256" \
  --applies-to "$applies_to"
python3 "$repo_root/scripts/ci/network_evidence_manifest.py" assemble \
  --client "$publish_dir/client-observation.json" \
  --observer "$publish_dir/observer-observation.json" \
  --source-sha "$source_sha" \
  --applies-to "$applies_to" \
  --execution-kind "$execution_kind" \
  --execution-id "$execution_id" \
  --execution-attempt "$execution_attempt" \
  --execution-definition "$execution_definition" \
  --runner-sha256 "$runner_sha256" \
  --validator-sha256 "$validator_sha256" \
  --policy-sha256 "$policy_sha256" \
  --producer-policy-sha256 "$producer_policy_sha256" \
  --workload-sha256 "$workload_sha256" \
  --client-artifact-sha256 "$client_artifact_sha256" \
  --test-artifact-sha256 "$test_artifact_sha256" \
  --output "$publish_dir/manifest.json"
validation_args=(
  --manifest "$publish_dir/manifest.json"
  --artifact-root "$publish_dir"
  --expected-source-sha "$source_sha"
  --applies-to "$applies_to"
  --expected-execution-kind "$execution_kind"
  --expected-execution-id "$execution_id"
  --expected-execution-attempt "$execution_attempt"
  --require-pass
)
if [[ -n "${RIPDPI_TEST_REPO_ROOT:-}" && "${RIPDPI_TEST_ALLOW_UNREADY_ACTIONS:-}" == "1" ]]; then
  validation_args+=(--allow-unready-actions-for-synthetic-test)
fi
python3 "$repo_root/scripts/ci/network_evidence_manifest.py" validate "${validation_args[@]}"

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
