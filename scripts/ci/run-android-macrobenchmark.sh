#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/ci/android-emulator-helpers.sh
source "$script_dir/android-emulator-helpers.sh"

bash scripts/ci/wait-for-android-package-manager.sh

./gradlew :baselineprofile:connectedAndroidTest \
  -Pripdpi.localNativeAbis=x86_64 \
  -Pandroid.testInstrumentationRunnerArguments.class=com.poyka.ripdpi.baselineprofile.StartupBenchmark || {
  adb_cmd logcat -d > android-logcat.txt || true
  exit 1
}
