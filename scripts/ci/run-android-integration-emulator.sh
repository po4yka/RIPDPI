#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/ci/android-emulator-helpers.sh
source "$script_dir/android-emulator-helpers.sh"

GRADLE_ABI="-Pripdpi.localNativeAbis=x86_64"
TARGET_FILE="${RUNNER_TEMP:-/tmp}/android-instrumented-target.txt"
TARGET_PACKAGE="com.poyka.ripdpi.integration"
CLASSES=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --classes)
      CLASSES="$2"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

run_target() {
  local filter_arg target_label
  if [[ -n "$CLASSES" ]]; then
    filter_arg="-Pandroid.testInstrumentationRunnerArguments.class=$CLASSES"
    target_label="$CLASSES"
  else
    filter_arg="-Pandroid.testInstrumentationRunnerArguments.package=$TARGET_PACKAGE"
    target_label="$TARGET_PACKAGE"
  fi

  echo "$target_label" | tee "$TARGET_FILE"
  echo "Running Android instrumentation target: $target_label"

  ./gradlew :app:connectedDebugAndroidTest \
    "$GRADLE_ABI" \
    "$filter_arg" \
    -Pandroid.testInstrumentationRunnerArguments.coverage=false
}

if ! run_target; then
  adb_cmd logcat -d > android-logcat.txt || true
  exit 1
fi
