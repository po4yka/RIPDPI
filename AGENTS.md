# AGENTS.md -- RIPDPI

## Project

RIPDPI is an Android VPN/proxy application for DPI (Deep Packet Inspection) bypass. It runs a local SOCKS5 proxy and VPN tunnel through in-repository Rust native modules.

## Setup

1. Requirements: JDK 17, Android SDK, Android NDK 29.0.14206865, stable Rust toolchain with Android targets
2. Native build properties are defined in `gradle.properties` -- do not hardcode NDK version, ABI filters, or SDK levels elsewhere
3. The Android build invokes the `ripdpi.android.rust-native` convention plugin from `:core:engine`, which builds the native workspace under `native/rust/`

## Build & Test

```bash
./gradlew assembleDebug              # Debug build (includes native compilation)
./gradlew assembleRelease             # Release build (requires signing env vars)
./gradlew testDebugUnitTest           # Run all unit tests
./gradlew :core:data:testDebugUnitTest  # Run tests for a single module
./gradlew staticAnalysis              # Run detekt + ktlint + Android lint
```

## Architecture

```
:app (UI/Compose) --> :core:service (VPN/proxy services)
                          |
                     :core:engine (Rust native + JNI)
                          |
                     :core:data (protobuf + DataStore)
                          |
              :core:diagnostics (active/passive diagnostics)
                          |
            :core:diagnostics-data (diagnostics contracts)
```

### Modules

- **`:app`** -- Jetpack Compose UI with Material 3, navigation, ViewModels
- **`:core:data`** -- App settings via Protobuf (schema: `core/data/src/main/proto/app_settings.proto`) and Jetpack DataStore
- **`:core:diagnostics`** -- Active network diagnostics, passive telemetry collection, diagnostics UI logic
- **`:core:diagnostics-data`** -- Protobuf schemas and data contracts for diagnostics
- **`:core:engine`** -- Native proxy and tunnel engine with JNI bridge, built from repo-owned Rust crates
- **`:core:service`** -- Android VPN and proxy foreground services
- **`:quality:detekt-rules`** -- Custom detekt rules (DI guardrails, Hilt ViewModel checks)
- **`:baselineprofile`** -- Baseline profile generation for runtime performance

### Current Diagnostics Surface

- `quick_v1` automatic probing is used for user-triggered recommendations and hidden first-seen-network handover re-checks
- `full_matrix_v1` Automatic Audit is a manual diagnostics workflow with rotating curated target cohorts, confidence/coverage assessment, and winners-first reporting
- Strategy-probe progress is structured: active TCP/QUIC lane, candidate index/total, candidate id, and candidate label are exposed through the native progress contract
- Strategy-probe reports now carry `auditAssessment` and `targetSelection`; export/share summaries include the selected audit cohort and coverage/confidence details
- Automatic probing/audit is unavailable when `Use command line settings` is enabled because those workflows require isolated UI-config strategy trials
- Remembered-network persistence is driven by validated recommendations; full-matrix audit results remain manual-apply only

## Native Code

Two native libraries are built from repo-owned Android adapter crates in the native workspace:

| Library | Build system | Source | Output |
|---------|-------------|--------|--------|
| `libripdpi.so` | Cargo + Android NDK linker via `:core:engine:buildRustNativeLibs` | `native/rust/crates/ripdpi-android/` | `core/engine/build/generated/jniLibs/` |
| `libripdpi-tunnel.so` | Cargo + Android NDK linker via `:core:engine:buildRustNativeLibs` | `native/rust/crates/ripdpi-tunnel-android/` | `core/engine/build/generated/jniLibs/` |

- Kotlin bridge for `libripdpi.so`: `core/engine/src/main/kotlin/com/poyka/ripdpi/core/RipDpiProxy.kt`
- Kotlin bridge for `libripdpi-tunnel.so`: `core/engine/src/main/kotlin/com/poyka/ripdpi/core/Tun2SocksTunnel.kt`
- Supported ABIs: armeabi-v7a, arm64-v8a, x86, x86_64
- Never edit `.so` files -- they are compiled from source
- Local non-release builds default to `ripdpi.localNativeAbisDefault=arm64-v8a`.
- Use `ripdpi.localNativeAbis=x86_64` for emulator-heavy local iteration. CI and release always build the full ABI set.

