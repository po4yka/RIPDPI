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

- **`ci.yml`** -- PR/push: `build`, `release-verification`, `native-bloat`, `cargo-deny`, `rust-lint`, `rust-cross-check`, `rust-workspace-tests`, `gradle-static-analysis`, `rust-network-e2e`, `cli-packet-smoke`, `rust-turmoil`, `coverage`, `rust-loom`; Nightly/manual: `rust-criterion-bench`, `android-macrobenchmark`, `rust-native-soak`, `rust-native-load`, `nightly-rust-coverage`, `android-network-e2e`, `linux-tun-e2e`, `linux-tun-soak`
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

Project-specific skills are split across two directories:

- `.github/skills/` -- Android, Kotlin, Gradle, CI, and testing skills (shared across Claude Code and Codex)
- `.claude/skills/` -- Rust native, low-level systems, and diagnostics skills (Claude Code only)

| Skill | Use when |
|-------|----------|
| `android-device-debug` | Debugging the app on a device or emulator, capturing logs, reproducing crashes, or investigating runtime issues with ADB |
| `native-jni-development` | Modifying Rust native crates, JNI exports, or native build integration |
| `native-profiling` | Profiling native Rust code on Android or desktop |
| `network-traffic-debug` | Capturing or inspecting SOCKS5, VPN, or tunnel traffic |
| `android-compose-patterns` | Building Compose UI, ViewModels, navigation |
| `jetpack-compose-api` | Compose API internals, correct API usage, recomposition, performance, accessibility |
| `kotlin-test-patterns` | Writing any new test, reviewing test code, or debugging test failures in app/src/test, app/src/androidTest, or core/*/src/test |
| `appium-automation-contract` | Choosing automation launch routes/presets and debugging test launch state |
| `appium-test-authoring` | Writing or updating Appium page objects and tests |
| `appium-test-debug` | Debugging flaky or failing Appium tests |
| `gradle-build-system` | Adding dependencies, modules, or convention plugins |
| `dependency-update` | Updating Gradle/Rust dependencies, Renovate config, or version catalogs |
| `ci-workflow-authoring` | Modifying GitHub Actions workflows or CI job wiring |
| `compose-performance` | Diagnosing unnecessary recompositions, analyzing Compose compiler stability reports, optimizing LazyColumn/LazyRow scroll performance, deciding between @Stable and @Immutable annotations, reviewing UI model class stability, interpreting compose-metrics and compose-reports output, debugging infinite transition animations on HomeScreen, reducing AdvancedSettingsScreen recomposition scope, or applying derivedStateOf to filter-heavy screens like LogsScreen, DiagnosticsScreen, and HistoryScreen |
| `convention-plugin-development` | Adding a new convention plugin, modifying an existing plugin, changing shared SDK/ABI/profile properties in gradle.properties, debugging Gradle configuration cache issues in build-logic, wiring new AGP variant APIs, or updating the diagnostics catalog pipeline |
| `detekt-custom-rules` | Adding or fixing custom detekt rules and DI guardrails |
| `encrypted-dns` | Adding or modifying encrypted DNS protocols, debugging resolver failures, tuning health scoring, working with bootstrap IPs, investigating DNS tampering diagnostics, or understanding why a DoH/DoT/DNSCrypt/DoQ exchange fails |
| `golden-test-management` | Working with snapshot/golden fixtures and blessing workflows |
| `tdd` | Following project-standard red/green/refactor workflow |
| `protobuf-datastore` | Modifying app settings schema or DataStore persistence |
| `protobuf-schema-evolution` | Adding, removing, or renaming proto fields in AppSettings; managing reserved field numbers; evolving the diagnostics wire contract between Kotlin and Rust; bumping DIAGNOSTICS_ENGINE_SCHEMA_VERSION; writing or updating golden contract tests; ensuring DataStore round-trip safety after schema changes; or reviewing any PR that touches .proto files, EngineContract.kt, or wire.rs |
| `release-changelog` | Preparing a release, bumping version code/name, generating a changelog from conventional commits, writing Play Store whatsnew text, creating a git tag, running the release workflow, reviewing what changed since last release, or drafting GitHub release notes |
| `release-signing` | Building signed release artifacts and release pipeline changes |
| `rust-android-ndk` | Building Rust for Android, cross-compilation targets, and Gradle jniLibs integration |
| `rust-code-style` | Rust code organization and style in `native/rust/` |
| `rust-crate-architecture` | Creating or restructuring native workspace crates and dependencies |
| `rust-jni-bridge` | Implementing JNI in Rust (jni crate vs UniFFI), type mapping |
| `rust-lint-config` | Updating Clippy, rustfmt, or cargo-deny configuration |
| `local-ci-act` | Running CI workflows locally with act, troubleshooting CI failures |
| `mutation-testing` | Running cargo-mutants on the native/rust workspace, interpreting mutation testing results, triaging survived mutants, improving test adequacy, configuring mutants.toml, reviewing mutants-output artifacts, or writing mutation-resistant tests |

Additional Rust/native skills in `.claude/skills/`:

| Skill | Use when |
|-------|----------|
| `cargo-workflows` | Managing the Rust workspace, feature flags, build scripts, Gradle-Cargo integration, or cross-compilation |
| `desync-engine` | Working with DPI desync evasion pipeline, DesyncMode, DesyncGroup, TcpChainStep, UdpChainStep, OffsetExpr, or ActivationFilter |
| `diagnostics-system` | Working with diagnostics scan pipeline, ScanRequest, ScanReport, ProbeTask, ripdpi-monitor, strategy probes, or diagnostics catalog |
| `memory-model` | Understanding memory ordering, writing lock-free code, using Rust atomics, or diagnosing data races on ARM64 Android |
| `play-store-screenshots` | Creating Play Store listing assets, marketing screenshots, or feature graphics |
| `rust-async-internals` | Diagnosing select!/join! pitfalls, blocking-in-async issues, JNI-to-async bridging, or tokio runtime configuration for Android NDK |
| `rust-build-times` | Profiling builds with cargo-timings, configuring sccache, splitting workspaces, or optimizing Android NDK cross-compilation times |
| `rust-debugging` | Debugging Rust native libraries on Android (JNI panics, logcat tracing, tombstones, addr2line), using GDB/LLDB with Rust |
| `rust-profiling` | Profiling Android .so binaries with simpleperf/perfetto, measuring monomorphization bloat, or micro-benchmarking with Criterion |
| `rust-sanitizers-miri` | Running AddressSanitizer or ThreadSanitizer on Rust code, using Miri to detect undefined behaviour in unsafe Rust |
| `rust-security` | Auditing dependencies with cargo-audit, enforcing policies with cargo-deny, or reviewing RUSTSEC advisories |
| `rust-unsafe` | Writing or reviewing unsafe Rust, auditing unsafe blocks, understanding raw pointers, or implementing safe abstractions over FFI |
| `ws-tunnel-telegram` | Working with MTProto WebSocket tunnel for Telegram traffic, ripdpi-ws-tunnel crate, DC IP database, or obfuscated2 classification |

Treat the tables above as an index only. The source of truth for each skill is its own `SKILL.md`.
