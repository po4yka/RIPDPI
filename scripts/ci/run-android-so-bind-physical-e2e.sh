#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/ci/android-so-bind-physical-lib.sh
source "$script_dir/android-so-bind-physical-lib.sh"

readonly test_class="com.poyka.ripdpi.e2e.NetworkPathE2ETest"
readonly test_method="vpnServiceDeniesExcludedTestUidBoundToTun0"
readonly test_selector="$test_class#$test_method"
readonly evidence_profile="physical_pixel_api37_kernel61"
readonly instrumentation_timeout_seconds="${RIPDPI_SO_BIND_INSTRUMENTATION_TIMEOUT_SECONDS:-180}"
readonly adb_bin="${ADB_BIN:-adb}"
readonly android_serial="${ANDROID_SERIAL:-}"
readonly fixture_host="${RIPDPI_FIXTURE_ANDROID_HOST:-}"
readonly fixture_port="${RIPDPI_FIXTURE_CONTROL_PORT:-}"
readonly app_apk="${RIPDPI_APP_APK:-}"
readonly test_apk="${RIPDPI_TEST_APK:-}"

fail() {
    echo "SO_BIND physical E2E: $1" >&2
    exit 1
}

[[ -n "$android_serial" ]] || fail "ANDROID_SERIAL is required"
[[ -f "$app_apk" ]] || fail "RIPDPI_APP_APK must name a regular file"
[[ -f "$test_apk" ]] || fail "RIPDPI_TEST_APK must name a regular file"
so_bind_physical_valid_fixture_host "$fixture_host" || fail "a directly routed, non-loopback RIPDPI_FIXTURE_ANDROID_HOST is required"
so_bind_physical_valid_port "$fixture_port" || fail "RIPDPI_FIXTURE_CONTROL_PORT must be in 1..65535"
so_bind_physical_valid_port "$instrumentation_timeout_seconds" || fail "instrumentation timeout must be in 1..65535 seconds"
command -v "$adb_bin" >/dev/null 2>&1 || fail "adb is unavailable"

adb_device() {
    "$adb_bin" -s "$android_serial" "$@"
}

temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/ripdpi-so-bind-physical.XXXXXX")"
cleanup() {
    rm -rf "$temp_dir"
}
trap cleanup EXIT

install_and_verify_apk() {
    local apk="$1"
    local package_name="$2"
    local label="$3"
    local install_output="$temp_dir/${label}-install.txt"
    local package_paths="$temp_dir/${label}-paths.txt"
    local pull_output="$temp_dir/${label}-pull.txt"
    local pulled_apk="$temp_dir/${label}.apk"
    local path_count remote_path

    adb_device install -r -d "$apk" >"$install_output" 2>&1 || fail "$label APK install failed"
    [[ "$(grep -Fxc 'Success' "$install_output" || true)" == "1" ]] || fail "$label APK install was not confirmed"
    adb_device shell pm path "$package_name" 2>/dev/null | tr -d '\r' >"$package_paths" ||
        fail "$label package path lookup failed"
    path_count="$(grep -Ec '^package:/[^[:space:]]+$' "$package_paths" || true)"
    [[ "$path_count" == "1" && "$(wc -l <"$package_paths" | tr -d ' ')" == "1" ]] ||
        fail "$label package must resolve to exactly one installed APK"
    remote_path="$(sed 's/^package://' "$package_paths")"
    adb_device pull "$remote_path" "$pulled_apk" >"$pull_output" 2>&1 || fail "$label APK readback failed"
    cmp -s "$apk" "$pulled_apk" || fail "$label APK readback does not match the supplied artifact"
}

[[ "$(adb_device get-state 2>/dev/null | tr -d '\r')" == "device" ]] || fail "selected Android device is not ready"

qemu="$(adb_device shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r')"
boot_qemu="$(adb_device shell getprop ro.boot.qemu 2>/dev/null | tr -d '\r')"
hardware="$(adb_device shell getprop ro.hardware 2>/dev/null | tr -d '\r' | tr '[:upper:]' '[:lower:]')"
[[ "$qemu" != "1" && "$boot_qemu" != "1" && "$hardware" != *ranchu* && "$hardware" != *goldfish* ]] ||
    fail "selected target is an emulator"

api_level="$(adb_device shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
[[ "$api_level" == "37" ]] || fail "physical evidence requires Android API 37"
kernel_release="$(adb_device shell uname -r 2>/dev/null | tr -d '\r')"
[[ "$kernel_release" == 6.1.* ]] || fail "physical evidence requires the qualified 6.1 kernel family"

install_and_verify_apk "$app_apk" "com.poyka.ripdpi" "app"
install_and_verify_apk "$test_apk" "com.poyka.ripdpi.test" "test"

adb_device shell toybox nc -z -w 5 "$fixture_host" "$fixture_port" >/dev/null 2>&1 ||
    fail "fixture control port is not directly reachable from the physical device"

instrumentation_components="$(
    adb_device shell pm list instrumentation 2>/dev/null | tr -d '\r' | awk '
        /target=com\.poyka\.ripdpi/ && $0 !~ /baselineprofile/ {
            sub(/^instrumentation:/, "", $0)
            sub(/ .*/, "", $0)
            print
        }
    '
)"
instrumentation_component_count="$(printf '%s\n' "$instrumentation_components" | sed '/^$/d' | wc -l | tr -d ' ')"
[[ "$instrumentation_component_count" == "1" ]] || fail "expected exactly one RIPDPI instrumentation component"
instrumentation_component="$instrumentation_components"
[[ "$instrumentation_component" =~ ^[A-Za-z0-9._]+/[A-Za-z0-9._]+$ ]] || fail "instrumentation component is malformed"

output_file="$temp_dir/instrumentation.txt"

set +e
adb_device shell timeout "$instrumentation_timeout_seconds" am instrument -w -r \
    -e class "$test_selector" \
    -e ripdpi.fixtureControlHost "$fixture_host" \
    -e ripdpi.fixtureControlPort "$fixture_port" \
    -e ripdpi.soBindEvidenceProfile "$evidence_profile" \
    "$instrumentation_component" >"$output_file" 2>&1
instrumentation_status=$?
set -e

[[ "$instrumentation_status" == "0" ]] || fail "instrumentation command failed"
so_bind_physical_output_is_exact_pass "$output_file" "$test_class" "$test_method" ||
    fail "instrumentation output was skipped, failed, incomplete, or ambiguous"

echo "SO_BIND physical E2E passed: one exact physical instrumentation test"
