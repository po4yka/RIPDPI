# justfile -- RIPDPI project task runner
# Usage: just --list          Show all recipes
#        just --list --groups Show recipes by category
#        just <recipe>        Run a recipe

set shell := ["bash", "-euo", "pipefail", "-c"]
set dotenv-load

rust_dir := "native/rust"

# Show available recipes
[group('help')]
default:
    @just --list --unsorted

# ─── Setup ────────────────────────────────────────────────────────

# Install git hooks and dev tooling
[group('setup')]
setup:
    npm ci --prefix tools/tasking --ignore-scripts
    lefthook install --force

# Install the exact repository-pinned mdtask and OpenSpec versions
[group('setup')]
task-tools:
    npm ci --prefix tools/tasking --ignore-scripts

# ─── Build ────────────────────────────────────────────────────────

# Build the representative GitHub full debug APK (includes native code)
[group('build')]
build:
    ./gradlew :app:assembleGithubFullDebug

# Build release APK
[group('build')]
build-release:
    ./gradlew :app:assembleRelease

# Build Rust native .so libraries for Android
[group('build')]
build-native:
    ./gradlew :core:engine:buildRustNativeLibs

# Build desktop CLI proxy binary
[group('build')]
build-cli:
    cargo build --manifest-path {{rust_dir}}/Cargo.toml -p ripdpi-cli

# ─── Test ─────────────────────────────────────────────────────────

# Run all Kotlin unit tests
[group('test')]
test:
    ./gradlew testDebugUnitTest -Pripdpi.skipNativeBuild=true

# Run unit tests for a single module (e.g., just test-module core:engine)
[group('test')]
test-module mod:
    ./gradlew :{{mod}}:testDebugUnitTest -Pripdpi.skipNativeBuild=true

# Run a single test class (e.g., just test-class core:engine RipDpiProxyPreferencesTest)
[group('test')]
test-class mod class:
    ./gradlew :{{mod}}:testDebugUnitTest --tests "{{class}}" -Pripdpi.skipNativeBuild=true

# Run all Rust workspace tests
[group('test')]
test-rust:
    cargo nextest run --manifest-path {{rust_dir}}/Cargo.toml --workspace

# Run Rust native load/stress tests (smoke profile)
[group('test')]
test-rust-load:
    RIPDPI_SOAK_PROFILE=smoke bash scripts/ci/run-rust-native-load.sh

# Run Rust deterministic network tests (turmoil)
[group('test')]
test-rust-turmoil:
    bash scripts/ci/run-rust-turmoil-tests.sh

# Produce and validate exact-source loopback PMTUD evidence locally.
# With no argument, writes to a fresh temporary directory.
[group('test')]
test-pmtud-local-evidence output_dir="":
    #!/usr/bin/env bash
    set -euo pipefail
    output_dir="{{output_dir}}"
    if [[ -z "$output_dir" ]]; then
      output_dir="$(mktemp -d "${TMPDIR:-/tmp}/ripdpi-pmtud-evidence.XXXXXX")"
    fi
    bash scripts/ci/run-pmtud-local-evidence.sh --output-dir "$output_dir"
    echo "PMTUD evidence: $output_dir/manifest.json"

# Validate the portfolio, mdtask execution graph, OpenSpec changes, and generated board
[group('test')]
task-check: task-tools
    ./taskctl validate

# List the portfolio with execution progress
[group('run')]
task-list: task-tools
    ./taskctl list

# List backlog/todo tasks whose portfolio blockers are resolved
[group('run')]
task-ready: task-tools
    ./taskctl ready

# Print parent and blocker relationships
[group('run')]
task-graph: task-tools
    ./taskctl graph

# Strictly validate every active OpenSpec change
[group('test')]
openspec-validate: task-tools
    OPENSPEC_TELEMETRY=0 tools/tasking/node_modules/.bin/openspec validate --all --strict --no-interactive

# Verify Roborazzi screenshot baselines
[group('test')]
test-screenshots:
    ./gradlew verifyScreenshots

# Run monkey against an installed app; distinguishes monkey's own self-exit
# from a real app crash by scraping logcat for FATAL EXCEPTION / ANR markers.
# Defaults: package com.poyka.ripdpi, 500 events, whichever device adb picks.
[group('test')]
test-monkey events="500":
    bash scripts/test-monkey.sh -c {{events}}

# End-to-end smoke against the sibling ripdpi-vpn-deploy stack: brings up the
# published-ports molecule scenario, installs the debug APK on the connected
# device/emulator, imports a VLESS REALITY deep-link built from the molecule
# test-secrets fixture, and asserts proxy import via logcat.
# Requires: a built debug APK (run `just build` first), a ready AVD/device, and
# the sibling repo at $HOME/GitHub/ripdpi-vpn-deploy (override via RIPDPI_VPN_DEPLOY_DIR).
[group('test')]
e2e-vpn-deploy:
    bash scripts/e2e-vpn-deploy.sh

# Record new Roborazzi screenshot baselines
[group('test')]
[confirm("This overwrites existing screenshot baselines. Continue?")]
record-screenshots:
    ./gradlew recordScreenshots

