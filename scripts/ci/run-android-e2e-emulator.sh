#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/ci/android-emulator-helpers.sh
source "$script_dir/android-emulator-helpers.sh"

# Arguments: $1=event_name $2=run_maestro_smoke $3=run_appium_smoke

event_name="${1:-}"
run_maestro="${2:-false}"
run_appium="${3:-false}"

GRADLE_ABI="-Pripdpi.localNativeAbis=x86_64"
TARGET_FILE="${RUNNER_TEMP:-/tmp}/android-instrumented-target.txt"
RESULTS_DIR="app/build/outputs/androidTest-results/connected/debug/flavors/githubFull"
RESULT_VALIDATOR="$script_dir/validate_android_junit_results.py"
PREFLIGHT_CLASS="com.poyka.ripdpi.e2e.EnvironmentPreflightE2ETest"
PREFLIGHT_TEST="$PREFLIGHT_CLASS#environmentSupportsFixtureReachabilityAndVpnConsent"
STARTUP_WINDOW_TEST="com.poyka.ripdpi.e2e.VpnStartupWindowE2ETest#vpnStartupWindowHoldsDnsPacketUntilNativeReady"

bash scripts/ci/wait-for-android-package-manager.sh

run_target() {
  local target="$1"
  local required_test="$2"
  local expected_count="$3"
  local forbid_skips="$4"
  shift 4
  echo "$target" | tee "$TARGET_FILE"
  echo "Running Android instrumentation target: $target"

  if ! rm -rf -- "$RESULTS_DIR"; then
    echo "Failed to clear Android JUnit results: $RESULTS_DIR" >&2
    return 1
  fi
  if ! ./gradlew :app:connectedGithubFullDebugAndroidTest \
    "$GRADLE_ABI" \
    "$@" \
    -Pandroid.testInstrumentationRunnerArguments.coverage=false; then
    return 1
  fi

  local validator_args=(
    "$RESULTS_DIR"
    --require-test "$required_test"
  )
  if [ -n "$expected_count" ]; then
    validator_args+=(--expected-count "$expected_count")
  fi
  if [ "$forbid_skips" = "true" ]; then
    validator_args+=(--forbid-skips)
  fi
  python3 "$RESULT_VALIDATOR" "${validator_args[@]}"
}

if ! run_target \
  "$PREFLIGHT_CLASS" \
  "$PREFLIGHT_TEST" \
  "" \
  true \
  "-Pandroid.testInstrumentationRunnerArguments.class=$PREFLIGHT_CLASS" \
  -Pandroid.testInstrumentationRunnerArguments.ripdpi.fixtureControlHost=10.0.2.2 \
  -Pandroid.testInstrumentationRunnerArguments.ripdpi.fixtureControlPort=46090; then
  adb_cmd logcat -d > android-logcat.txt || true
  exit 1
fi

if ! run_target \
  "com.poyka.ripdpi.e2e" \
  "$STARTUP_WINDOW_TEST" \
  1 \
  false \
  -Pandroid.testInstrumentationRunnerArguments.package=com.poyka.ripdpi.e2e \
  "-Pandroid.testInstrumentationRunnerArguments.notClass=$PREFLIGHT_CLASS" \
  -Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.poyka.ripdpi.e2e.RawPacketValidationOnly \
  -Pandroid.testInstrumentationRunnerArguments.ripdpi.fixtureControlHost=10.0.2.2 \
  -Pandroid.testInstrumentationRunnerArguments.ripdpi.fixtureControlPort=46090; then
  adb_cmd logcat -d > android-logcat.txt || true
  exit 1
fi

if [ "$event_name" = "workflow_dispatch" ] && [ "$run_maestro" = "true" ]; then
  mkdir -p "$RUNNER_TEMP/maestro"
  curl -Ls https://get.maestro.mobile.dev | bash
  export PATH="$PATH:$HOME/.maestro/bin"
  maestro --version
  bash scripts/ci/run-maestro-smoke.sh \
    2>&1 | tee "$RUNNER_TEMP/maestro/maestro-smoke.log"
fi

if [ "$event_name" = "workflow_dispatch" ] && [ "$run_appium" = "true" ]; then
  npm install -g appium
  appium driver install uiautomator2
  pip install -r appium/requirements.txt
  timeout 900 bash scripts/ci/run-appium-smoke.sh \
    2>&1 | tee "$RUNNER_TEMP/appium-smoke.log"
fi
