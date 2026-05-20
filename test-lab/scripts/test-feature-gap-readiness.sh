#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=test-lab/scripts/python.sh
source "$repo_root/test-lab/scripts/python.sh"
python_bin="$(ripdpi_resolve_python "feature gap readiness tests")"
tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/ripdpi-readiness-test.XXXXXX")"
trap 'rm -rf "$tmpdir"' EXIT

default_json="$tmpdir/feature-gap-readiness.json"
invalid_generated_at_json="$tmpdir/feature-gap-readiness-invalid-generated-at.json"
invalid_host_json="$tmpdir/feature-gap-readiness-invalid-host.json"
duplicate_json="$tmpdir/feature-gap-readiness-duplicate.json"
invalid_status_json="$tmpdir/feature-gap-readiness-invalid-status.json"
invalid_required_json="$tmpdir/feature-gap-readiness-invalid-required.json"
unexpected_json="$tmpdir/feature-gap-readiness-unexpected.json"
relay_json="$tmpdir/feature-gap-readiness-with-relay.json"
unknown_json="$tmpdir/feature-gap-readiness-unknown-remote.json"
atomic_json="$tmpdir/feature-gap-readiness-atomic.json"
multi_device_json="$tmpdir/feature-gap-readiness-multi-device.json"
netem_container_json="$tmpdir/feature-gap-readiness-netem-container.json"
cellular_out_of_service_json="$tmpdir/feature-gap-readiness-cellular-out-of-service.json"
fake_adb="$tmpdir/fake-adb"
fake_docker="$tmpdir/docker"
validator="$tmpdir/validate-readiness.py"

"$repo_root/test-lab/scripts/check-feature-gap-readiness.sh" \
  --output "$default_json" >/dev/null
"$repo_root/test-lab/scripts/check-feature-test-signoff.sh" \
  --list-required-readiness > "$tmpdir/signoff-required-readiness.txt"

cat > "$validator" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)
with open(sys.argv[2], encoding="utf-8") as handle:
    signoff_required = {line.strip() for line in handle if line.strip()}

required = {
    "android_device",
    "rooted_physical_device",
    "manual_talkback",
    "physical_network_handover",
    "routed_netem_vm",
    "production_relay_matrix",
    "remote_workflow_confirmation",
}
if signoff_required != required:
    raise SystemExit(
        "readiness/signoff required row mismatch: "
        f"readiness={sorted(required)!r} signoff={sorted(signoff_required)!r}"
    )
if not isinstance(data, dict):
    raise SystemExit("readiness artifact must be a JSON object")
generated_at = data.get("generatedAtEpoch")
if not isinstance(generated_at, int) or generated_at <= 0:
    raise SystemExit("readiness artifact generatedAtEpoch must be a positive integer")
host = data.get("host")
if not isinstance(host, str):
    raise SystemExit("readiness artifact host must be a string")
checks_list = data.get("checks")
if not isinstance(checks_list, list):
    raise SystemExit("readiness artifact checks must be an array")

checks = {}
allowed_statuses = {"ready", "manual", "blocked"}
for index, check in enumerate(checks_list):
    if not isinstance(check, dict):
        raise SystemExit(f"readiness checks[{index}] must be an object")
    name = check.get("name")
    status = check.get("status")
    required_value = check.get("required")
    message = check.get("message")
    if not isinstance(name, str) or not name:
        raise SystemExit(f"readiness checks[{index}].name must be a non-empty string")
    if name in checks:
        raise SystemExit(f"duplicate readiness check: {name}")
    if status not in allowed_statuses:
        raise SystemExit(
            f"{name} readiness status must be one of {sorted(allowed_statuses)}, "
            f"got {status!r}"
        )
    if required_value is not True and required_value is not False:
        raise SystemExit(f"{name} readiness required must be a boolean")
    if not isinstance(message, str):
        raise SystemExit(f"{name} readiness message must be a string")
    checks[name] = check

actual_required = {name for name, check in checks.items() if check.get("required") is True}
if actual_required != required:
    raise SystemExit(
        "readiness artifact required row mismatch: "
        f"actual={sorted(actual_required)!r} required={sorted(required)!r}"
    )
