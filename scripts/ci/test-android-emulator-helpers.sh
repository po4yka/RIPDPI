#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/ci/android-emulator-helpers.sh
source "$script_dir/android-emulator-helpers.sh"

tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/ripdpi-emulator-helper-test.XXXXXX")"
trap 'rm -rf "$tmpdir"' EXIT

make_executable() {
  local path="$1"
  mkdir -p "$(dirname "$path")"
  printf '#!/usr/bin/env bash\nexit 0\n' >"$path"
  chmod +x "$path"
}

assert_equals() {
  local expected="$1"
  local actual="$2"
  local message="$3"
  if [[ "$actual" != "$expected" ]]; then
    echo "$message: expected '$expected', got '$actual'" >&2
    exit 1
  fi
}

fake_home="$tmpdir/home"
mac_sdk="$fake_home/Library/Android/sdk"
explicit_sdk="$tmpdir/explicit-sdk"
fake_homebrew="$tmpdir/homebrew"

make_executable "$mac_sdk/platform-tools/adb"
make_executable "$mac_sdk/emulator/emulator"
make_executable "$mac_sdk/cmdline-tools/latest/bin/avdmanager"
make_executable "$explicit_sdk/platform-tools/adb"
make_executable "$explicit_sdk/emulator/emulator"
make_executable "$fake_homebrew/bin/adb"

actual="$(
  PATH="$fake_homebrew/bin:/usr/bin:/bin" \
    HOME="$fake_home" \
    ANDROID_HOME='' \
    ANDROID_SDK_ROOT='' \
    resolve_android_sdk_root
)"
assert_equals "$mac_sdk" "$actual" "macOS SDK root should win over Homebrew adb parent"

actual="$(
  PATH="$fake_homebrew/bin:/usr/bin:/bin" \
    HOME="$fake_home" \
    ANDROID_HOME="$tmpdir/not-an-sdk" \
    ANDROID_SDK_ROOT='' \
    resolve_android_sdk_root
)"
assert_equals "$mac_sdk" "$actual" "invalid ANDROID_HOME should not mask the macOS SDK"

actual="$(
  PATH="$fake_homebrew/bin:/usr/bin:/bin" \
    HOME="$fake_home" \
    ANDROID_HOME="$mac_sdk" \
    ANDROID_SDK_ROOT="$explicit_sdk" \
    resolve_android_sdk_root
)"
assert_equals "$explicit_sdk" "$actual" "valid ANDROID_SDK_ROOT should take priority"

actual="$(
  PATH="$fake_homebrew/bin:/usr/bin:/bin" \
    HOME="$fake_home" \
    ANDROID_HOME='' \
    ANDROID_SDK_ROOT='' \
    resolve_emulator_bin
)"
assert_equals "$mac_sdk/emulator/emulator" "$actual" "emulator binary should resolve from discovered macOS SDK"

actual="$(
  PATH="$fake_homebrew/bin:/usr/bin:/bin" \
    HOME="$fake_home" \
    ANDROID_HOME='' \
    ANDROID_SDK_ROOT='' \
    resolve_avdmanager_bin
)"
assert_equals "$mac_sdk/cmdline-tools/latest/bin/avdmanager" "$actual" "avdmanager should resolve from discovered macOS SDK"

(
  # Use the real timeout process supervisor, with short test deadlines. ADB
  # deliberately ignores TERM so the escalation path is exercised as well.
  real_timeout="$(command -v timeout)"
  fake_adb="$tmpdir/wedged-adb"
  export EMULATOR_TEST_TIMEOUT_LOG="$tmpdir/timeout-calls"
  export EMULATOR_TEST_CONNECTED=false
  : > "$EMULATOR_TEST_TIMEOUT_LOG"
  cat > "$fake_adb" <<'ADB'
#!/usr/bin/env bash
if [[ "$*" == *get-state && "$EMULATOR_TEST_CONNECTED" == true ]]; then
  echo device
  exit 0
fi
trap '' TERM
while :; do sleep 1; done
ADB
  chmod +x "$fake_adb"

  resolve_adb_bin() { printf '%s\n' "$fake_adb"; }
  timeout() {
    [[ "$1" == --kill-after=2 ]] || { echo "ADB timeout lacks kill escalation" >&2; return 97; }
    shift
    [[ "$1" -gt 0 && "$1" -le 30 ]] || return 98
    printf '%s\n' "$1" >> "$EMULATOR_TEST_TIMEOUT_LOG"
    shift
    "$real_timeout" --kill-after=0.1 1 "$@"
  }

  echo "Checking diagnostics with unresponsive ADB"
  capture_android_emulator_diagnostics "$tmpdir/offline-diagnostics"
  assert_equals 2 "$(wc -l < "$EMULATOR_TEST_TIMEOUT_LOG" | tr -d ' ')" "Offline ADB commands must be bounded"
  for artifact in android-logcat.txt android-logcat-crash.txt adb-devices.txt device-getprop.txt package-manager-health.txt emulator.log avd-config.ini avdmanager-create.log; do
    [[ -f "$tmpdir/offline-diagnostics/$artifact" ]] || { echo "Missing diagnostic artifact: $artifact" >&2; exit 1; }
  done

  : > "$EMULATOR_TEST_TIMEOUT_LOG"
  export EMULATOR_TEST_CONNECTED=true
  capture_android_emulator_diagnostics "$tmpdir/connected-diagnostics"
  assert_equals 6 "$(wc -l < "$EMULATOR_TEST_TIMEOUT_LOG" | tr -d ' ')" "Connected device diagnostics must all be bounded"

  : > "$EMULATOR_TEST_TIMEOUT_LOG"
  if stop_android_emulator test-owned-avd; then
    echo "Emulator stop must fail when ADB cannot identify the AVD" >&2
    exit 1
  fi
  assert_equals 1 "$(wc -l < "$EMULATOR_TEST_TIMEOUT_LOG" | tr -d ' ')" "Emulator discovery must be bounded"
)

