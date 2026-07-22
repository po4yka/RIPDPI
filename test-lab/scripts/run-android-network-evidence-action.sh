#!/usr/bin/env bash
set -euo pipefail

internal_locked_body=0
if [[ "${1:-}" == "--ripdpi-internal-locked-body" ]]; then
    internal_locked_body=1
    shift
fi
readonly original_args=("$@")

readonly adb_bin="${ADB_BIN:-adb}"
readonly git_bin="${GIT_BIN:-git}"
readonly android_serial="${ANDROID_SERIAL:-}"
readonly fixture_host="${RIPDPI_FIXTURE_ANDROID_HOST:-}"
readonly fixture_port="${RIPDPI_FIXTURE_CONTROL_PORT:-}"
readonly instrumentation_timeout_seconds="${RIPDPI_NETWORK_ACTION_TIMEOUT_SECONDS:-180}"
readonly device_lock_root="${RIPDPI_ANDROID_DEVICE_LOCK_ROOT:-${TMPDIR:-/tmp}}"

fail() {
    echo "Android network evidence action: $1" >&2
    exit 1
}

usage() {
    cat >&2 <<'EOF'
Usage: run-android-network-evidence-action.sh \
  --gate-id ID --correlation-id SHA256 --source-sha SHA1 \
  --client-artifact-sha256 SHA256 --test-artifact-sha256 SHA256 \
  --fixture-identity-sha256 SHA256 --receipt-output ABSOLUTE_PATH \
  [--fixture-transcript-output ABSOLUTE_PATH] \
  [--test-only-action-registry-override ABSOLUTE_PATH]

This command starts and stops the RIPDPI VPN. It never changes Wi-Fi, mobile,
routes, DNS, Private DNS, or airplane mode, but requires explicit operator
authorization for the existing device/network state before execution.
EOF
    exit 2
}

requested_gate=""
correlation_id=""
source_sha=""
client_artifact_sha256=""
test_artifact_sha256=""
fixture_identity_sha256=""
receipt_output=""
fixture_transcript_output=""
test_only_action_registry_override=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --gate-id) requested_gate="${2:-}"; shift 2 ;;
        --correlation-id) correlation_id="${2:-}"; shift 2 ;;
        --source-sha) source_sha="${2:-}"; shift 2 ;;
        --client-artifact-sha256) client_artifact_sha256="${2:-}"; shift 2 ;;
        --test-artifact-sha256) test_artifact_sha256="${2:-}"; shift 2 ;;
        --fixture-identity-sha256) fixture_identity_sha256="${2:-}"; shift 2 ;;
        --receipt-output) receipt_output="${2:-}"; shift 2 ;;
        --fixture-transcript-output) fixture_transcript_output="${2:-}"; shift 2 ;;
        --test-only-action-registry-override) test_only_action_registry_override="${2:-}"; shift 2 ;;
        -h|--help) usage ;;
        *) usage ;;
    esac
done

