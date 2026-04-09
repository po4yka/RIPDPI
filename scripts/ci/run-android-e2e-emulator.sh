#!/usr/bin/env bash
set -euo pipefail

# Called from the reactivecircus/android-emulator-runner step.
# Arguments: $1=event_name $2=run_maestro_smoke $3=run_appium_smoke

event_name="${1:-}"
run_maestro="${2:-false}"
run_appium="${3:-false}"

GRADLE_ABI="-Pripdpi.localNativeAbis=x86_64"

bash scripts/ci/wait-for-android-package-manager.sh

run_connected_tests() {
  ./gradlew :app:connectedDebugAndroidTest \
    "$GRADLE_ABI" \
    -Pandroid.testInstrumentationRunnerArguments.package=com.poyka.ripdpi.integration

  ./gradlew :app:connectedDebugAndroidTest \
    "$GRADLE_ABI" \
    -Pandroid.testInstrumentationRunnerArguments.package=com.poyka.ripdpi.e2e \
    -Pandroid.testInstrumentationRunnerArguments.ripdpi.fixtureControlHost=10.0.2.2 \
    -Pandroid.testInstrumentationRunnerArguments.ripdpi.fixtureControlPort=46090
}

succeeded=false
for attempt in 1 2 3; do
  if run_connected_tests; then
    succeeded=true
    break
  fi

  adb logcat -d > android-logcat.txt || true
  if [ "$attempt" -lt 3 ]; then
    echo "::warning::Attempt $attempt failed, retrying..."
    sleep 10
  fi
done

if [ "$succeeded" != "true" ]; then
  exit 1
fi

./gradlew :app:createDebugAndroidTestCoverageReport "$GRADLE_ABI"

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
