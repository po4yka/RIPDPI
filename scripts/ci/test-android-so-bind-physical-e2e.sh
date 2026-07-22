#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
runner="$repo_root/scripts/ci/run-android-so-bind-physical-e2e.sh"
library="$repo_root/scripts/ci/android-so-bind-physical-lib.sh"
# shellcheck source=scripts/ci/android-so-bind-physical-lib.sh
source "$library"
temp_dir="$(mktemp -d)"
source_bound_app_apk="$repo_root/app/build/outputs/apk/githubFull/debug/app-github-full-debug.apk"
source_bound_test_apk="$repo_root/app/build/outputs/apk/androidTest/githubFull/debug/app-github-full-debug-androidTest.apk"
cleanup() {
    rm -f "$source_bound_app_apk" "$source_bound_test_apk"
    rm -rf "$temp_dir"
}
trap cleanup EXIT

grep -Fq 'RIPDPI_FIXTURE_ANDROID_IPV6_HOST' "$runner" || {
    echo "assertion failed: physical runner does not require an IPv6 fixture endpoint" >&2
    exit 1
}
grep -Fq 'ripdpi.soBindIpv6Host' "$runner" || {
    echo "assertion failed: physical runner does not pass IPv6 capability into instrumentation" >&2
    exit 1
}
grep -Fq 'RIPDPI_FIXTURE_TCP_ECHO_PORT' "$runner" || {
    echo "assertion failed: physical runner does not require the TCP echo endpoint" >&2
    exit 1
}
grep -Fq 'RIPDPI_FIXTURE_UDP_ECHO_PORT' "$runner" || {
    echo "assertion failed: physical runner does not require the UDP echo endpoint" >&2
    exit 1
}

fake_git="$temp_dir/git"
cat >"$fake_git" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == "-C" ]] || exit 98
requested_root="$(cd "$2" && pwd)"
[[ "$requested_root" == "$FAKE_REPO_ROOT" ]] || exit 98
shift 2
case "$*" in
    "rev-parse --show-toplevel") echo "$FAKE_REPO_ROOT" ;;
    "rev-parse HEAD") printf '%040d\n' 1 ;;
    "status --porcelain=v1 --untracked-files=all")
        [[ "${FAKE_DIRTY_SOURCE:-0}" != "1" ]] || echo '?? fabricated-evidence'
        [[ "${FAKE_BUILD_DIRTY_SOURCE:-0}" != "1" || ! -f "$FAKE_BUILD_MARKER" ]] || echo ' M app/build.gradle.kts'
        ;;
    *) exit 97 ;;
esac
EOF
chmod +x "$fake_git"

fake_gradle="$temp_dir/gradlew"
cat >"$fake_gradle" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "${FAKE_BUILD_FAILURE:-0}" != "1" ]] || exit 1
[[ "$*" == *":app:assembleGithubFullDebug"* ]] || exit 84
[[ "$*" == *":app:assembleGithubFullDebugAndroidTest"* ]] || exit 83
[[ "$*" == *"-Pripdpi.localNativeAbis=arm64-v8a"* ]] || exit 82
[[ "$*" == *"-Pripdpi.enableAbiSplits=false"* ]] || exit 79
[[ "$*" == *"-Pripdpi.skipNativeBuild=false"* ]] || exit 81
[[ "$*" == *"-Pripdpi.prebuiltJniLibsDir="* ]] || exit 80
app_output="$FAKE_REPO_ROOT/app/build/outputs/apk/githubFull/debug/app-github-full-debug.apk"
test_output="$FAKE_REPO_ROOT/app/build/outputs/apk/androidTest/githubFull/debug/app-github-full-debug-androidTest.apk"
mkdir -p "$(dirname "$app_output")" "$(dirname "$test_output")"
cp "$FAKE_APP_APK_SEED" "$app_output"
cp "$FAKE_TEST_APK_SEED" "$test_output"
: >"$FAKE_BUILD_MARKER"
EOF
chmod +x "$fake_gradle"