extra = sorted(set(checks).difference(required))
if extra:
    raise SystemExit(f"unexpected readiness checks: {extra}")
PY

"$python_bin" "$validator" "$default_json" "$tmpdir/signoff-required-readiness.txt"
cp "$default_json" "$atomic_json"

"$python_bin" - "$default_json" <<'PY'
import json
import re
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)
checks = {check["name"]: check for check in data.get("checks", [])}

remote = checks["remote_workflow_confirmation"]
message = remote.get("message", "")
if (
    "Local branch" not in message
    and "Local working tree has" not in message
    and "Could not compare" not in message
):
    raise SystemExit(f"remote workflow message lost branch context: {message!r}")
if (
    remote.get("status") == "blocked"
    and "review branch" not in message
    and "uncommitted change" not in message
    and "Could not compare" not in message
):
    raise SystemExit(f"remote workflow message lost ruleset path: {message!r}")
if re.search(r"\b[0-9a-f]{7,40}\b", message) or re.search(r"\bby \d+ commit", message):
    raise SystemExit(f"remote workflow message is commit-specific: {message!r}")
PY

pids=()
for _ in 1 2 3 4 5; do
  "$repo_root/test-lab/scripts/check-feature-gap-readiness.sh" \
    --output "$atomic_json" >/dev/null &
  pids+=("$!")
done
for _ in 1 2 3 4 5 6 7 8 9 10; do
  "$python_bin" "$validator" "$atomic_json" "$tmpdir/signoff-required-readiness.txt"
done
for pid in "${pids[@]}"; do
  wait "$pid"
done
"$python_bin" "$validator" "$atomic_json" "$tmpdir/signoff-required-readiness.txt"

make_fixture() {
  local output="$1"
  local mutation="$2"
  "$python_bin" - "$default_json" "$output" "$mutation" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)
mutation = sys.argv[3]
if mutation == "invalid_generated_at":
    data["generatedAtEpoch"] = "now"
elif mutation == "invalid_host":
    data["host"] = 42
elif mutation == "duplicate":
    data["checks"].append(dict(data["checks"][0]))
elif mutation == "invalid_status":
    data["checks"][0]["status"] = "done"
elif mutation == "invalid_required":
    data["checks"][0]["required"] = "true"
elif mutation == "unexpected":
    data["checks"].append(
        {
            "name": "unexpected_check",
            "status": "ready",
            "required": False,
            "message": "unexpected",
        }
    )
else:
    raise SystemExit(f"unknown readiness fixture mutation: {mutation}")
with open(sys.argv[2], "w", encoding="utf-8") as handle:
    json.dump(data, handle)
PY
}

expect_invalid_readiness() {
  local fixture="$1"
  local expected="$2"
  set +e
  "$python_bin" "$validator" "$fixture" "$tmpdir/signoff-required-readiness.txt" \
    > "$tmpdir/invalid-readiness.out" 2>&1
  local status=$?
  set -e
  cat "$tmpdir/invalid-readiness.out"
  if [[ "$status" -ne 1 ]]; then
    echo "Expected invalid readiness fixture to fail, got $status" >&2
    cat "$tmpdir/invalid-readiness.out" >&2
    exit 1
  fi
  grep -F "$expected" "$tmpdir/invalid-readiness.out"
}

make_fixture "$invalid_generated_at_json" "invalid_generated_at"
expect_invalid_readiness \
  "$invalid_generated_at_json" \
  "readiness artifact generatedAtEpoch must be a positive integer"
make_fixture "$invalid_host_json" "invalid_host"
expect_invalid_readiness "$invalid_host_json" "readiness artifact host must be a string"
make_fixture "$duplicate_json" "duplicate"
expect_invalid_readiness "$duplicate_json" "duplicate readiness check: android_device"
make_fixture "$invalid_status_json" "invalid_status"
expect_invalid_readiness "$invalid_status_json" "android_device readiness status must be one of"
make_fixture "$invalid_required_json" "invalid_required"
expect_invalid_readiness "$invalid_required_json" "android_device readiness required must be a boolean"
make_fixture "$unexpected_json" "unexpected"
expect_invalid_readiness "$unexpected_json" "unexpected readiness checks: ['unexpected_check']"

