# CLAUDE.md -- RIPDPI

## Project Overview

Android VPN/proxy app for network optimization using in-repository Rust native modules for the local SOCKS5 proxy and VPN tunnel.

## Module Structure

| Module | Purpose |
|--------|---------|
| `:app` | UI layer: Jetpack Compose + Material 3, navigation, ViewModels |
| `:core:data` | Protobuf schema + DataStore for app settings persistence |
| `:core:diagnostics` | Active network diagnostics, passive telemetry, diagnostics UI logic |
| `:core:diagnostics-data` | Protobuf schemas and data contracts for diagnostics |
| `:core:engine` | Rust native proxy + VPN tunnel + JNI bridge |
| `:core:service` | Android VPN and proxy foreground services |
| `:quality:detekt-rules` | Custom detekt rules (DI guardrails, Hilt checks) |
| `:baselineprofile` | Baseline profile generation for runtime performance |

## Current Diagnostics Surface

- `quick_v1` automatic probing drives both user-visible recommendations and hidden first-seen-network handover re-checks.
- `full_matrix_v1` Automatic Audit is a manual full-matrix strategy probe with rotating curated target cohorts, confidence/coverage scoring, and winners-first report presentation.
- Native diagnostics progress is structured: strategy-probe runs emit active TCP/QUIC lane plus candidate index/total and label.
- Native strategy-probe reports now carry `auditAssessment` and `targetSelection`, and Kotlin export/share layers surface those fields.
- Automatic probing/audit is intentionally blocked when `Use command line settings` is enabled; the UI routes users to Advanced Settings instead of trying to run with raw CLI args.
- Recommendation-derived metadata and remembered-policy persistence now fail closed when the decoded config does not match the reported winning candidates.

## Tech Stack

- Kotlin, Jetpack Compose (BOM 2026.03.00), Material 3
- Android NDK 29.0.14206865, Rust toolchain 1.94.0
- Protobuf 4.34.1 (javalite) + DataStore
- Gradle 9.4.1 with Kotlin DSL, AGP 9.1.0
- JDK 17 (Temurin)

## Build Commands

```bash
./gradlew assembleDebug          # Full build with native code
./gradlew testDebugUnitTest      # All unit tests
./gradlew :core:data:testDebugUnitTest  # Single module tests
./gradlew staticAnalysis         # detekt + ktlint + Android lint
```

### Desktop CLI (native proxy without Android)

```bash
cd native/rust
cargo build -p ripdpi-cli                    # Build the CLI binary
cargo run -p ripdpi-cli -- -p 1080 -x 1      # Run proxy on port 1080, info logging
cargo run -p ripdpi-cli -- -h                 # Show all CLI flags
RUST_LOG=debug cargo run -p ripdpi-cli       # Override log filter via env
cargo run -p ripdpi-cli -- -p 1080 --window-clamp 2  # TCP window clamp for DPI evasion
cargo run -p ripdpi-cli -- -p 1080 --strategy-evolution  # Enable adaptive combo exploration
```

## Native Code Rules

- `ripdpi` CLI binary is built from `native/rust/crates/ripdpi-cli` (macOS/Linux, no Android deps)
- `libripdpi.so` is built from `native/rust/crates/ripdpi-android`
- `libripdpi-tunnel.so` is built from `native/rust/crates/ripdpi-tunnel-android`
- `:core:engine:buildRustNativeLibs` builds both libraries from the `native/rust` workspace into `core/engine/build/generated/jniLibs/`
- NDK version and ABI filters are in `gradle.properties` (single source of truth)
- Never edit `.so` files directly -- they are built from source
- Supported ABIs: armeabi-v7a, arm64-v8a, x86, x86_64
- Local non-release builds default to `ripdpi.localNativeAbisDefault=arm64-v8a`
- `ripdpi.localNativeAbis=x86_64` is the fast path for emulator-heavy local iteration
- `ripdpi.localNativeAbis` remains a local debug-only override. Do not use it in CI or release workflows.

## Code Conventions

- Convention plugins in `build-logic/convention/` -- use them, don't add raw plugin config to modules
  - `ripdpi.android.application`, `ripdpi.android.library`, `ripdpi.android.compose`
  - `ripdpi.android.hilt`, `ripdpi.android.serialization`
  - `ripdpi.android.native`, `ripdpi.android.rust-native`, `ripdpi.android.protobuf`
  - `ripdpi.android.quality`, `ripdpi.android.coverage`, `ripdpi.android.jacoco`
  - `ripdpi.android.detekt`, `ripdpi.android.ktlint`, `ripdpi.android.lint`
  - `ripdpi.android.roborazzi`
  - `ripdpi.diagnostics.catalog`