fake_adb="$temp_dir/adb"
cat >"$fake_adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == "-s" && "$2" == "pixel-serial" ]] || exit 90
shift 2
extract_arg() {
    local needle="$1"
    shift
    while (($# > 1)); do
        if [[ "$1" == "$needle" ]]; then
            printf '%s' "$2"
            return 0
        fi
        shift
    done
    return 1
}
case "$*" in
    get-state) echo device ;;
    "shell getprop ro.kernel.qemu") echo "${FAKE_QEMU:-0}" ;;
    "shell getprop ro.boot.qemu") echo "${FAKE_QEMU:-0}" ;;
    "shell getprop ro.hardware") echo "${FAKE_HARDWARE:-tensor}" ;;
    "shell getprop ro.product.manufacturer") echo "${FAKE_MANUFACTURER:-Google}" ;;
    "shell getprop ro.product.device") echo "${FAKE_PRODUCT_DEVICE:-panther}" ;;
    "shell getprop ro.build.version.sdk") echo "${FAKE_API:-37}" ;;
    "shell uname -r") echo "${FAKE_KERNEL:-6.1.99-test}" ;;
    "shell ip -6 route get "*)
        [[ "${FAKE_IPV6_ROUTE_UNAVAILABLE:-0}" != "1" ]] || exit 1
        target="${*: -1}"
        echo "$target from :: dev ${FAKE_IPV6_DEVICE:-wlan0} src ${FAKE_IPV6_SOURCE:-2001:4860:1::10} metric 1024"
        ;;
    "install -r -d "*)
        [[ "${FAKE_INSTALL_FAILURE:-0}" != "1" ]] || exit 1
        echo Success
        ;;
    "shell pm path com.poyka.ripdpi")
        echo 'package:/data/app/app/base.apk'
        [[ "${FAKE_AMBIGUOUS_PATH:-0}" != "1" ]] || echo 'package:/data/app/app/split.apk'
        ;;
    "shell pm path com.poyka.ripdpi.test") echo 'package:/data/app/test/base.apk' ;;
    "shell pm list packages -U com.poyka.ripdpi.test") echo 'package:com.poyka.ripdpi.test uid:10444' ;;
    "shell run-as com.poyka.ripdpi rm -f files/so-bind-physical-evidence.json") : ;;
    "shell run-as com.poyka.ripdpi cat files/so-bind-physical-evidence.json")
        [[ "${FAKE_EVIDENCE_MISSING:-0}" != "1" ]] || exit 1
        cat "$FAKE_EVIDENCE"
        ;;
    "shell cmd deviceidle tempwhitelist -d 300000 com.poyka.ripdpi.test")
        : >"$FAKE_ALLOWLIST_MARKER"
        ;;
    "shell dumpsys deviceidle")
        [[ -f "$FAKE_ALLOWLIST_MARKER" ]] || exit 94
        echo '  Temp whitelist schedule:'
        echo '    UID=10444: +4m59s - shell'
        ;;
    "shell cmd deviceidle tempwhitelist -r com.poyka.ripdpi.test") rm -f "$FAKE_ALLOWLIST_MARKER" ;;
    "pull /data/app/app/base.apk "*)
        destination="${@: -1}"
        if [[ "${FAKE_APK_MISMATCH:-0}" == "1" ]]; then
            printf 'mismatch' >"$destination"
        else
            cp "$FAKE_APP_APK" "$destination"
        fi
        echo '1 file pulled'
        ;;
    "pull /data/app/test/base.apk "*)
        destination="${@: -1}"
        cp "$FAKE_TEST_APK" "$destination"
        echo '1 file pulled'
        ;;
    "shell toybox nc -4 -z -w 5 "*) [[ "${FAKE_FIXTURE_UNREACHABLE:-0}" != "1" ]] ;;
    "shell toybox nc -6 -n -z -w 5 "*) [[ "${FAKE_IPV6_FIXTURE_UNREACHABLE:-0}" != "1" ]] ;;
    "shell toybox nc -6 -n -q 1 -w 5 "*)
        [[ "${FAKE_IPV6_TCP_ECHO_UNREACHABLE:-0}" != "1" ]] || exit 1
        if [[ "${FAKE_IPV6_TCP_ECHO_MISMATCH:-0}" == "1" ]]; then
            echo mismatch
        else
            cat
        fi
        ;;
    "shell toybox nc -6 -n -u -q 1 -w 5 "*)
        [[ "${FAKE_IPV6_UDP_ECHO_UNREACHABLE:-0}" != "1" ]] || exit 1
        if [[ "${FAKE_IPV6_UDP_ECHO_MISMATCH:-0}" == "1" ]]; then
            echo mismatch
        else
            cat
        fi
        ;;
    "shell pm list instrumentation")
        echo 'instrumentation:com.poyka.ripdpi.full.test/com.poyka.ripdpi.HiltTestRunner (target=com.poyka.ripdpi.full)'
        echo 'instrumentation:com.poyka.ripdpi.baselineprofile/androidx.benchmark.junit4.AndroidBenchmarkRunner (target=com.poyka.ripdpi)'
        echo 'instrumentation:com.poyka.ripdpi.test/com.poyka.ripdpi.HiltTestRunner (target=com.poyka.ripdpi)'
        echo 'instrumentation:com.poyka.ripdpi.simple.test/com.poyka.ripdpi.HiltTestRunner (target=com.poyka.ripdpi.simple)'
        ;;
    "shell timeout "*)
        [[ -f "$FAKE_ALLOWLIST_MARKER" ]] || exit 95
        [[ "$3" == "180" ]] || exit 89
        [[ "$*" == *"-e ripdpi.soBindEvidenceProfile physical_pixel_api37_kernel61"* ]] || exit 91
        [[ "$*" == *"-e ripdpi.soBindIpv6Host ${FAKE_IPV6_HOST:-2606:4700:4700::1111}"* ]] || exit 96
        [[ "$*" == *"-e ripdpi.soBindTcpEchoPort 46091"* ]] || exit 86
        [[ "$*" == *"-e ripdpi.soBindUdpEchoPort 46092"* ]] || exit 85
        [[ "$*" == *"-e class com.poyka.ripdpi.e2e.NetworkPathE2ETest#vpnServiceDeniesExcludedTestUidBoundToTun0"* ]] || exit 92
        case "${FAKE_RESULT:-pass}" in
            pass)
                run_id="$(extract_arg ripdpi.soBindRunId "$@")"
                source_sha="$(extract_arg ripdpi.soBindSourceSha "$@")"
                app_sha="$(extract_arg ripdpi.soBindAppApkSha256 "$@")"
                test_sha="$(extract_arg ripdpi.soBindTestApkSha256 "$@")"
                [[ "${FAKE_EVIDENCE_TAMPER:-0}" != "1" ]] || run_id="00000000000000000000000000000000"
                python3 - "$FAKE_EVIDENCE" "$run_id" "$source_sha" "$app_sha" "$test_sha" "${FAKE_DEVICE_TIMESTAMPS:-0}" <<'PY'
