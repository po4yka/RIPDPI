#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
runner="$repo_root/scripts/ci/run-android-so-bind-physical-e2e.sh"
library="$repo_root/scripts/ci/android-so-bind-physical-lib.sh"
temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"' EXIT

fake_adb="$temp_dir/adb"
cat >"$fake_adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == "-s" && "$2" == "pixel-serial" ]] || exit 90
shift 2
case "$*" in
    get-state) echo device ;;
    "shell getprop ro.kernel.qemu") echo "${FAKE_QEMU:-0}" ;;
    "shell getprop ro.boot.qemu") echo "${FAKE_QEMU:-0}" ;;
    "shell getprop ro.hardware") echo "${FAKE_HARDWARE:-tensor}" ;;
    "shell getprop ro.build.version.sdk") echo "${FAKE_API:-37}" ;;
    "shell uname -r") echo "${FAKE_KERNEL:-6.1.99-test}" ;;
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
    "shell toybox nc -z -w 5 "*) [[ "${FAKE_FIXTURE_UNREACHABLE:-0}" != "1" ]] ;;
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
        [[ "$*" == *"-e class com.poyka.ripdpi.e2e.NetworkPathE2ETest#vpnServiceDeniesExcludedTestUidBoundToTun0"* ]] || exit 92
        case "${FAKE_RESULT:-pass}" in
            pass)
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

run_runner() {
    env \
        ADB_BIN="$fake_adb" \
        ANDROID_SERIAL=pixel-serial \
        RIPDPI_FIXTURE_ANDROID_HOST=192.0.2.10 \
        RIPDPI_FIXTURE_CONTROL_PORT=46090 \
        RIPDPI_APP_APK="$app_apk" \
        RIPDPI_TEST_APK="$test_apk" \
        FAKE_APP_APK="$app_apk" \
        FAKE_TEST_APK="$test_apk" \
        FAKE_ALLOWLIST_MARKER="$temp_dir/allowlist.marker" \
        "$@" \
        bash "$runner" >/dev/null 2>&1
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
        exit 1
    fi
}

assert_status 0 "exact pass" run_runner FAKE_RESULT=pass
assert_status 1 "skip rejected" run_runner FAKE_RESULT=skipped
assert_status 1 "duplicate pass marker rejected" run_runner FAKE_RESULT=duplicate
assert_status 1 "zero tests rejected" run_runner FAKE_RESULT=zero
assert_status 1 "instrumentation timeout rejected" run_runner FAKE_RESULT=command_failure
assert_status 1 "emulator rejected" run_runner FAKE_QEMU=1
assert_status 1 "wrong API rejected" run_runner FAKE_API=36
assert_status 1 "wrong kernel rejected" run_runner FAKE_KERNEL=5.15-test
assert_status 1 "emulator hardware rejected" run_runner FAKE_HARDWARE=ranchu
assert_status 1 "install failure rejected" run_runner FAKE_INSTALL_FAILURE=1
assert_status 1 "APK byte mismatch rejected" run_runner FAKE_APK_MISMATCH=1
assert_status 1 "ambiguous package path rejected" run_runner FAKE_AMBIGUOUS_PATH=1
assert_status 1 "unreachable direct fixture rejected" run_runner FAKE_FIXTURE_UNREACHABLE=1
set +e
env ADB_BIN="$fake_adb" ANDROID_SERIAL=pixel-serial \
    RIPDPI_FIXTURE_ANDROID_HOST=127.0.0.1 RIPDPI_FIXTURE_CONTROL_PORT=46090 \
    RIPDPI_APP_APK="$app_apk" RIPDPI_TEST_APK="$test_apk" \
    bash "$runner" >/dev/null 2>&1
loopback_status=$?
set -e
[[ "$loopback_status" == "1" ]] || {
    echo "assertion failed: loopback fixture expected=1 actual=$loopback_status" >&2
    exit 1
}

run_without_apk() {
    env ADB_BIN="$fake_adb" ANDROID_SERIAL=pixel-serial \
        RIPDPI_FIXTURE_ANDROID_HOST=192.0.2.10 RIPDPI_FIXTURE_CONTROL_PORT=46090 \
        bash "$runner" >/dev/null 2>&1
}
assert_status 1 "missing APK rejected" run_without_apk

echo "android SO_BIND physical runner contract tests passed"
