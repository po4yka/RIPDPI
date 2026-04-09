#!/usr/bin/env bash
set -euo pipefail

bash scripts/ci/wait-for-android-package-manager.sh

./gradlew :baselineprofile:connectedAndroidTest \
  -Pripdpi.localNativeAbis=x86_64 \
  -Pandroid.testInstrumentationRunnerArguments.class=com.poyka.ripdpi.baselineprofile.StartupBenchmark || {
  adb logcat -d > android-logcat.txt || true
  exit 1
}
