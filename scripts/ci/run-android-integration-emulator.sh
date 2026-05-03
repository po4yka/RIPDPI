#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/ci/android-emulator-helpers.sh
source "$script_dir/android-emulator-helpers.sh"

GRADLE_ABI="-Pripdpi.localNativeAbis=x86_64"
TARGET_FILE="${RUNNER_TEMP:-/tmp}/android-instrumented-target.txt"
TARGET_CLASSES=(
  com.poyka.ripdpi.integration.AutomationLaunchInstrumentedTest
  com.poyka.ripdpi.integration.AutomationPermissionFlowInstrumentedTest
  com.poyka.ripdpi.integration.MainActivityNavigationInstrumentedTest
  com.poyka.ripdpi.integration.MainActivityOnboardingStartupInstrumentedTest
  com.poyka.ripdpi.integration.MainActivityBiometricStartupInstrumentedTest
  com.poyka.ripdpi.integration.MainActivityShellInstrumentedTest
  com.poyka.ripdpi.integration.NativeBridgeInstrumentedTest
  com.poyka.ripdpi.integration.ServiceLifecycleIntegrationTest
)

bash scripts/ci/wait-for-android-package-manager.sh

run_target() {
  local target
  target="$(IFS=,; echo "${TARGET_CLASSES[*]}")"
  echo "$target" | tee "$TARGET_FILE"
  echo "Running Android instrumentation target: $target"

  ./gradlew :app:connectedDebugAndroidTest \
    "$GRADLE_ABI" \
    "-Pandroid.testInstrumentationRunnerArguments.class=$target"
}

if ! run_target; then
  adb_cmd logcat -d > android-logcat.txt || true
  exit 1
fi

./gradlew :app:createDebugAndroidTestCoverageReport "$GRADLE_ABI"