- Version catalog at `gradle/libs.versions.toml` -- all dependency versions there
- Max line length: 120 chars
- detekt config: `config/detekt/detekt.yml`
- Protobuf schema: `core/data/src/main/proto/app_settings.proto`

## SDK Versions

| Property | Value |
|----------|-------|
| compileSdk | 36 |
| minSdk | 27 |
| targetSdk | 35 |

## Gotchas

- `ripdpi.android.protobuf` is a repo-owned lite-codegen task that runs `protoc` directly against `src/main/proto` and writes generated sources under `build/generated/source/protoLite/main/java`.
- `:core:engine:buildRustNativeLibs` runs before `preBuild`, uses Cargo incremental outputs per ABI, and only copies changed `.so` files into Gradle-managed `jniLibs`.
- Signing config for release builds uses environment variables (`SIGNING_STORE_FILE`, etc.) -- never commit keystores.

## Git Hooks

Pre-commit hooks via [lefthook](https://github.com/evilmartians/lefthook). Install once:

```bash
brew install lefthook && lefthook install
```

Hooks auto-install on Android Studio project sync (`prepareKotlinBuildScriptModel`).

**Pre-commit** (parallel, staged files only):
- `ktlint --format` on `*.kt`/`*.kts` -- auto-fixes and re-stages
- `cargo fmt --check` on `*.rs`
- Secret pattern detection
- Large file prevention (>512KB)

**Commit-msg**: Conventional Commits format enforced.

Skip for emergencies: `LEFTHOOK=0 git commit ...`

Local overrides: create `.lefthook-local.yml` (gitignored).

## Task Runner

Project commands are unified in a [`justfile`](https://github.com/casey/just). Install: `brew install just`

```bash
just              # List all recipes
just build        # Build debug APK
just test         # All Kotlin unit tests
just test-module core:engine                    # Single module
just test-class core:engine MyTest              # Single class
just test-rust    # All Rust tests
just lint         # Full Kotlin static analysis
just lint-rust    # Full Rust checks (fmt + clippy + deny)
just fmt          # Auto-format Kotlin + Rust
just run-cli      # Start desktop proxy on :1080
just ci           # Full local CI mirror
```

Run `just --list --groups` to see recipes organized by category.

## CI/CD

- `ci.yml`: PR lanes (build, static-analysis, rust-network-e2e, android-network-e2e, coverage, rust-turmoil, rust-loom, cli-packet-smoke); nightly/manual lanes (rust-native-soak, rust-native-load, linux-tun-e2e, linux-tun-soak, nightly-rust-coverage)
- `release.yml`: signed release APK on `v*` tags or manual dispatch
- `mutation-testing.yml`: weekly Rust mutation testing via cargo-mutants

## Testing

- TDD is the default workflow for features, bugfixes, and refactors. Use the `tdd` skill (`.github/skills/tdd/SKILL.md`).
- Test doubles are hand-written `Fake*` classes in `TestDoubles.kt`. No mocking frameworks (MockK, Mockito).
- Fault injection uses `FaultQueue<T>` + `FaultSpec` from `core/engine/src/main/kotlin/com/poyka/ripdpi/core/testing/FaultModel.kt`.
- Kotlin test names use backticks: `` `proxy start propagates exception` ``. Rust uses `snake_case`.
- Prefer single-module test commands for fast iteration: `./gradlew :core:engine:testDebugUnitTest --tests "ClassName"`.
- `./gradlew staticAnalysis` applies to test code -- run it before committing.
- Golden contracts are read-only by default. Bless with `RIPDPI_BLESS_GOLDENS=1`, review diffs, explain changes in the commit message.
- Soak tests (`RIPDPI_RUN_SOAK=1`) cover endurance: restart cycling, sustained traffic, fault recovery.
- Load tests (`RIPDPI_RUN_LOAD=1`) cover high-concurrency: ramp-up, burst spikes, saturation. Run via `just test-rust-load`.
- Full test stack docs: `docs/testing.md`.

## Project Skills

Project-specific skills live in `.github/skills/`:

- Android/UI: `android-compose-patterns`, `jetpack-compose-api`
- Android runtime/debugging: `android-device-debug`, `service-lifecycle`
- Appium automation: `appium-automation-contract`, `appium-test-authoring`, `appium-test-debug`
- Build/release/CI: `gradle-build-system`, `dependency-update`, `ci-workflow-authoring`, `local-ci-act`, `release-signing`, `detekt-custom-rules`
- Data and contracts: `protobuf-datastore`, `golden-test-management`, `tdd`
- Native/Rust: `native-jni-development`, `native-profiling`, `network-traffic-debug`, `rust-android-ndk`, `rust-code-style`, `rust-crate-architecture`, `rust-jni-bridge`, `rust-lint-config`

Use the skill body, not this summary, as the authoritative workflow for a given domain.