RIPDPI_IGNORE_DIRTY_WORKTREE_FOR_READINESS=true \
  RIPDPI_REMOTE_COMPARE_REF="origin/ripdpi-missing-test-ref" \
  "$repo_root/test-lab/scripts/check-feature-gap-readiness.sh" \
  --output "$unknown_json" >/dev/null
"$python_bin" "$validator" "$unknown_json" "$tmpdir/signoff-required-readiness.txt"

"$python_bin" - "$unknown_json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)

checks = {check["name"]: check for check in data.get("checks", [])}
remote = checks.get("remote_workflow_confirmation")
if remote is None:
    raise SystemExit("missing remote_workflow_confirmation readiness check")
if remote.get("status") != "blocked":
    raise SystemExit(f"expected unknown remote compare to block, got {remote.get('status')!r}")
message = remote.get("message", "")
if "Could not compare" not in message or "origin/ripdpi-missing-test-ref" not in message:
    raise SystemExit(f"unknown remote compare message is unclear: {message!r}")
PY

cat > "$fake_adb" <<'SH'
#!/usr/bin/env bash
set -euo pipefail

serial=""
if [[ "${1:-}" == "-s" ]]; then
  serial="${2:-}"
  shift 2
fi

case "${1:-}" in
  devices)
    cat <<'EOF'
List of devices attached
pixel-serial	device usb:1 product:husky model:Pixel_8_Pro device:husky transport_id:1
emulator-5554	device product:sdk_gphone64_arm64 model:sdk_gphone64_arm64 device:emu64a transport_id:2
EOF
    ;;
  get-state)
    if [[ "$serial" == "pixel-serial" || "$serial" == "emulator-5554" ]]; then
      echo "device"
    else
      echo "error: more than one device/emulator" >&2
      exit 1
    fi
    ;;
  shell)
    shift
    case "$*" in
      "getprop ro.product.model")
        if [[ "$serial" == "pixel-serial" ]]; then
          echo "Pixel 8 Pro"
        else
          echo "Android SDK built for arm64"
        fi
        ;;
      "getprop ro.build.version.sdk")
        echo "36"
        ;;
      "getprop ro.build.version.release_or_codename")
        echo "16"
        ;;
      "su 0 id")
        echo "permission denied" >&2
        exit 1
        ;;
      "settings get secure enabled_accessibility_services")
        echo "com.example/.OtherService"
        ;;
      "settings get secure accessibility_enabled")
        echo "1"
        ;;
      "pm list packages")
        echo "package:com.google.android.marvin.talkback"
        ;;
      "dumpsys connectivity")
        cat <<'EOF'
NetworkAgentInfo{netId=100 Transports: WIFI}
NetworkAgentInfo{netId=101 Transports: CELLULAR}
EOF
        ;;
      "dumpsys telephony.registry")
        if [[ "${RIPDPI_FAKE_CELLULAR_SERVICE:-in_service}" == "out_of_service" ]]; then
          echo "mServiceState={mVoiceRegState=1(OUT_OF_SERVICE), mDataRegState=1(OUT_OF_SERVICE)}"
        else
          echo "mServiceState={mVoiceRegState=0(IN_SERVICE), mDataRegState=0(IN_SERVICE)}"
        fi
        ;;
      *)
        exit 0
        ;;
    esac
    ;;
  *)
    echo "unsupported fake adb command: $*" >&2
    exit 1
    ;;
esac
SH
chmod +x "$fake_adb"

ADB="$fake_adb" \
  RIPDPI_IGNORE_DIRTY_WORKTREE_FOR_READINESS=true \
  RIPDPI_REMOTE_COMPARE_REF=HEAD \
  "$repo_root/test-lab/scripts/check-feature-gap-readiness.sh" \
  --output "$multi_device_json" >/dev/null
"$python_bin" "$validator" "$multi_device_json" "$tmpdir/signoff-required-readiness.txt"

"$python_bin" - "$multi_device_json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)

checks = {check["name"]: check for check in data.get("checks", [])}
android = checks["android_device"]
if android.get("status") != "ready":
    raise SystemExit(f"expected auto-selected physical device to be ready, got {android!r}")
