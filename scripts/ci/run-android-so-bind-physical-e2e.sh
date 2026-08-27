#!/usr/bin/env bash
set -euo pipefail

internal_locked_body=0
if [[ "${1:-}" == "--ripdpi-internal-locked-body" ]]; then
    internal_locked_body=1
    shift
fi
readonly original_args=("$@")

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/ci/android-so-bind-physical-lib.sh
source "$script_dir/android-so-bind-physical-lib.sh"

readonly test_class="com.poyka.ripdpi.e2e.NetworkPathE2ETest"
readonly test_method="vpnServiceDeniesExcludedTestUidBoundToTun0"
readonly test_selector="$test_class#$test_method"
readonly evidence_profile="${RIPDPI_SO_BIND_EVIDENCE_PROFILE:-physical_kernel_ge57}"
readonly instrumentation_timeout_seconds="${RIPDPI_SO_BIND_INSTRUMENTATION_TIMEOUT_SECONDS:-180}"
readonly test_probe_allowlist_duration_ms="300000"
readonly adb_bin="${ADB_BIN:-adb}"
readonly curl_bin="${CURL_BIN:-curl}"
readonly git_bin="${GIT_BIN:-git}"
readonly android_serial="${ANDROID_SERIAL:-}"
readonly fixture_host="${RIPDPI_FIXTURE_ANDROID_HOST:-}"
readonly fixture_ipv6_host_raw="${RIPDPI_FIXTURE_ANDROID_IPV6_HOST:-}"
readonly fixture_port="${RIPDPI_FIXTURE_CONTROL_PORT:-}"
readonly fixture_tcp_echo_port="${RIPDPI_FIXTURE_TCP_ECHO_PORT:-}"
readonly fixture_udp_echo_port="${RIPDPI_FIXTURE_UDP_ECHO_PORT:-}"
readonly evidence_output="${RIPDPI_SO_BIND_EVIDENCE_OUTPUT:-}"
readonly evidence_file_name="so-bind-physical-evidence.json"
readonly infra_gap_file_name="so-bind-physical-infra-gap.txt"
readonly device_lock_root="${RIPDPI_ANDROID_DEVICE_LOCK_ROOT:-${TMPDIR:-/tmp}}"

fail() {
    echo "SO_BIND physical E2E: $1" >&2
    exit 1
}

fail_infra() {
    echo "SO_BIND physical E2E: INFRA_GAP $1: $2" >&2
    exit 2
}

[[ -n "$android_serial" ]] || fail "ANDROID_SERIAL is required"
[[ "$evidence_profile" == "physical_kernel_ge57" || "$evidence_profile" == "physical_kernel_lt57" ]] || fail "unknown physical kernel profile"
so_bind_physical_valid_fixture_host "$fixture_host" || fail "a directly routed, non-loopback RIPDPI_FIXTURE_ANDROID_HOST is required"
command -v python3 >/dev/null 2>&1 || fail "python3 is unavailable"
fixture_ipv6_host=""
if [[ "$evidence_profile" == "physical_kernel_ge57" || -n "$fixture_ipv6_host_raw" ]]; then
    fixture_ipv6_host="$(so_bind_physical_normalize_routed_ipv6 "$fixture_ipv6_host_raw")" ||
        fail_infra IPV6_ENDPOINT_REQUIRED "RIPDPI_FIXTURE_ANDROID_IPV6_HOST must be a numeric routed unicast IPv6 address"
fi
readonly fixture_ipv6_host
so_bind_physical_valid_port "$fixture_port" || fail "RIPDPI_FIXTURE_CONTROL_PORT must be in 1..65535"
so_bind_physical_valid_port "$fixture_tcp_echo_port" || fail "RIPDPI_FIXTURE_TCP_ECHO_PORT must be in 1..65535"
so_bind_physical_valid_port "$fixture_udp_echo_port" || fail "RIPDPI_FIXTURE_UDP_ECHO_PORT must be in 1..65535"
so_bind_physical_valid_port "$instrumentation_timeout_seconds" || fail "instrumentation timeout must be in 1..65535 seconds"
command -v "$adb_bin" >/dev/null 2>&1 || fail "adb is unavailable"
command -v "$curl_bin" >/dev/null 2>&1 || fail "curl is unavailable"
command -v "$git_bin" >/dev/null 2>&1 || fail "git is unavailable"

fixture_manifest="$($curl_bin -fsS --max-time 5 "http://$fixture_host:$fixture_port/manifest")" ||
    fail_infra FIXTURE_MANIFEST_UNAVAILABLE "fixture manifest could not be fetched"
