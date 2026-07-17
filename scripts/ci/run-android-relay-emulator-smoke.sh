#!/usr/bin/env bash
set -euo pipefail

GRADLE_ABI="-Pripdpi.nativeAbisOverride=x86_64"
TARGET_CLASSES="com.poyka.ripdpi.integration.NativeBridgeInstrumentedTest,com.poyka.ripdpi.integration.ServiceLifecycleIntegrationTest"

bash scripts/ci/wait-for-android-package-manager.sh

./gradlew :app:connectedGithubFullDebugAndroidTest \
  "$GRADLE_ABI" \
  "-Pandroid.testInstrumentationRunnerArguments.class=$TARGET_CLASSES"
