#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/ci/android-emulator-helpers.sh
source "$script_dir/android-emulator-helpers.sh"

GRADLE_ABI="-Pripdpi.localNativeAbis=x86_64"
TARGET_FILE="${RUNNER_TEMP:-/tmp}/android-instrumented-target.txt"
TARGET_PACKAGE="com.poyka.ripdpi.integration"
APP_PACKAGE="com.poyka.ripdpi"
TEST_PACKAGE="com.poyka.ripdpi.test"
TEST_RUNNER="com.poyka.ripdpi.HiltTestRunner"
CLASSES=""
APK_DIR="${RIPDPI_ANDROID_TEST_APK_DIR:-}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --classes)
      CLASSES="$2"
      shift 2
      ;;
    --apk-dir)
      APK_DIR="$2"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

find_apk() {
  local pattern="$1"
  find "$APK_DIR" -type f -name "$pattern" | sort | head -n 1
}

run_prebuilt_target() {
  local target_label app_apk test_apk
  app_apk="$(find "$APK_DIR" -type f -name "*.apk" ! -name "*androidTest*.apk" | sort | head -n 1)"
  test_apk="$(find_apk "*androidTest*.apk")"

  if [[ -z "$app_apk" || -z "$test_apk" ]]; then
    echo "::error::Prebuilt app/test APKs not found under $APK_DIR"
    find "$APK_DIR" -type f -name "*.apk" -print || true
    return 1
  fi

  if [[ -n "$CLASSES" ]]; then
    target_label="$CLASSES"
  else
    target_label="$TARGET_PACKAGE"
  fi

  echo "$target_label" | tee "$TARGET_FILE"
  echo "Installing prebuilt APKs:"
  echo "  app: $app_apk"
  echo "  test: $test_apk"
  adb_cmd install -r -d "$app_apk"
  adb_cmd install -r -d "$test_apk"
  adb_cmd shell pm clear "$APP_PACKAGE" >/dev/null || true
  adb_cmd shell pm clear "$TEST_PACKAGE" >/dev/null || true

  echo "Running Android instrumentation target: $target_label"
  if [[ -n "$CLASSES" ]]; then
    adb_cmd shell am instrument -w -r \
      -e clearPackageData true \
      -e coverage false \
      -e class "$CLASSES" \
      "$TEST_PACKAGE/$TEST_RUNNER"
  else
    adb_cmd shell am instrument -w -r \
      -e clearPackageData true \
      -e coverage false \
      -e package "$TARGET_PACKAGE" \
      "$TEST_PACKAGE/$TEST_RUNNER"
  fi
}

run_target() {
  local filter_arg target_label
  if [[ -n "$APK_DIR" ]]; then
    run_prebuilt_target
    return
  fi

  if [[ -n "$CLASSES" ]]; then
    filter_arg="-Pandroid.testInstrumentationRunnerArguments.class=$CLASSES"
    target_label="$CLASSES"
  else
    filter_arg="-Pandroid.testInstrumentationRunnerArguments.package=$TARGET_PACKAGE"
    target_label="$TARGET_PACKAGE"
  fi

  echo "$target_label" | tee "$TARGET_FILE"
  echo "Running Android instrumentation target: $target_label"

  ./gradlew :app:connectedGithubDebugAndroidTest \
    "$GRADLE_ABI" \
    "$filter_arg" \
    -Pandroid.testInstrumentationRunnerArguments.coverage=false
}

if ! run_target; then
  adb_cmd logcat -d > android-logcat.txt || true
  exit 1
fi