## Build Logic

Convention plugins live in `build-logic/convention/` and provide shared configuration:
- `ripdpi.android.application`, `ripdpi.android.library`, `ripdpi.android.compose`
- `ripdpi.android.hilt`, `ripdpi.android.serialization`
- `ripdpi.android.native`, `ripdpi.android.rust-native`, `ripdpi.android.protobuf`
- `ripdpi.android.quality`, `ripdpi.android.coverage`, `ripdpi.android.jacoco`
- `ripdpi.android.detekt`, `ripdpi.android.ktlint`, `ripdpi.android.lint`
- `ripdpi.android.roborazzi`
- `ripdpi.diagnostics.catalog`

All dependency versions are in `gradle/libs.versions.toml`.

## CI/CD

- **`ci.yml`** -- Runs on push/PR to main: build, unit tests, static analysis; nightly soak and TUN E2E
- **`codeql.yml`** -- Runs on push/PR to main plus weekly schedule: GitHub Actions CodeQL analysis; Kotlin analysis is currently disabled pending upstream support
- **`release.yml`** -- Runs on `v*` tags: builds signed release APK, creates GitHub Release
- **`mutation-testing.yml`** -- Weekly Rust mutation testing via cargo-mutants

## Code Quality

```bash
./gradlew staticAnalysis   # Runs all checks (detekt, ktlint, Android lint)
```

- detekt config: `config/detekt/detekt.yml`
- Max line length: 120 characters
- SDK targets: compileSdk 36, minSdk 27, targetSdk 35
- **Never extend baselines** (detekt baselines, LoC baselines, lint baselines) to suppress new violations. Always fix the underlying issue -- refactor long files, resolve detekt findings, etc. Baselines exist only for legacy debt; new code must not add to them.

## Agent Skills

Project-specific skills are in `.github/skills/` and are shared across Claude Code and Codex:

| Skill | Use when |
|-------|----------|
| `android-device-debug` | Debugging the app on a device or emulator, capturing logs, reproducing crashes, or investigating runtime issues with ADB |
| `native-jni-development` | Modifying Rust native crates, JNI exports, or native build integration |
| `native-profiling` | Profiling native Rust code on Android or desktop |
| `network-traffic-debug` | Capturing or inspecting SOCKS5, VPN, or tunnel traffic |
| `android-compose-patterns` | Building Compose UI, ViewModels, navigation |
| `jetpack-compose-api` | Compose API internals, correct API usage, recomposition, performance, accessibility |
| `appium-automation-contract` | Choosing automation launch routes/presets and debugging test launch state |
| `appium-test-authoring` | Writing or updating Appium page objects and tests |
| `appium-test-debug` | Debugging flaky or failing Appium tests |
| `gradle-build-system` | Adding dependencies, modules, or convention plugins |
| `dependency-update` | Updating Gradle/Rust dependencies, Renovate config, or version catalogs |
| `ci-workflow-authoring` | Modifying GitHub Actions workflows or CI job wiring |
| `detekt-custom-rules` | Adding or fixing custom detekt rules and DI guardrails |
| `golden-test-management` | Working with snapshot/golden fixtures and blessing workflows |
| `tdd` | Following project-standard red/green/refactor workflow |
| `protobuf-datastore` | Modifying app settings schema or DataStore persistence |
| `service-lifecycle` | Working with VPN/proxy service start/stop logic |
| `release-signing` | Building signed release artifacts and release pipeline changes |
| `rust-android-ndk` | Building Rust for Android, cross-compilation targets, and Gradle jniLibs integration |
| `rust-code-style` | Rust code organization and style in `native/rust/` |
| `rust-crate-architecture` | Creating or restructuring native workspace crates and dependencies |
| `rust-jni-bridge` | Implementing JNI in Rust (jni crate vs UniFFI), type mapping |
| `rust-lint-config` | Updating Clippy, rustfmt, or cargo-deny configuration |
| `local-ci-act` | Running CI workflows locally with act, troubleshooting CI failures |

Treat the table above as an index only. The source of truth for each skill is its own `SKILL.md`.
