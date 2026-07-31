#!/usr/bin/env bash
set -euo pipefail

GRADLE_ABI="-Pripdpi.nativeAbisOverride=x86_64"
TARGET_CLASSES="com.poyka.ripdpi.integration.NativeBridgeInstrumentedTest,com.poyka.ripdpi.integration.ServiceLifecycleIntegrationTest"
PT_GRADLE_ARGS=()
if [[ -n "${RIPDPI_PREBUILT_PT_DIR:-}" || -n "${RIPDPI_PREBUILT_PT_MANIFEST_SHA256:-}" ]]; then
  : "${RIPDPI_PREBUILT_PT_DIR:?RIPDPI_PREBUILT_PT_DIR is required with a prebuilt PT digest}"
  : "${RIPDPI_PREBUILT_PT_MANIFEST_SHA256:?RIPDPI_PREBUILT_PT_MANIFEST_SHA256 is required with prebuilt PT assets}"
  PT_GRADLE_ARGS=(
    "-Pripdpi.prebuiltPluggableTransportAssetsDir=$RIPDPI_PREBUILT_PT_DIR"
    "-Pripdpi.prebuiltPluggableTransportAssetsManifestSha256=$RIPDPI_PREBUILT_PT_MANIFEST_SHA256"
  )
fi

bash scripts/ci/wait-for-android-package-manager.sh

./gradlew :app:connectedGithubFullDebugAndroidTest \
  "$GRADLE_ABI" \
  "${PT_GRADLE_ARGS[@]}" \
  "-Pandroid.testInstrumentationRunnerArguments.class=$TARGET_CLASSES"
