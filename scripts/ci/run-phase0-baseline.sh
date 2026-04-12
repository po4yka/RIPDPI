#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
artifact_dir="${1:-$repo_root/build/phase0-baseline}"
load_profile="${RIPDPI_SOAK_PROFILE:-smoke}"

mkdir -p "$artifact_dir"

wrapper_report="$artifact_dir/engine-wrapper-baseline.json"
debug_size_report="$artifact_dir/native-size-debug-report.json"
debug_size_md="$artifact_dir/native-size-debug-report.md"
release_size_report="$artifact_dir/native-size-release-report.json"
bloat_report="$artifact_dir/native-bloat-report.json"
bloat_md="$artifact_dir/native-bloat-report.md"
baseline_json="$artifact_dir/phase0-baseline.json"
baseline_md="$artifact_dir/phase0-baseline.md"

echo "==> Criterion benchmarks"
(
  cd "$repo_root/native/rust"
  cargo bench --package ripdpi-bench 2>&1 | tee "$artifact_dir/criterion-output.txt"
)
python3 "$repo_root/scripts/ci/check-criterion-regressions.py" \
  --criterion-dir "$repo_root/native/rust/target/criterion" \
  --baseline "$repo_root/scripts/ci/rust-bench-baseline.json" \
  --warn-only \
  --markdown-output "$artifact_dir/criterion-summary.md"

echo "==> Native load baseline"
RIPDPI_SOAK_PROFILE="$load_profile" \
  bash "$repo_root/scripts/ci/run-rust-native-load.sh" "$artifact_dir/native-load" \
  2>&1 | tee "$artifact_dir/native-load.log"

echo "==> Engine wrapper baseline"
(
  cd "$repo_root"
  RIPDPI_BASELINE_OUTPUT="$wrapper_report" \
    ./gradlew \
    :core:engine:testDebugUnitTest \
    --tests 'com.poyka.ripdpi.core.NativeWrapperBaselineCaptureTest' \
    -x :core:engine:buildRustNativeLibs \
    -x :core:engine:buildRustCloudflareOrigin \
    -x :core:engine:buildRustNaiveProxy \
    -x :core:engine:buildRustRootHelper
)

echo "==> Android builds for packaged native size baselines"
(
  cd "$repo_root"
  ./gradlew :app:assembleDebug :app:assembleRelease
)

python3 "$repo_root/scripts/ci/verify_native_sizes.py" \
  --report-json "$debug_size_report" \
  --report-md "$debug_size_md"

python3 "$repo_root/scripts/ci/verify_native_sizes.py" \
  --lib-dir "app/build/intermediates/merged_native_libs/release/mergeReleaseNativeLibs/out/lib" \
  --dump-current > "$release_size_report"

python3 "$repo_root/scripts/ci/verify_native_bloat.py" \
  --report-json "$bloat_report" \
  --report-md "$bloat_md"

echo "==> Aggregating Phase 0 baseline snapshot"
python3 "$repo_root/scripts/ci/build_phase0_baseline.py" \
  --criterion-dir "native/rust/target/criterion" \
  --load-report "$artifact_dir/native-load/proxy_connection_resource_budget.results.json" \
  --wrapper-report "$wrapper_report" \
  --debug-size-report "$debug_size_report" \
  --release-size-report "$release_size_report" \
  --bloat-report "$bloat_report" \
  --output-json "$baseline_json" \
  --output-md "$baseline_md"

echo "Phase 0 baseline snapshot written to $artifact_dir"
