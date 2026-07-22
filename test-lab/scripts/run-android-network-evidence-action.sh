#!/usr/bin/env bash
set -euo pipefail

readonly gate_id="killswitch-tun-establish-native-ready"
readonly gate_kind="direct_window"
readonly test_class="com.poyka.ripdpi.e2e.VpnStartupWindowE2ETest"
readonly test_method="vpnStartupWindowHoldsDnsPacketUntilNativeReady"
readonly test_selector="$test_class#$test_method"
readonly receipt_file="network-evidence-action-receipt.json"
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
  --fixture-identity-sha256 SHA256 --receipt-output ABSOLUTE_PATH

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
while [[ $# -gt 0 ]]; do
    case "$1" in
        --gate-id) requested_gate="${2:-}"; shift 2 ;;
        --correlation-id) correlation_id="${2:-}"; shift 2 ;;
        --source-sha) source_sha="${2:-}"; shift 2 ;;
        --client-artifact-sha256) client_artifact_sha256="${2:-}"; shift 2 ;;
        --test-artifact-sha256) test_artifact_sha256="${2:-}"; shift 2 ;;
        --fixture-identity-sha256) fixture_identity_sha256="${2:-}"; shift 2 ;;
        --receipt-output) receipt_output="${2:-}"; shift 2 ;;
        -h|--help) usage ;;
        *) usage ;;
    esac
done

[[ "$requested_gate" == "$gate_id" ]] || fail "unsupported or missing gate ID"
[[ "$correlation_id" =~ ^[0-9a-f]{64}$ ]] || fail "correlation ID is malformed"
[[ "$source_sha" =~ ^[0-9a-f]{40}$ ]] || fail "source SHA is malformed"
[[ "$client_artifact_sha256" =~ ^[0-9a-f]{64}$ ]] || fail "client artifact digest is malformed"
[[ "$test_artifact_sha256" =~ ^[0-9a-f]{64}$ ]] || fail "androidTest artifact digest is malformed"
[[ "$client_artifact_sha256" != "$test_artifact_sha256" ]] || fail "client and androidTest artifacts must differ"
[[ "$fixture_identity_sha256" =~ ^[0-9a-f]{64}$ ]] || fail "fixture identity digest is malformed"
[[ "$receipt_output" == /* ]] || fail "receipt output path must be absolute"
[[ ! -e "$receipt_output" && ! -L "$receipt_output" ]] ||
    fail "receipt output must not already exist"
[[ -n "$android_serial" ]] || fail "ANDROID_SERIAL is required"
if [[ ! "$fixture_port" =~ ^[0-9]+$ ]] || ((fixture_port < 1 || fixture_port > 65535)); then
    fail "RIPDPI_FIXTURE_CONTROL_PORT must be in 1..65535"
fi
if [[ ! "$instrumentation_timeout_seconds" =~ ^[1-9][0-9]*$ ]] ||
    ((instrumentation_timeout_seconds > 600)); then
    fail "instrumentation timeout must be in 1..600 seconds"
fi
command -v python3 >/dev/null 2>&1 || fail "python3 is unavailable"

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

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source_root="$($git_bin -C "$script_dir/../.." rev-parse --show-toplevel 2>/dev/null)" ||
    fail "could not resolve the source checkout"
[[ "$source_root" == "$(cd "$script_dir/../.." && pwd)" ]] || fail "runner must execute from its source checkout"
[[ "$($git_bin -C "$source_root" rev-parse HEAD 2>/dev/null)" == "$source_sha" ]] ||
    fail "runner checkout does not match the attributed source SHA"
[[ -z "$($git_bin -C "$source_root" status --porcelain=v1 --untracked-files=all)" ]] ||
    fail "network action evidence requires a clean source checkout"
validator="$source_root/scripts/ci/check_android_network_action_receipt.py"
[[ -f "$validator" ]] || fail "receipt validator is unavailable"

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

temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/ripdpi-android-network-action.XXXXXX")"
chmod 0700 "$temp_dir"
device_lock_acquired=0
device_lock_serial="$(printf '%s' "$android_serial" | tr -c 'A-Za-z0-9._-' '_')"
device_lock_dir="$device_lock_root/ripdpi-android-device-$device_lock_serial.lock"
cleanup() {
    adb_device shell run-as com.poyka.ripdpi rm -f "files/$receipt_file" "files/$receipt_file.tmp" >/dev/null 2>&1 || true
    if [[ "$device_lock_acquired" == "1" ]]; then
        rm -rf "$device_lock_dir"
    fi
    rm -rf "$temp_dir"
}
trap cleanup EXIT
mkdir -p "$device_lock_root" || fail "could not prepare the Android device lock directory"
mkdir "$device_lock_dir" 2>/dev/null || fail "physical Android device lane is already in use for $android_serial"
device_lock_acquired=1
printf '%s\n' "$$" >"$device_lock_dir/owner-pid"

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
if adb_device shell run-as com.poyka.ripdpi test -e "files/$receipt_file"; then
    fail "stale action receipt remained before instrumentation"
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
python3 "$validator" "$private_receipt" \
    --source-sha "$source_sha" \
    --correlation-id "$correlation_id" \
    --client-artifact-sha256 "$client_artifact_sha256" \
    --test-artifact-sha256 "$test_artifact_sha256" \
    --fixture-identity-sha256 "$fixture_identity_sha256" >"$temp_dir/receipt-sha256.txt" ||
    fail "action receipt was missing, partial, or malformed"

adb_device shell run-as com.poyka.ripdpi rm -f "files/$receipt_file" "files/$receipt_file.tmp" >/dev/null 2>&1 ||
    fail "could not delete device action receipt"
if adb_device shell run-as com.poyka.ripdpi test -e "files/$receipt_file"; then
    fail "device action receipt remained after deletion"
fi
python3 - "$private_receipt" "$receipt_output" <<'PY' || fail "could not publish private receipt output"
import os
import shutil
import stat
import sys
from pathlib import Path

source = Path(sys.argv[1])
destination = Path(sys.argv[2])
flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC
if hasattr(os, "O_NOFOLLOW"):
    flags |= os.O_NOFOLLOW
descriptor = os.open(destination, flags, 0o600)
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
    raise SystemExit(1)
PY
echo "Android network evidence action passed: $gate_id"