import json
import sys

positive_names = (
    "directTcpRoundTrips", "directUdpRoundTrips", "directTcpFixtureEvents", "directUdpFixtureEvents",
    "allowedTcpRoundTrips", "allowedUdpRoundTrips", "allowedTcpFixtureEvents", "allowedUdpFixtureEvents",
    "deniedTcpBlockedAttempts", "deniedUdpBlockedAttempts", "livenessTcpRoundTrips", "livenessUdpRoundTrips",
    "livenessTcpFixtureEvents", "livenessUdpFixtureEvents",
)
positive = {name: 1 for name in positive_names}
families = [{
    "family": family,
    "sourceFamilyVerified": True,
    "deniedTcpErrno": 110,
    "deniedTcpFailureKind": "TIMEOUT",
    "deniedTcpFailureStage": "connect",
    "deniedUdpErrno": 110,
    "deniedUdpFailureKind": "TIMEOUT",
    "deniedUdpFailureStage": "receive",
    **positive,
    "deniedTcpFixtureEvents": 0,
    "deniedUdpFixtureEvents": 0,
} for family in ("ipv4", "ipv6")]
evidence = {
        "version": "android_so_bind_physical_evidence_v2",
        "status": "PASS",
        "profile": "physical_pixel_api37_kernel61",
        "runId": sys.argv[2],
        "sourceSha": sys.argv[3],
        "appApkSha256": sys.argv[4],
        "testApkSha256": sys.argv[5],
        "deviceManufacturer": "Google",
        "deviceCodename": "panther",
        "apiLevel": 37,
        "kernelFamily": "6.1",
        "realTun": True,
        "tunPacketPathObserved": True,
        "families": families,
}
if sys.argv[6] == "1":
    evidence["startedAtEpochMs"] = 1
    evidence["finishedAtEpochMs"] = 2
