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

echo "Android emulator helper tests passed."