[[ "$requested_gate" =~ ^[a-z0-9][a-z0-9_-]{0,127}$ ]] || fail "unsupported or missing gate ID"
[[ "$correlation_id" =~ ^[0-9a-f]{64}$ ]] || fail "correlation ID is malformed"
[[ "$source_sha" =~ ^[0-9a-f]{40}$ ]] || fail "source SHA is malformed"
[[ "$client_artifact_sha256" =~ ^[0-9a-f]{64}$ ]] || fail "client artifact digest is malformed"
[[ "$test_artifact_sha256" =~ ^[0-9a-f]{64}$ ]] || fail "androidTest artifact digest is malformed"
[[ "$client_artifact_sha256" != "$test_artifact_sha256" ]] || fail "client and androidTest artifacts must differ"
[[ "$fixture_identity_sha256" =~ ^[0-9a-f]{64}$ ]] || fail "fixture identity digest is malformed"
[[ "$receipt_output" == /* ]] || fail "receipt output path must be absolute"
[[ ! -e "$receipt_output" && ! -L "$receipt_output" ]] ||
    fail "receipt output must not already exist"
if [[ -n "$fixture_transcript_output" ]]; then
    [[ "$fixture_transcript_output" == /* ]] || fail "fixture transcript output path must be absolute"
    [[ ! -e "$fixture_transcript_output" && ! -L "$fixture_transcript_output" ]] ||
        fail "fixture transcript output must not already exist"
fi
if [[ -n "$fixture_transcript_output" ]]; then
    if ! python3 - "$receipt_output" "$fixture_transcript_output" <<'PY'
from pathlib import Path
import sys

receipt, transcript = (Path(value) for value in sys.argv[1:])
if receipt.resolve(strict=False) == transcript.resolve(strict=False):
    raise SystemExit(1)
PY
    then
        fail "receipt and fixture transcript outputs must be distinct"
    fi
fi
if [[ -n "$test_only_action_registry_override" ]]; then
    [[ "$test_only_action_registry_override" == /* ]] ||
        fail "test-only action registry override path must be absolute"
    [[ -f "$test_only_action_registry_override" && ! -L "$test_only_action_registry_override" ]] ||
        fail "test-only action registry override must be a regular file"
fi
[[ -n "$android_serial" ]] || fail "ANDROID_SERIAL is required"
if [[ ! "$fixture_port" =~ ^[0-9]+$ ]] || ((fixture_port < 1 || fixture_port > 65535)); then
    fail "RIPDPI_FIXTURE_CONTROL_PORT must be in 1..65535"
fi
if [[ ! "$instrumentation_timeout_seconds" =~ ^[1-9][0-9]*$ ]] ||
    ((instrumentation_timeout_seconds > 600)); then
    fail "instrumentation timeout must be in 1..600 seconds"
fi
command -v python3 >/dev/null 2>&1 || fail "python3 is unavailable"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source_root="$(cd "$script_dir/../.." && pwd)"
validator="$source_root/scripts/ci/check_android_network_action_receipt.py"
[[ -f "$validator" ]] || fail "receipt validator is unavailable"
descriptor="$({
    python3 - "$validator" "$requested_gate" "$test_only_action_registry_override" <<'PY'
import importlib.util
from pathlib import Path
import sys

path, gate_id, override = sys.argv[1:]
spec = importlib.util.spec_from_file_location("network_action_receipt", path)
if spec is None or spec.loader is None:
    raise SystemExit(1)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
descriptor = module.load_action_registry(
    test_only_ready_override=Path(override) if override else None
).get(gate_id)
if descriptor is None:
    raise SystemExit(1)
print("\t".join((
    descriptor.kind,
    descriptor.selector,
    descriptor.receipt_file,
    descriptor.semantic_rule,
    "true" if descriptor.production_ready else "false",
)))
PY
} 2>/dev/null)" || fail "unsupported gate ID or invalid action registry"
IFS=$'\t' read -r gate_kind test_selector receipt_file semantic_rule production_ready <<<"$descriptor"
[[ -n "$gate_kind" && -n "$test_selector" && -n "$receipt_file" && -n "$semantic_rule" && -n "$production_ready" ]] ||
    fail "action descriptor is incomplete"
[[ "$production_ready" == "true" ]] || fail "action descriptor is not production ready: $requested_gate"
[[ "$test_selector" == *#* && "$test_selector" != *#*#* ]] || fail "action selector is malformed"
test_class="${test_selector%%#*}"
test_method="${test_selector#*#}"
readonly gate_id="$requested_gate" gate_kind test_selector receipt_file semantic_rule test_class test_method
readonly fixture_transcript_file="network-evidence-fixture-transcript-$gate_id.json"
case "$gate_id" in
    dns-virtual-vpn-resolver|dns-proxied-through-tunnelled-resolver|dns-no-isp-fallback-on-encrypted-resolver-outage)
        [[ -n "$fixture_transcript_output" ]] || fail "fixture transcript output is required for $gate_id"
        readonly requires_fixture_transcript=1
        ;;
    *) readonly requires_fixture_transcript=0 ;;
esac

python3 - "$fixture_host" <<'PY' || fail "RIPDPI_FIXTURE_ANDROID_HOST must be a numeric routed unicast address"
import ipaddress
import sys

try:
    address = ipaddress.ip_address(sys.argv[1])
except ValueError:
    raise SystemExit(1)
if address.is_unspecified or address.is_loopback or address.is_link_local or address.is_multicast:
    raise SystemExit(1)
PY
command -v "$adb_bin" >/dev/null 2>&1 || fail "adb is unavailable"
command -v "$git_bin" >/dev/null 2>&1 || fail "git is unavailable"

git_source_root="$($git_bin -C "$script_dir/../.." rev-parse --show-toplevel 2>/dev/null)" ||
    fail "could not resolve the source checkout"
[[ "$git_source_root" == "$source_root" ]] || fail "runner must execute from its source checkout"
[[ "$($git_bin -C "$source_root" rev-parse HEAD 2>/dev/null)" == "$source_sha" ]] ||
    fail "runner checkout does not match the attributed source SHA"
[[ -z "$($git_bin -C "$source_root" status --porcelain=v1 --untracked-files=all)" ]] ||
    fail "network action evidence requires a clean source checkout"
markers="$(
    python3 - "$correlation_id" "$gate_id" "$gate_kind" <<'PY'
import hashlib
import sys

correlation, gate, kind = sys.argv[1:]
for phase in ("action", "outcome"):
    value = f"ripdpi:network-evidence-marker:v2:{correlation}:{gate}:{kind}:{phase}"
    print(hashlib.sha256(value.encode("ascii")).hexdigest())
PY
)"
[[ "$(printf '%s\n' "$markers" | wc -l | tr -d ' ')" == "2" ]] || fail "could not derive both markers"
action_marker_sha256="$(printf '%s\n' "$markers" | sed -n '1p')"
outcome_marker_sha256="$(printf '%s\n' "$markers" | sed -n '2p')"
readonly action_marker_sha256 outcome_marker_sha256
[[ "$action_marker_sha256" =~ ^[0-9a-f]{64}$ && "$outcome_marker_sha256" =~ ^[0-9a-f]{64}$ ]] ||
    fail "derived markers are malformed"
[[ "$action_marker_sha256" != "$outcome_marker_sha256" ]] || fail "derived markers are not unique"

adb_device() {
    "$adb_bin" -s "$android_serial" "$@"
}

single_installed_apk_path() {
    local package_name="$1"
    local output
    output="$(adb_device shell pm path "$package_name" 2>/dev/null | tr -d '\r')" ||
        fail "could not locate installed package $package_name"
    [[ "$(printf '%s\n' "$output" | sed -n '/^package:/p' | wc -l | tr -d ' ')" == "1" ]] ||
        fail "expected exactly one installed APK path for $package_name"
    local apk_path
    apk_path="$(printf '%s\n' "$output" | sed -n 's/^package://p')"
    [[ "$apk_path" == /* && "$apk_path" == *.apk ]] || fail "installed APK path is malformed for $package_name"
    printf '%s\n' "$apk_path"
}

read_installed_apk_sha256() {
    local package_name="$1"
    local output_file="$2"
    local apk_path
    apk_path="$(single_installed_apk_path "$package_name")"
    adb_device pull "$apk_path" "$output_file" >/dev/null ||
        fail "could not read back installed APK for $package_name"
    python3 - "$output_file" <<'PY'
import hashlib
import pathlib
import sys

print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())
PY
}

instrumentation_output_is_exact_pass() {
    local output_file="$1"
    [[ "$(grep -Fxc 'INSTRUMENTATION_STATUS: numtests=1' "$output_file" || true)" == "2" ]] &&
        [[ "$(grep -Fxc "INSTRUMENTATION_STATUS: class=$test_class" "$output_file" || true)" == "2" ]] &&
        [[ "$(grep -Fxc "INSTRUMENTATION_STATUS: test=$test_method" "$output_file" || true)" == "2" ]] &&
        [[ "$(grep -Fxc 'INSTRUMENTATION_STATUS_CODE: 1' "$output_file" || true)" == "1" ]] &&
        [[ "$(grep -Fxc 'INSTRUMENTATION_STATUS_CODE: 0' "$output_file" || true)" == "1" ]] &&
        [[ "$(grep -Fxc 'OK (1 test)' "$output_file" || true)" == "1" ]] &&
        [[ "$(grep -Fxc 'INSTRUMENTATION_CODE: -1' "$output_file" || true)" == "1" ]] &&
        [[ "$(grep -Ec '^INSTRUMENTATION_STATUS_CODE: -?[0-9]+$' "$output_file" || true)" == "2" ]] &&
        ! grep -Eqi '(^FAILURES!!!$|INSTRUMENTATION_STATUS_CODE: -|INSTRUMENTATION_RESULT: shortMsg=|Process crashed|assumption|ignored|skipped|0 tests|OK \(0 test)' "$output_file"
}

device_lock_serial="$(printf '%s' "$android_serial" | tr -c 'A-Za-z0-9._-' '_')"
device_lock_name="ripdpi-android-device-$device_lock_serial.lock"
mkdir -p "$device_lock_root" || fail "could not prepare the Android device lock directory"
device_lock_root_canonical="$(cd "$device_lock_root" && pwd -P)"
if [[ "$internal_locked_body" == "0" ]]; then
    exec python3 "$source_root/test-lab/scripts/run-with-android-device-lock.py" run \
        --lock-root "$device_lock_root_canonical" \
        --lock-name "$device_lock_name" \
        bash "$0" --ripdpi-internal-locked-body "${original_args[@]}"
fi
python3 "$source_root/test-lab/scripts/run-with-android-device-lock.py" verify \
    --lock-root "$device_lock_root_canonical" \
    --lock-name "$device_lock_name" \
    --supervisor-pid "${RIPDPI_ANDROID_DEVICE_LOCK_SUPERVISOR_PID:-0}" \
    --nonce "${RIPDPI_ANDROID_DEVICE_LOCK_AUTH:-}" ||
    fail "physical Android device lane lock authentication failed"
unset RIPDPI_ANDROID_DEVICE_LOCK_AUTH RIPDPI_ANDROID_DEVICE_LOCK_SUPERVISOR_PID
temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/ripdpi-android-network-action.XXXXXX")"
chmod 0700 "$temp_dir"
cleanup() {
    adb_device shell run-as com.poyka.ripdpi rm -f \
        "files/$receipt_file" "files/$receipt_file.tmp" \
        "files/$fixture_transcript_file" "files/$fixture_transcript_file.tmp" >/dev/null 2>&1 || true
    rm -rf "$temp_dir"
}
trap cleanup EXIT

[[ "$(adb_device get-state 2>/dev/null | tr -d '\r')" == "device" ]] || fail "selected Android device is not ready"
qemu="$(adb_device shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r')"
boot_qemu="$(adb_device shell getprop ro.boot.qemu 2>/dev/null | tr -d '\r')"
hardware="$(adb_device shell getprop ro.hardware 2>/dev/null | tr -d '\r' | tr '[:upper:]' '[:lower:]')"
[[ "$qemu" != "1" && "$boot_qemu" != "1" && "$hardware" != *ranchu* && "$hardware" != *goldfish* ]] ||
    fail "network action evidence requires a physical Android device"
pre_client_apk="$temp_dir/client-before.apk"
pre_test_apk="$temp_dir/test-before.apk"
pre_client_sha256="$(read_installed_apk_sha256 com.poyka.ripdpi "$pre_client_apk")"
pre_test_sha256="$(read_installed_apk_sha256 com.poyka.ripdpi.test "$pre_test_apk")"
[[ "$pre_client_sha256" == "$client_artifact_sha256" ]] ||
    fail "installed client APK digest does not match requested provenance"
[[ "$pre_test_sha256" == "$test_artifact_sha256" ]] ||
    fail "installed androidTest APK digest does not match requested provenance"
components="$(
    adb_device shell pm list instrumentation 2>/dev/null | tr -d '\r' | awk '
        /\(target=com\.poyka\.ripdpi\)$/ && $0 !~ /baselineprofile/ {
            sub(/^instrumentation:/, "", $0)
            sub(/ .*/, "", $0)
            print
        }
    '
)"
[[ "$(printf '%s\n' "$components" | sed '/^$/d' | wc -l | tr -d ' ')" == "1" ]] ||
    fail "expected exactly one RIPDPI instrumentation component"