with open(sys.argv[1], "w", encoding="utf-8") as output:
    json.dump(evidence, output)
PY
                cat <<'OUTPUT'
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS: class=com.poyka.ripdpi.e2e.NetworkPathE2ETest
INSTRUMENTATION_STATUS: test=vpnServiceDeniesExcludedTestUidBoundToTun0
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS: class=com.poyka.ripdpi.e2e.NetworkPathE2ETest
INSTRUMENTATION_STATUS: test=vpnServiceDeniesExcludedTestUidBoundToTun0
INSTRUMENTATION_STATUS_CODE: 0
OK (1 test)
INSTRUMENTATION_CODE: -1
OUTPUT
                ;;
            skipped)
                cat <<'OUTPUT'
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS: class=com.poyka.ripdpi.e2e.NetworkPathE2ETest
INSTRUMENTATION_STATUS: test=vpnServiceDeniesExcludedTestUidBoundToTun0
INSTRUMENTATION_STATUS: stack=org.junit.AssumptionViolatedException: physical-only
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS: class=com.poyka.ripdpi.e2e.NetworkPathE2ETest
INSTRUMENTATION_STATUS: test=vpnServiceDeniesExcludedTestUidBoundToTun0
INSTRUMENTATION_STATUS_CODE: 0
OK (1 test)
INSTRUMENTATION_CODE: -1
OUTPUT
                ;;
            duplicate)
                cat <<'OUTPUT'
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS: class=com.poyka.ripdpi.e2e.NetworkPathE2ETest
INSTRUMENTATION_STATUS: test=vpnServiceDeniesExcludedTestUidBoundToTun0
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS: class=com.poyka.ripdpi.e2e.NetworkPathE2ETest
INSTRUMENTATION_STATUS: test=vpnServiceDeniesExcludedTestUidBoundToTun0
INSTRUMENTATION_STATUS_CODE: 0
INSTRUMENTATION_STATUS_CODE: 0
OK (1 test)
INSTRUMENTATION_CODE: -1
OUTPUT
                ;;
            zero)
                echo 'OK (0 tests)'
                echo 'INSTRUMENTATION_CODE: -1'
                ;;
            command_failure) exit 124 ;;
        esac
        ;;
    *) exit 93 ;;
esac
EOF
chmod +x "$fake_adb"
app_apk="$temp_dir/app.apk"
test_apk="$temp_dir/test.apk"
printf 'app-apk' >"$app_apk"
printf 'test-apk' >"$test_apk"
evidence="$temp_dir/evidence.json"

readonly_scope_stderr="$temp_dir/readonly-scope.stderr"
(
    # Match the runner's readonly globals. The validator must not try to shadow
    # them with local variables because that emits an error in Bash.
    readonly test_class="com.poyka.ripdpi.e2e.NetworkPathE2ETest"
    readonly test_method="vpnServiceDeniesExcludedTestUidBoundToTun0"
    # shellcheck source=scripts/ci/android-so-bind-physical-lib.sh
    source "$library"
    pass_output="$temp_dir/readonly-pass.txt"
    cat >"$pass_output" <<'OUTPUT'
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS: class=com.poyka.ripdpi.e2e.NetworkPathE2ETest
INSTRUMENTATION_STATUS: test=vpnServiceDeniesExcludedTestUidBoundToTun0
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS: class=com.poyka.ripdpi.e2e.NetworkPathE2ETest
INSTRUMENTATION_STATUS: test=vpnServiceDeniesExcludedTestUidBoundToTun0
INSTRUMENTATION_STATUS_CODE: 0
OK (1 test)
INSTRUMENTATION_CODE: -1
OUTPUT
    so_bind_physical_output_is_exact_pass "$pass_output" "$test_class" "$test_method"
) 2>"$readonly_scope_stderr"
[[ ! -s "$readonly_scope_stderr" ]] || {
    echo "assertion failed: validator wrote to stderr in readonly caller scope" >&2
    cat "$readonly_scope_stderr" >&2
    exit 1
}
normalized_ipv6="$(so_bind_physical_normalize_routed_ipv6 '2606:4700:4700:0:0:0:0:1111')"
[[ "$normalized_ipv6" == "2606:4700:4700::1111" ]] || {
    echo "assertion failed: routed IPv6 normalization was not canonical" >&2
    exit 1
}
normalized_ula="$(so_bind_physical_normalize_routed_ipv6 'fd08:7888:d1e0:45b1:0:0:0:1')"
[[ "$normalized_ula" == "fd08:7888:d1e0:45b1::1" ]] || {
    echo "assertion failed: routed ULA normalization was not canonical" >&2
    exit 1
}
if so_bind_physical_normalize_routed_ipv6 '::ffff:8.8.8.8' >/dev/null 2>&1; then
    echo "assertion failed: IPv4-mapped IPv6 endpoint was accepted" >&2
    exit 1
