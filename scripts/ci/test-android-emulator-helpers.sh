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
    ANDROID_HOME= \
    ANDROID_SDK_ROOT= \
    resolve_android_sdk_root
)"
assert_equals "$mac_sdk" "$actual" "macOS SDK root should win over Homebrew adb parent"

actual="$(
  PATH="$fake_homebrew/bin:/usr/bin:/bin" \
    HOME="$fake_home" \
    ANDROID_HOME="$tmpdir/not-an-sdk" \
    ANDROID_SDK_ROOT= \
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
    ANDROID_HOME= \
    ANDROID_SDK_ROOT= \
    resolve_emulator_bin
)"
assert_equals "$mac_sdk/emulator/emulator" "$actual" "emulator binary should resolve from discovered macOS SDK"

actual="$(
  PATH="$fake_homebrew/bin:/usr/bin:/bin" \
    HOME="$fake_home" \
    ANDROID_HOME= \
    ANDROID_SDK_ROOT= \
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
  pkill() { printf '%s\n' "$*" > "$tmpdir/emulator-stop-fallback"; }

  echo "Checking diagnostics with unresponsive ADB"
  capture_android_emulator_diagnostics "$tmpdir/offline-diagnostics"
  assert_equals 2 "$(wc -l < "$EMULATOR_TEST_TIMEOUT_LOG" | tr -d ' ')" "Offline ADB commands must be bounded"
  for artifact in android-logcat.txt adb-devices.txt device-getprop.txt package-manager-health.txt emulator.log avd-config.ini avdmanager-create.log; do
    [[ -f "$tmpdir/offline-diagnostics/$artifact" ]] || { echo "Missing diagnostic artifact: $artifact" >&2; exit 1; }
  done

  : > "$EMULATOR_TEST_TIMEOUT_LOG"
  export EMULATOR_TEST_CONNECTED=true
  capture_android_emulator_diagnostics "$tmpdir/connected-diagnostics"
  assert_equals 5 "$(wc -l < "$EMULATOR_TEST_TIMEOUT_LOG" | tr -d ' ')" "Connected device diagnostics must all be bounded"

  : > "$EMULATOR_TEST_TIMEOUT_LOG"
  stop_android_emulator test-owned-avd
  assert_equals 2 "$(wc -l < "$EMULATOR_TEST_TIMEOUT_LOG" | tr -d ' ')" "Emulator stop must bound get-state and emu kill"
  [[ -s "$tmpdir/emulator-stop-fallback" ]] || { echo "Emulator stop did not reach its process fallback" >&2; exit 1; }
)

echo "Android emulator helper tests passed."