if "Connected physical device: Pixel 8 Pro" not in android.get("message", ""):
    raise SystemExit(f"physical device selection message is unclear: {android.get('message', '')!r}")
handover = checks["physical_network_handover"]
if handover.get("status") != "manual":
    raise SystemExit(f"expected physical handover to require manual evidence, got {handover!r}")
rooted = checks["rooted_physical_device"]
if rooted.get("status") != "blocked" or "did not provide root" not in rooted.get("message", ""):
    raise SystemExit(f"expected non-rooted physical device blocker, got {rooted!r}")
PY

ADB="$fake_adb" \
  RIPDPI_FAKE_CELLULAR_SERVICE=out_of_service \
  RIPDPI_IGNORE_DIRTY_WORKTREE_FOR_READINESS=true \
  RIPDPI_REMOTE_COMPARE_REF=HEAD \
  "$repo_root/test-lab/scripts/check-feature-gap-readiness.sh" \
  --output "$cellular_out_of_service_json" >/dev/null
"$python_bin" "$validator" "$cellular_out_of_service_json" "$tmpdir/signoff-required-readiness.txt"

"$python_bin" - "$cellular_out_of_service_json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)

checks = {check["name"]: check for check in data.get("checks", [])}
handover = checks["physical_network_handover"]
if handover.get("status") != "blocked":
    raise SystemExit(f"expected out-of-service cellular state to block handover, got {handover!r}")
message = handover.get("message", "")
if "cellular data service is out of service" not in message:
    raise SystemExit(f"handover blocker lost cellular service context: {message!r}")
PY

cat > "$fake_docker" <<'SH'
#!/usr/bin/env bash
set -euo pipefail

case "${1:-}" in
  inspect)
    if [[ "${2:-}" != "-f" || "${4:-}" != "ripdpi-linux-netem" ]]; then
      echo "unsupported fake docker inspect: $*" >&2
      exit 1
    fi
    echo "true"
    ;;
  exec)
    if [[ "${2:-}" != "ripdpi-linux-netem" ]]; then
      echo "unsupported fake docker exec target: $*" >&2
      exit 1
    fi
    exit 0
    ;;
  *)
    echo "unsupported fake docker command: $*" >&2
    exit 1
    ;;
esac
SH
chmod +x "$fake_docker"

PATH="$tmpdir:$PATH" \
  RIPDPI_IGNORE_DIRTY_WORKTREE_FOR_READINESS=true \
  RIPDPI_REMOTE_COMPARE_REF=HEAD \
  "$repo_root/test-lab/scripts/check-feature-gap-readiness.sh" \
  --output "$netem_container_json" >/dev/null
"$python_bin" "$validator" "$netem_container_json" "$tmpdir/signoff-required-readiness.txt"

"$python_bin" - "$netem_container_json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)

checks = {check["name"]: check for check in data.get("checks", [])}
netem = checks["routed_netem_vm"]
if netem.get("status") != "manual":
    raise SystemExit(f"expected running netem container to require manual route proof, got {netem!r}")
message = netem.get("message", "")
if "Docker netem container 'ripdpi-linux-netem' is running and has tc" not in message:
    raise SystemExit(f"netem container message lost capability context: {message!r}")
if "routed through this container" not in message:
    raise SystemExit(f"netem container message lost route-proof requirement: {message!r}")
PY

RIPDPI_RELAY_MATRIX_CONFIG="$repo_root/test-lab/relay/provider-matrix.example.json" \
  "$repo_root/test-lab/scripts/check-feature-gap-readiness.sh" \
  --output "$relay_json" >/dev/null
"$python_bin" "$validator" "$relay_json" "$tmpdir/signoff-required-readiness.txt"

"$python_bin" - "$relay_json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)

checks = {check["name"]: check for check in data.get("checks", [])}
relay = checks.get("production_relay_matrix")
if relay is None:
    raise SystemExit("missing production_relay_matrix readiness check")
if relay.get("status") != "manual":
    raise SystemExit(
        "expected valid relay matrix to produce manual status, "
        f"got {relay.get('status')!r}: {relay.get('message', '')}"
    )
PY

echo "Feature gap readiness self-test passed."