[[ "$components" =~ ^[A-Za-z0-9._]+/[A-Za-z0-9._]+$ ]] || fail "instrumentation component is malformed"

adb_device shell run-as com.poyka.ripdpi rm -f "files/$receipt_file" "files/$receipt_file.tmp" >/dev/null 2>&1 ||
    fail "could not clear prior action receipt"
if [[ "$requires_fixture_transcript" == "1" ]]; then
    adb_device shell run-as com.poyka.ripdpi rm -f \
        "files/$fixture_transcript_file" "files/$fixture_transcript_file.tmp" >/dev/null 2>&1 ||
        fail "could not clear prior fixture transcript"
fi
if adb_device shell run-as com.poyka.ripdpi test -e "files/$receipt_file"; then
    fail "stale action receipt remained before instrumentation"
fi
if [[ "$requires_fixture_transcript" == "1" ]] &&
    adb_device shell run-as com.poyka.ripdpi test -e "files/$fixture_transcript_file"; then
    fail "stale fixture transcript remained before instrumentation"
fi

output_file="$temp_dir/instrumentation.txt"
set +e
adb_device shell timeout "$instrumentation_timeout_seconds" am instrument -w -r \
    -e class "$test_selector" \
    -e ripdpi.fixtureControlHost "$fixture_host" \
    -e ripdpi.fixtureControlPort "$fixture_port" \
    -e ripdpi.networkEvidenceCorrelationId "$correlation_id" \
    -e ripdpi.networkEvidenceGateId "$gate_id" \
    -e ripdpi.networkEvidenceKind "$gate_kind" \
    -e ripdpi.networkEvidenceSelector "$test_selector" \
    -e ripdpi.networkEvidenceSemanticRule "$semantic_rule" \
    -e ripdpi.networkEvidenceReceiptFile "$receipt_file" \
    -e ripdpi.networkEvidenceSourceSha "$source_sha" \
    -e ripdpi.networkEvidenceClientArtifactSha256 "$client_artifact_sha256" \
    -e ripdpi.networkEvidenceTestArtifactSha256 "$test_artifact_sha256" \
    -e ripdpi.networkEvidenceFixtureIdentitySha256 "$fixture_identity_sha256" \
    -e ripdpi.networkEvidenceActionMarkerSha256 "$action_marker_sha256" \
    -e ripdpi.networkEvidenceOutcomeMarkerSha256 "$outcome_marker_sha256" \
    "$components" >"$output_file" 2>&1
