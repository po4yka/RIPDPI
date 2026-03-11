#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
workspace_manifest="$repo_root/native/rust/Cargo.toml"

export RIPDPI_BLESS_GOLDENS=1
export RIPDPI_GOLDEN_ARTIFACT_DIR="${RIPDPI_GOLDEN_ARTIFACT_DIR:-$repo_root/native/rust/target/golden-diffs}"

echo "==> bless Rust telemetry/logging goldens"
cargo test --manifest-path "$workspace_manifest" -p android-support
cargo test --manifest-path "$workspace_manifest" -p ripdpi-android
cargo test --manifest-path "$workspace_manifest" -p hs5t-android
cargo test --manifest-path "$workspace_manifest" -p ripdpi-monitor

echo "==> bless JVM telemetry/logging goldens"
(cd "$repo_root" && ./gradlew \
  :core:engine:testDebugUnitTest \
  --tests com.poyka.ripdpi.core.NativeTelemetryGoldenTest \
  :core:service:testDebugUnitTest \
  --tests com.poyka.ripdpi.services.ServiceTelemetryGoldenTest \
  :core:diagnostics:testDebugUnitTest \
  --tests com.poyka.ripdpi.diagnostics.DiagnosticsManagerTest)

echo "==> sync Android instrumentation goldens from JVM fixtures"
cp \
  "$repo_root/core/engine/src/test/resources/golden/proxy_running_first_poll.json" \
  "$repo_root/app/src/androidTest/assets/golden/proxy_running_first_poll.json"
cp \
  "$repo_root/core/engine/src/test/resources/golden/tunnel_ready.json" \
  "$repo_root/app/src/androidTest/assets/golden/tunnel_ready.json"

echo "Blessed telemetry/logging goldens."
