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
    lefthook install --force

# ─── Build ────────────────────────────────────────────────────────

# Build debug APK (includes native code)
[group('build')]
build:
    ./gradlew assembleDebug

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
    ./gradlew testDebugUnitTest

# Run unit tests for a single module (e.g., just test-module core:engine)
[group('test')]
test-module mod:
    ./gradlew :{{mod}}:testDebugUnitTest

# Run a single test class (e.g., just test-class core:engine RipDpiProxyPreferencesTest)
[group('test')]
test-class mod class:
    ./gradlew :{{mod}}:testDebugUnitTest --tests "{{class}}"

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

# Verify Roborazzi screenshot baselines
[group('test')]
test-screenshots:
    ./gradlew verifyScreenshots

# Record new Roborazzi screenshot baselines
[group('test')]
[confirm("This overwrites existing screenshot baselines. Continue?")]
record-screenshots:
    ./gradlew recordScreenshots

# ─── Lint ─────────────────────────────────────────────────────────

# Run full Kotlin quality suite (detekt + ktlint + lint)
[group('lint')]
lint:
    ./gradlew staticAnalysis

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
    ./gradlew coverageReport

# Generate Rust LLVM coverage report
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

# Run full local CI mirror (lint + test for both Kotlin and Rust)
[group('ci')]
ci: lint lint-rust test test-rust

# Run GitHub Actions locally via act
[group('ci')]
ci-local:
    bash scripts/ci/act-local.sh
