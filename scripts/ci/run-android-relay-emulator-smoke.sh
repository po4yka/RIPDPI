#!/usr/bin/env bash
set -euo pipefail

GRADLE_ABI="-Pripdpi.localNativeAbis=x86_64"
TARGET_CLASSES="com.poyka.ripdpi.integration.NativeBridgeInstrumentedTest,com.poyka.ripdpi.integration.ServiceLifecycleIntegrationTest"

bash scripts/ci/wait-for-android-package-manager.sh

./gradlew :app:connectedDebugAndroidTest \
  "$GRADLE_ABI" \
  "-Pandroid.testInstrumentationRunnerArguments.class=$TARGET_CLASSES"