instrumentation_status=$?
set -e
[[ "$instrumentation_status" == "0" ]] || fail "instrumentation command failed"
if ! instrumentation_output_is_exact_pass "$output_file"; then
    sed 's/^/Android action instrumentation: /' "$output_file" >&2
    fail "instrumentation output was skipped, failed, incomplete, or ambiguous"
fi
post_client_sha256="$(read_installed_apk_sha256 com.poyka.ripdpi "$temp_dir/client-after.apk")"
post_test_sha256="$(read_installed_apk_sha256 com.poyka.ripdpi.test "$temp_dir/test-after.apk")"
[[ "$post_client_sha256" == "$pre_client_sha256" ]] ||
    fail "installed client APK changed during instrumentation"
[[ "$post_test_sha256" == "$pre_test_sha256" ]] ||
    fail "installed androidTest APK changed during instrumentation"

device_mode="$(adb_device shell run-as com.poyka.ripdpi stat -c %a "files/$receipt_file" 2>/dev/null | tr -d '\r')" ||
    fail "action receipt mode lookup failed"
[[ "$device_mode" == "600" ]] || fail "action receipt mode must be 600 on device"
private_receipt="$temp_dir/receipt.json"
adb_device shell run-as com.poyka.ripdpi cat "files/$receipt_file" >"$private_receipt" 2>/dev/null ||
    fail "action receipt readback failed"