# Verify protocol crate SPEC_VERSION.md pins and SPEC.md presence
[group('test')]
verify-spec-versions:
    python3 scripts/ci/verify_spec_versions.py
    bash scripts/ci/verify_spec_md_present.sh

# Run :app instrumented tests on all CI managed devices (GMD)
[group('test')]
test-instrumented:
    ./gradlew :app:ciDevicesGroupGithubFullDebugAndroidTest
    ./gradlew :app:pixel6Api35GoogleGithubSimpleDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.poyka.ripdpi.integration.MainActivityNavigationInstrumentedTest#simpleHomeWiresDiagnosticStartCancelAndShareThroughMainViewModel

# ─── Lint ─────────────────────────────────────────────────────────

# Run full Kotlin quality suite (detekt + ktlint + lint)
[group('lint')]
lint:
    ./gradlew staticAnalysis -Pripdpi.skipNativeBuild=true

# Run full Rust quality suite (fmt + clippy + cargo-deny)
[group('lint')]
lint-rust:
    bash scripts/ci/run-rust-native-checks.sh

# Auto-format all Kotlin and Rust code
[group('lint')]
fmt:
    #!/usr/bin/env bash
    set -euo pipefail
    echo "Formatting Kotlin..."
    ktlint --format "**/*.kt" "**/*.kts" || true
    echo "Formatting Rust..."
    cargo fmt --manifest-path {{rust_dir}}/Cargo.toml --all

# Check formatting without modifying files
[group('lint')]
fmt-check:
    #!/usr/bin/env bash
    set -euo pipefail
    echo "Checking Kotlin formatting..."
    ktlint "**/*.kt" "**/*.kts"
    echo "Checking Rust formatting..."
    cargo fmt --manifest-path {{rust_dir}}/Cargo.toml --all --check

# Regenerate the module dependency graph (docs/architecture/MODULE_GRAPH.md)
[group('lint')]
module-graph:
    ./gradlew createModuleGraph -Pripdpi.skipNativeBuild=true

# ─── Run ──────────────────────────────────────────────────────────

# Run desktop CLI proxy (e.g., just run-cli, just run-cli 8080 2)
[group('run')]
run-cli port="1080" log="1":
    cargo run --manifest-path {{rust_dir}}/Cargo.toml -p ripdpi-cli -- -p {{port}} -x {{log}}

# Run CLI with debug logging
[group('run')]
run-cli-debug port="1080":
    RUST_LOG=debug cargo run --manifest-path {{rust_dir}}/Cargo.toml -p ripdpi-cli -- -p {{port}}

# ─── Coverage ─────────────────────────────────────────────────────

# Generate Kotlin JaCoCo coverage report
[group('coverage')]
coverage:
    ./gradlew coverageReport -Pripdpi.skipNativeBuild=true

# Generate the CI-scoped Rust LLVM coverage report
[group('coverage')]
coverage-rust:
    bash scripts/ci/run-rust-coverage.sh

# ─── Bench ───────────────────────────────────────────────────

# Run Rust criterion benchmarks locally
[group('bench')]
bench-rust:
    cargo bench --manifest-path {{rust_dir}}/Cargo.toml --package ripdpi-bench

# Run Rust criterion benchmarks and save as local baseline
[group('bench')]
bench-rust-save:
    cargo bench --manifest-path {{rust_dir}}/Cargo.toml --package ripdpi-bench -- --save-baseline local

# Compare Rust benchmarks against saved local baseline
[group('bench')]
bench-rust-compare:
    cargo bench --manifest-path {{rust_dir}}/Cargo.toml --package ripdpi-bench -- --baseline local

# Bless new Rust benchmark baselines for CI
[group('bench')]
bench-rust-bless:
    python3 scripts/ci/check-criterion-regressions.py \
      --criterion-dir {{rust_dir}}/target/criterion \
      --dump-current > scripts/ci/rust-bench-baseline.json

# Run Android macrobenchmarks (requires connected device/emulator)
[group('bench')]
bench-android:
    ./gradlew :baselineprofile:connectedAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.class=com.poyka.ripdpi.baselineprofile.StartupBenchmark

# Bless new macrobenchmark baselines for CI
[group('bench')]
bench-android-bless:
    python3 scripts/ci/check-macrobenchmark-regressions.py \
      --results-dir baselineprofile/build/outputs/connected_android_test_additional_output \
      --dump-current > scripts/ci/macrobenchmark-baseline.json

# ─── CI ───────────────────────────────────────────────────────────

# Run the full secret-free local release readiness mirror. The receipt records
# that this host-ABI check is not signing or hosted exact-SHA CI evidence.
[group('release')]
release-preflight tag window_start started_at:
    python3 scripts/ci/release_preflight.py \
      --release-tag "{{tag}}" \
      --window-start-sha "{{window_start}}" \
      --window-started-at "{{started_at}}" \
      --report build/reports/release/preflight.json

# Run full local CI mirror (lint + test for both Kotlin and Rust)
[group('ci')]
ci: task-check lint lint-rust test test-rust

# Run GitHub Actions locally via act
[group('ci')]
ci-local:
    bash scripts/ci/act-local.sh