fi
for unrouted in '::' '::1' 'fe80::1' 'ff02::1' 'fd00::1%wlan0' 'fec0::1'; do
    if so_bind_physical_normalize_routed_ipv6 "$unrouted" >/dev/null 2>&1; then
        echo "assertion failed: unrouted IPv6 endpoint was accepted: $unrouted" >&2
        exit 1
    fi
done

run_runner() {
    rm -f "$temp_dir/build.marker"
    env \
        ADB_BIN="$fake_adb" \
        GIT_BIN="$fake_git" \
        GRADLE_BIN="$fake_gradle" \
        FAKE_REPO_ROOT="$repo_root" \
        FAKE_BUILD_MARKER="$temp_dir/build.marker" \
        ANDROID_SERIAL=pixel-serial \
        RIPDPI_FIXTURE_ANDROID_HOST=192.0.2.10 \
        RIPDPI_FIXTURE_ANDROID_IPV6_HOST=2606:4700:4700::1111 \
        RIPDPI_FIXTURE_CONTROL_PORT=46090 \
        RIPDPI_FIXTURE_TCP_ECHO_PORT=46091 \
        RIPDPI_FIXTURE_UDP_ECHO_PORT=46092 \
        FAKE_APP_APK_SEED="$app_apk" \
        FAKE_TEST_APK_SEED="$test_apk" \
        FAKE_APP_APK="$source_bound_app_apk" \
        FAKE_TEST_APK="$source_bound_test_apk" \
        FAKE_EVIDENCE="$evidence" \
        FAKE_ALLOWLIST_MARKER="$temp_dir/allowlist.marker" \
        "$@" \
        bash "$runner" >"$temp_dir/runner.stdout" 2>"$temp_dir/runner.stderr"
}

assert_status() {
    local expected="$1"
    local label="$2"
    shift 2
    set +e
    "$@"
    local actual=$?
    set -e
    if [[ "$actual" != "$expected" ]]; then
        echo "assertion failed: $label expected=$expected actual=$actual" >&2
        sed 's/^/runner stdout: /' "$temp_dir/runner.stdout" >&2 || true
        sed 's/^/runner stderr: /' "$temp_dir/runner.stderr" >&2 || true
        exit 1
    fi
}

assert_status 0 "exact pass" run_runner FAKE_RESULT=pass
assert_status 0 "routed ULA endpoint and source pass" run_runner \
    RIPDPI_FIXTURE_ANDROID_IPV6_HOST=fd08:7888:d1e0:45b1::1 \
    FAKE_IPV6_HOST=fd08:7888:d1e0:45b1::1 \
    FAKE_IPV6_SOURCE=fd08:7888:d1e0:45b1::2
assert_status 2 "VPN interface cannot satisfy physical IPv6 route" run_runner \
    RIPDPI_FIXTURE_ANDROID_IPV6_HOST=fd08:7888:d1e0:45b1::1 \
    FAKE_IPV6_HOST=fd08:7888:d1e0:45b1::1 \
    FAKE_IPV6_SOURCE=fd08:7888:d1e0:45b1::2 \
    FAKE_IPV6_DEVICE=tun0
assert_status 0 "global skip-native override defeated" run_runner \
    ORG_GRADLE_PROJECT_ripdpi.skipNativeBuild=true