chmod 0600 "$private_receipt"
validator_args=(
    "$validator" "$private_receipt"
    --gate-id "$gate_id"
    --source-sha "$source_sha"
    --correlation-id "$correlation_id"
    --client-artifact-sha256 "$client_artifact_sha256"
    --test-artifact-sha256 "$test_artifact_sha256"
    --fixture-identity-sha256 "$fixture_identity_sha256"
)
if [[ -n "$test_only_action_registry_override" ]]; then
    validator_args+=(--test-only-ready-override "$test_only_action_registry_override")
fi
python3 "${validator_args[@]}" >"$temp_dir/receipt-sha256.txt" ||
    fail "action receipt was missing, partial, or malformed"

private_transcript=""
if [[ "$requires_fixture_transcript" == "1" ]]; then
    transcript_mode="$(adb_device shell run-as com.poyka.ripdpi stat -c %a "files/$fixture_transcript_file" 2>/dev/null | tr -d '\r')" ||
        fail "fixture transcript mode lookup failed"
    [[ "$transcript_mode" == "600" ]] || fail "fixture transcript mode must be 600 on device"
    private_transcript="$temp_dir/fixture-transcript.json"
    adb_device shell run-as com.poyka.ripdpi cat "files/$fixture_transcript_file" >"$private_transcript" 2>/dev/null ||
        fail "fixture transcript readback failed"
    chmod 0600 "$private_transcript"
    python3 - "$private_receipt" "$private_transcript" <<'PY' || fail "fixture transcript digest does not match the action receipt"