(
  fake_adb="$tmpdir/healthy-adb"
  export EMULATOR_STOP_LOG="$tmpdir/emulator-stop-log"
  export EMULATOR_PROCESS_COUNT_LOG="$tmpdir/process-checks"
  export ANDROID_SERIAL=emulator-5554
  mkdir -p "$tmpdir/stop-tools"
  cat > "$tmpdir/stop-tools/ps" <<'PS'
#!/usr/bin/env bash
printf 'check\n' >> "$EMULATOR_PROCESS_COUNT_LOG"
if [[ "$(wc -l < "$EMULATOR_PROCESS_COUNT_LOG" | tr -d ' ')" == 1 ]]; then
  printf '/sdk/emulator/qemu-system-aarch64 -avd test-owned-avd\n'
fi
PS
  chmod +x "$tmpdir/stop-tools/ps"
  export PATH="$tmpdir/stop-tools:$PATH"
  cat > "$fake_adb" <<'ADB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$EMULATOR_STOP_LOG"
case "$*" in
  devices)
    printf 'List of devices attached\nemulator-5554\tdevice\nphone\tdevice\n'
    [[ -f "${EMULATOR_STOP_LOG}.done" ]] || printf 'emulator-5556\tdevice\n'
    ;;
  '-s emulator-5554 emu avd name') printf 'unrelated-avd\nOK\n' ;;
  '-s emulator-5556 emu avd name') printf 'test-owned-avd\nOK\n' ;;
  '-s emulator-5556 emu kill') touch "${EMULATOR_STOP_LOG}.done" ;;
  *) exit 9 ;;
esac
ADB
  chmod +x "$fake_adb"
  resolve_adb_bin() { printf '%s\n' "$fake_adb"; }
  assert_equals "$fake_adb" "$(resolve_adb_bin)" "Stop test must use the test ADB"
  stop_android_emulator test-owned-avd
  [[ -f "${EMULATOR_STOP_LOG}.done" ]] || { echo "Owned emulator was not stopped" >&2; exit 1; }
  assert_equals 1 "$(grep -c 'emu kill' "$EMULATOR_STOP_LOG")" "An unrelated emulator must remain active"
  assert_equals 3 "$(wc -l < "$EMULATOR_PROCESS_COUNT_LOG" | tr -d ' ')" "Stop must wait for shutdown and confirm no remaining process"
  if stop_android_emulator RIPDPI_Pool_1; then
    echo "A detached stop must not bypass the pool lease" >&2
    exit 1
  fi
  cat > "$fake_adb" <<'ADB'
#!/usr/bin/env bash
if [[ "$*" == devices ]]; then
  printf 'List of devices attached\nemulator-5554\toffline\n'
  exit 0
fi
exit 9
ADB
  if stop_android_emulator test-owned-avd; then
    echo "Offline emulator must block disk recreation" >&2
    exit 1
  fi
  cat > "$fake_adb" <<'ADB'
#!/usr/bin/env bash
printf 'List of devices attached\n'
ADB
  cat > "$tmpdir/stop-tools/ps" <<'PS'
#!/usr/bin/env bash
printf '/sdk/emulator -avd test-owned-avd\n'
PS
  if stop_android_emulator test-owned-avd; then
    echo "An emulator absent from ADB must still block a successful stop" >&2
    exit 1
  fi
)

echo "Android emulator helper tests passed."