assert_status 0 "global prebuilt-native override defeated" run_runner \
    ORG_GRADLE_PROJECT_ripdpi.prebuiltJniLibsDir=/tmp/stale-native
assert_status 1 "skip rejected" run_runner FAKE_RESULT=skipped
assert_status 1 "duplicate pass marker rejected" run_runner FAKE_RESULT=duplicate
assert_status 1 "zero tests rejected" run_runner FAKE_RESULT=zero
assert_status 1 "instrumentation timeout rejected" run_runner FAKE_RESULT=command_failure
assert_status 1 "emulator rejected" run_runner FAKE_QEMU=1
assert_status 1 "wrong API rejected" run_runner FAKE_API=36
assert_status 1 "wrong kernel rejected" run_runner FAKE_KERNEL=5.15-test
assert_status 1 "emulator hardware rejected" run_runner FAKE_HARDWARE=ranchu
assert_status 1 "wrong manufacturer rejected" run_runner FAKE_MANUFACTURER=Samsung
assert_status 1 "wrong Pixel product rejected" run_runner FAKE_PRODUCT_DEVICE=oriole
assert_status 1 "dirty source rejected" run_runner FAKE_DIRTY_SOURCE=1
assert_status 1 "source-bound APK build failure rejected" run_runner FAKE_BUILD_FAILURE=1
assert_status 1 "source-changing APK build rejected" run_runner FAKE_BUILD_DIRTY_SOURCE=1
assert_status 1 "install failure rejected" run_runner FAKE_INSTALL_FAILURE=1
assert_status 1 "APK byte mismatch rejected" run_runner FAKE_APK_MISMATCH=1
assert_status 1 "ambiguous package path rejected" run_runner FAKE_AMBIGUOUS_PATH=1
assert_status 1 "unreachable direct fixture rejected" run_runner FAKE_FIXTURE_UNREACHABLE=1
assert_status 2 "missing IPv6 route is an infra gap" run_runner FAKE_IPV6_ROUTE_UNAVAILABLE=1
assert_status 2 "unreachable IPv6 fixture is an infra gap" run_runner FAKE_IPV6_FIXTURE_UNREACHABLE=1
assert_status 2 "unreachable IPv6 TCP echo is an infra gap" run_runner FAKE_IPV6_TCP_ECHO_UNREACHABLE=1
assert_status 2 "mismatched IPv6 TCP echo is an infra gap" run_runner FAKE_IPV6_TCP_ECHO_MISMATCH=1
assert_status 2 "unreachable IPv6 UDP echo is an infra gap" run_runner FAKE_IPV6_UDP_ECHO_UNREACHABLE=1
assert_status 2 "mismatched IPv6 UDP echo is an infra gap" run_runner FAKE_IPV6_UDP_ECHO_MISMATCH=1
assert_status 1 "missing evidence rejected" run_runner FAKE_EVIDENCE_MISSING=1
assert_status 1 "fabricated evidence rejected" run_runner FAKE_EVIDENCE_TAMPER=1
assert_status 1 "device-supplied wall-clock timestamps rejected" run_runner FAKE_DEVICE_TIMESTAMPS=1
set +e
env ADB_BIN="$fake_adb" ANDROID_SERIAL=pixel-serial \
    RIPDPI_FIXTURE_ANDROID_HOST=127.0.0.1 RIPDPI_FIXTURE_CONTROL_PORT=46090 \
    RIPDPI_FIXTURE_ANDROID_IPV6_HOST=2606:4700:4700::1111 \
    bash "$runner" >/dev/null 2>&1
loopback_status=$?
set -e
[[ "$loopback_status" == "1" ]] || {
    echo "assertion failed: loopback fixture expected=1 actual=$loopback_status" >&2
    exit 1
}

run_without_ipv6() {
    env ADB_BIN="$fake_adb" ANDROID_SERIAL=pixel-serial \
        RIPDPI_FIXTURE_ANDROID_HOST=192.0.2.10 RIPDPI_FIXTURE_CONTROL_PORT=46090 \
        bash "$runner" >/dev/null 2>&1
}
assert_status 2 "missing IPv6 endpoint is an infra gap" run_without_ipv6

echo "android SO_BIND physical runner contract tests passed"