if [[ "$evidence_profile" == "physical_kernel_ge57" ]]; then
python3 - "$fixture_manifest" <<'PY' ||
import json
import sys

manifest = json.loads(sys.argv[1])
if manifest.get("icmpIpv4Observer") is not True or manifest.get("icmpIpv6Observer") is not True:
    raise SystemExit(1)
PY
    fail_infra ICMP_OBSERVER_UNAVAILABLE "fixture requires CAP_NET_RAW observers for both IPv4 and IPv6"

fi

source_root="$($git_bin -C "$script_dir/../.." rev-parse --show-toplevel 2>/dev/null)" ||
    fail "could not resolve source checkout"
readonly source_root
[[ "$source_root" == "$(cd "$script_dir/../.." && pwd)" ]] || fail "runner must execute from its source checkout"
mkdir -p "$device_lock_root" || fail "could not prepare the Android device lock directory"
device_lock_root_canonical="$(cd "$device_lock_root" && pwd -P)"
device_lock_serial="$(printf '%s' "$android_serial" | tr -c 'A-Za-z0-9._-' '_')"
device_lock_name="ripdpi-android-device-$device_lock_serial.lock"
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
source_sha="$($git_bin -C "$source_root" rev-parse HEAD 2>/dev/null)" || fail "could not resolve source SHA"
readonly source_sha
[[ "$source_sha" =~ ^[0-9a-f]{40}$ ]] || fail "source SHA is malformed"
[[ -z "$($git_bin -C "$source_root" status --porcelain=v1 --untracked-files=all)" ]] ||
    fail "physical evidence requires a clean source checkout"
readonly gradle_bin="${GRADLE_BIN:-$source_root/gradlew}"
[[ -x "$gradle_bin" ]] || fail "Gradle wrapper is unavailable"
build-gate -- "$gradle_bin" -p "$source_root" --max-workers=4 \
    :app:assembleGithubFullDebug \
    :app:assembleGithubFullDebugAndroidTest \
    -Pripdpi.localNativeAbis=arm64-v8a \
    -Pripdpi.enableAbiSplits=false \
    -Pripdpi.skipNativeBuild=false \
    -Pripdpi.prebuiltJniLibsDir= || fail "source-bound physical APK build failed"
[[ "$($git_bin -C "$source_root" rev-parse HEAD 2>/dev/null)" == "$source_sha" ]] ||
    fail "source checkout changed during the physical APK build"
[[ -z "$($git_bin -C "$source_root" status --porcelain=v1 --untracked-files=all)" ]] ||
    fail "physical APK build changed tracked or untracked source inputs"
readonly app_apk="$source_root/app/build/outputs/apk/githubFull/debug/app-github-full-debug.apk"
readonly test_apk="$source_root/app/build/outputs/apk/androidTest/githubFull/debug/app-github-full-debug-androidTest.apk"
[[ -f "$app_apk" ]] || fail "source-bound app APK was not produced"
[[ -f "$test_apk" ]] || fail "source-bound test APK was not produced"
app_apk_sha256="$(python3 - "$app_apk" <<'PY'
import hashlib
import sys

with open(sys.argv[1], "rb") as source:
    print(hashlib.file_digest(source, "sha256").hexdigest())
PY
)"
readonly app_apk_sha256
test_apk_sha256="$(python3 - "$test_apk" <<'PY'
import hashlib
import sys

with open(sys.argv[1], "rb") as source:
    print(hashlib.file_digest(source, "sha256").hexdigest())
PY
)"
readonly test_apk_sha256
run_id="$(python3 - <<'PY'
import secrets

print(secrets.token_hex(16))
PY
)"
readonly run_id
adb_device() {
    "$adb_bin" -s "$android_serial" "$@"
}

temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/ripdpi-so-bind-physical.XXXXXX")"
device_state_dir="$temp_dir/device-state"
device_state_captured=false
socket_observer_pid=""
cleanup() {
    local status=$?
    trap - EXIT
    set +e
    if [[ -n "$socket_observer_pid" ]]; then
        touch "$temp_dir/socket-observer.stop"
        kill "$socket_observer_pid" 2>/dev/null || true
        wait "$socket_observer_pid" 2>/dev/null || true
    fi
    adb_device shell run-as com.poyka.ripdpi rm -f files/so-bind-socket-window.json files/so-bind-socket-window.tmp files/so-bind-socket-ack.txt >/dev/null 2>&1 || true
    adb_device shell cmd deviceidle tempwhitelist -r com.poyka.ripdpi.test >/dev/null 2>&1 || true
    adb_device shell am force-stop com.poyka.ripdpi >/dev/null 2>&1 || true
    adb_device shell am force-stop com.poyka.ripdpi.test >/dev/null 2>&1 || true
    if [[ "$device_state_captured" == "true" ]]; then
        if ! python3 "$source_root/test-lab/scripts/android-device-session.py" restore \
            --adb "$adb_bin" \
            --serial "$android_serial" \
            --state-dir "$device_state_dir"; then
            echo "SO_BIND physical E2E: failed to restore pre-test Android device state" >&2
            echo "SO_BIND physical E2E: recovery backup retained at $device_state_dir" >&2
            status=1
        fi
    fi
    if [[ $status -eq 0 ]]; then
        rm -rf "$temp_dir"
    fi
    exit "$status"
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
manufacturer="$(adb_device shell getprop ro.product.manufacturer 2>/dev/null | tr -d '\r')"
product_device="$(adb_device shell getprop ro.product.device 2>/dev/null | tr -d '\r')"
api_level="$(adb_device shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
kernel_release="$(adb_device shell uname -r 2>/dev/null | tr -d '\r')"
python3 - "$api_level" "$kernel_release" "$evidence_profile" <<'PYPROFILE' || fail "physical device does not match requested kernel band or API 29+"
import re
import sys
api, release, profile = sys.argv[1:]
match = re.match(r"^(\d+)\.(\d+)(?:[.\-+_]|$)", release)
if not api.isdecimal() or int(api) < 29 or match is None:
    raise SystemExit(1)
modern = tuple(map(int, match.groups())) >= (5, 7)
if modern != (profile == "physical_kernel_ge57"):
    raise SystemExit(1)
PYPROFILE

if [[ -n "$fixture_ipv6_host" ]]; then
ipv6_route="$temp_dir/ipv6-route.txt"
adb_device shell ip -6 route get "$fixture_ipv6_host" >"$ipv6_route" 2>/dev/null ||
    fail_infra IPV6_ROUTE_UNAVAILABLE "selected physical network has no route to the IPv6 fixture"
grep -Eq '(^|[[:space:]])dev[[:space:]]+[^[:space:]]+' "$ipv6_route" ||
    fail_infra IPV6_ROUTE_MALFORMED "IPv6 route lacks an output interface"
grep -Eq '(^|[[:space:]])src[[:space:]]+[0-9A-Fa-f:]+' "$ipv6_route" ||
    fail_infra IPV6_SOURCE_UNAVAILABLE "IPv6 route lacks a selected source address"
ipv6_source="$(awk '{ for (field = 1; field <= NF; field++) if ($field == "src") { print $(field + 1); exit } }' "$ipv6_route")"
ipv6_interface="$(awk '{ for (field = 1; field <= NF; field++) if ($field == "dev") { print $(field + 1); exit } }' "$ipv6_route")"
so_bind_physical_is_underlay_interface "$ipv6_interface" ||
    fail_infra IPV6_UNDERLAY_REQUIRED "IPv6 route does not select a physical Android underlay interface"
so_bind_physical_normalize_routed_ipv6 "$ipv6_source" >/dev/null ||
    fail_infra IPV6_SOURCE_UNAVAILABLE "IPv6 route does not select a routed unicast source address"

fi

python3 "$source_root/test-lab/scripts/android-device-session.py" capture \
    --adb "$adb_bin" \
    --serial "$android_serial" \
    --state-dir "$device_state_dir" \
    --package com.poyka.ripdpi \
    --package com.poyka.ripdpi.test ||
    fail "could not capture the pre-test Android package and VPN state"
device_state_captured=true

install_and_verify_apk "$app_apk" "com.poyka.ripdpi" "app"
install_and_verify_apk "$test_apk" "com.poyka.ripdpi.test" "test"
# A foreground task may be recreated when install -r replaces its process. If
# that happens under HiltTestApplication, MainActivity can start before JUnit
# creates the per-test Hilt component. Quiesce both packages deterministically.
adb_device shell input keyevent HOME >/dev/null 2>&1 ||
    fail "could not background foreground tasks before instrumentation"
adb_device shell am force-stop com.poyka.ripdpi >/dev/null 2>&1 ||
    fail "could not stop the target package before instrumentation"
adb_device shell am force-stop com.poyka.ripdpi.test >/dev/null 2>&1 ||
    fail "could not stop the instrumentation package before instrumentation"
target_pids="$(adb_device shell pidof com.poyka.ripdpi 2>/dev/null | tr -d '\r' || true)"
test_pids="$(adb_device shell pidof com.poyka.ripdpi.test 2>/dev/null | tr -d '\r' || true)"
[[ -z "$target_pids" && -z "$test_pids" ]] ||
    fail "target or instrumentation process remained alive after force-stop"
adb_device shell run-as com.poyka.ripdpi rm -f \
    "files/$evidence_file_name" "files/$infra_gap_file_name" \
    files/so-bind-socket-window.json files/so-bind-socket-ack.txt >/dev/null 2>&1 ||
    fail "could not clear prior physical evidence and infrastructure status"

test_uid_line="$(adb_device shell pm list packages -U com.poyka.ripdpi.test 2>/dev/null | tr -d '\r')" ||
    fail "test package UID lookup failed"
[[ "$test_uid_line" =~ ^package:com\.poyka\.ripdpi\.test[[:space:]]uid:([0-9]+)$ ]] ||
    fail "test package UID lookup was malformed"
test_uid="${BASH_REMATCH[1]}"
adb_device shell cmd deviceidle tempwhitelist -d "$test_probe_allowlist_duration_ms" com.poyka.ripdpi.test \
    >/dev/null 2>&1 || fail "test probe temporary allowlist grant failed"
adb_device shell dumpsys deviceidle >"$temp_dir/deviceidle.txt" 2>/dev/null ||
    fail "test probe temporary allowlist verification failed"
grep -Eq "^[[:space:]]*UID=${test_uid}:" "$temp_dir/deviceidle.txt" ||
    fail "test probe UID is absent from the temporary allowlist"

adb_device shell toybox nc -4 -z -w 5 "$fixture_host" "$fixture_port" >/dev/null 2>&1 ||
    fail "fixture control port is not directly reachable from the physical device"
if [[ -n "$fixture_ipv6_host" ]]; then
adb_device shell toybox nc -6 -n -z -w 5 "$fixture_ipv6_host" "$fixture_port" >/dev/null 2>&1 ||
    fail_infra IPV6_FIXTURE_UNREACHABLE "fixture control port is not reachable over IPv6"
tcp_echo_marker="so-bind-ipv6-tcp-preflight-$run_id"
tcp_echo_response="$(printf '%s' "$tcp_echo_marker" | adb_device shell toybox nc -6 -n -q 1 -w 5 "$fixture_ipv6_host" "$fixture_tcp_echo_port" 2>/dev/null)" ||
    fail_infra IPV6_TCP_FIXTURE_UNREACHABLE "IPv6 TCP echo endpoint did not complete a round trip"
[[ "$tcp_echo_response" == "$tcp_echo_marker" ]] ||
    fail_infra IPV6_TCP_FIXTURE_MISMATCH "IPv6 TCP echo endpoint did not return the exact preflight marker"
udp_echo_marker="so-bind-ipv6-udp-preflight-$run_id"
udp_echo_response="$(printf '%s' "$udp_echo_marker" | adb_device shell toybox nc -6 -n -u -q 1 -w 5 "$fixture_ipv6_host" "$fixture_udp_echo_port" 2>/dev/null)" ||
    fail_infra IPV6_UDP_FIXTURE_UNREACHABLE "IPv6 UDP echo endpoint did not complete a round trip"
[[ "$udp_echo_response" == "$udp_echo_marker" ]] ||
    fail_infra IPV6_UDP_FIXTURE_MISMATCH "IPv6 UDP echo endpoint did not return the exact preflight marker"

fi

instrumentation_components="$(
    adb_device shell pm list instrumentation 2>/dev/null | tr -d '\r' | awk '
        /\(target=com\.poyka\.ripdpi\)$/ && $0 !~ /baselineprofile/ {
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
started_at_epoch_ms="$(python3 - <<'PY'
import time

print(time.time_ns() // 1_000_000)
PY
)"
readonly started_at_epoch_ms

python3 "$script_dir/capture_android_so_bind_sockets.py" \
    --adb "$adb_bin" --serial "$android_serial" --run-id "$run_id" \
    --stop "$temp_dir/socket-observer.stop" --output "$temp_dir/socket-observation.json" \
    --timeout "$instrumentation_timeout_seconds" >"$temp_dir/socket-observer.log" 2>&1 &
socket_observer_pid=$!

set +e
adb_device shell timeout "$instrumentation_timeout_seconds" am instrument -w -r \
    -e class "$test_selector" \
    -e ripdpi.fixtureControlHost "$fixture_host" \
    -e ripdpi.fixtureControlPort "$fixture_port" \
    -e ripdpi.soBindEvidenceProfile "$evidence_profile" \
    -e ripdpi.soBindIpv6Host "$fixture_ipv6_host" \
    -e ripdpi.soBindTcpEchoPort "$fixture_tcp_echo_port" \
    -e ripdpi.soBindUdpEchoPort "$fixture_udp_echo_port" \
    -e ripdpi.soBindRunId "$run_id" \
    -e ripdpi.soBindSourceSha "$source_sha" \
    -e ripdpi.soBindAppApkSha256 "$app_apk_sha256" \
    -e ripdpi.soBindTestApkSha256 "$test_apk_sha256" \
    "$instrumentation_component" >"$output_file" 2>&1
instrumentation_status=$?
touch "$temp_dir/socket-observer.stop"
wait "$socket_observer_pid"
socket_observer_status=$?
socket_observer_pid=""
set -e

infra_gap_reason="$(
    adb_device shell run-as com.poyka.ripdpi cat "files/$infra_gap_file_name" 2>/dev/null | tr -d '\r\n' || true
)"
if [[ -n "$infra_gap_reason" ]]; then
    [[ "$infra_gap_reason" == "ICMP_PING_SOCKET_UNAVAILABLE" ]] ||
        fail "physical test returned an unknown infrastructure status"
    fail_infra ICMP_PING_SOCKET_UNAVAILABLE "device denied the unprivileged ICMP ping socket with EPERM/EACCES"
fi
[[ "$instrumentation_status" == "0" ]] || fail "instrumentation command failed"
[[ "$socket_observer_status" != "2" ]] || fail_infra SOCKET_TABLE_OBSERVATION_UNAVAILABLE "socket table capture was unreadable, malformed, or late"
[[ "$socket_observer_status" == "0" ]] || fail "socket table observation detected a leak or observer failure"
if ! so_bind_physical_output_is_exact_pass "$output_file" "$test_class" "$test_method"; then
    sed 's/^/SO_BIND instrumentation: /' "$output_file" >&2
    fail "instrumentation output was skipped, failed, incomplete, or ambiguous"
fi

physical_evidence="$temp_dir/$evidence_file_name"
adb_device shell run-as com.poyka.ripdpi cat "files/$evidence_file_name" >"$physical_evidence" 2>/dev/null ||
    fail "physical evidence readback failed"
finished_at_epoch_ms="$(python3 - <<'PY'
import time

print(time.time_ns() // 1_000_000)
PY
)"
readonly finished_at_epoch_ms
python3 - "$physical_evidence" "$started_at_epoch_ms" "$finished_at_epoch_ms" "$temp_dir/socket-observation.json" <<'PY'
import json
import sys

path = sys.argv[1]
with open(path, encoding="utf-8") as source:
    evidence = json.load(source)
if "startedAtEpochMs" in evidence or "finishedAtEpochMs" in evidence or "socketTable" in evidence:
    raise SystemExit("device evidence must not supply host capture timestamps")
evidence["startedAtEpochMs"] = int(sys.argv[2])
evidence["finishedAtEpochMs"] = int(sys.argv[3])
with open(sys.argv[4], encoding="utf-8") as source:
    evidence["socketTable"] = json.load(source)
with open(path, "w", encoding="utf-8") as output:
    json.dump(evidence, output, separators=(",", ":"), sort_keys=True)
    output.write("\n")
PY
validation_summary="$(python3 "$script_dir/check_android_so_bind_physical_evidence.py" \
    "$physical_evidence" \
    --profile "$evidence_profile" \
    --device-manufacturer "$manufacturer" \
    --device-codename "$product_device" \
    --api-level "$api_level" \
    --run-id "$run_id" \
    --source-sha "$source_sha" \
    --app-apk-sha256 "$app_apk_sha256" \
    --test-apk-sha256 "$test_apk_sha256" \
    --started-at-epoch-ms "$started_at_epoch_ms")" ||
    fail "physical evidence was missing, partial, or malformed"
if [[ -n "$evidence_output" ]]; then
    [[ "$evidence_output" == /* ]] || fail "RIPDPI_SO_BIND_EVIDENCE_OUTPUT must be absolute"
    install -m 0600 "$physical_evidence" "$evidence_output"
fi

echo "SO_BIND physical E2E passed: $validation_summary"