import hashlib
import json
import pathlib
import sys

receipt = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
transcript = pathlib.Path(sys.argv[2]).read_bytes()
if hashlib.sha256(transcript).hexdigest() != receipt["facts"]["fixtureTranscriptSha256"]:
    raise SystemExit(1)
PY
fi

adb_device shell run-as com.poyka.ripdpi rm -f \
    "files/$receipt_file" "files/$receipt_file.tmp" \
    "files/$fixture_transcript_file" "files/$fixture_transcript_file.tmp" >/dev/null 2>&1 ||
    fail "could not delete device action outputs"
if adb_device shell run-as com.poyka.ripdpi test -e "files/$receipt_file"; then
    fail "device action receipt remained after deletion"
fi
if [[ "$requires_fixture_transcript" == "1" ]] &&
    adb_device shell run-as com.poyka.ripdpi test -e "files/$fixture_transcript_file"; then
    fail "device fixture transcript remained after deletion"
fi
python3 - "$private_receipt" "$receipt_output" "$private_transcript" "$fixture_transcript_output" <<'PY' || fail "could not publish private action outputs"
import os
import shutil
import stat
import sys
from pathlib import Path

created: list[tuple[Path, int, int]] = []

def publish(source_name: str, destination_name: str) -> None:
    source = Path(source_name)
    destination = Path(destination_name)
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    descriptor = os.open(destination, flags, 0o600)
    metadata = os.fstat(descriptor)
    created.append((destination, metadata.st_dev, metadata.st_ino))
    try:
        with os.fdopen(descriptor, "wb", closefd=False) as output:
            with source.open("rb") as input_file:
                shutil.copyfileobj(input_file, output)
            output.flush()
            os.fsync(output.fileno())
    finally:
        os.close(descriptor)
    metadata = destination.lstat()
    if not stat.S_ISREG(metadata.st_mode) or stat.S_IMODE(metadata.st_mode) != 0o600:
        raise OSError("published output is not a private regular file")

receipt_destination = Path(sys.argv[2]).resolve(strict=False)
transcript_destination = Path(sys.argv[4]).resolve(strict=False) if sys.argv[3] else None
if transcript_destination is not None and receipt_destination == transcript_destination:
    raise SystemExit(1)

try:
    if sys.argv[3]:
        publish(sys.argv[3], sys.argv[4])
    publish(sys.argv[1], sys.argv[2])
except BaseException:
    for destination, device, inode in reversed(created):
        try:
            metadata = destination.lstat()
            if metadata.st_dev == device and metadata.st_ino == inode:
                destination.unlink()
        except FileNotFoundError:
            pass
    raise
PY
echo "Android network evidence action passed: $gate_id"
