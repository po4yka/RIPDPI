# AGENTS.md -- RIPDPI

## Project

RIPDPI is an Android VPN/proxy application for DPI (Deep Packet Inspection) bypass. It runs a local SOCKS5 proxy and VPN tunnel through in-repository Rust native modules derived from ByeDPI and hev-socks5-tunnel.

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
```

### Modules

- **`:app`** -- Jetpack Compose UI with Material 3, navigation, ViewModels
- **`:core:data`** -- App settings via Protobuf (schema: `core/data/src/main/proto/app_settings.proto`) and Jetpack DataStore
- **`:core:engine`** -- Native proxy and tunnel engine with JNI bridge, built from vendored Rust crates
- **`:core:service`** -- Android VPN and proxy foreground services

## Native Code

Two native libraries are built from repo-owned Android adapter crates in the native workspace:

| Library | Build system | Source | Output |
|---------|-------------|--------|--------|
| `libripdpi.so` | Cargo + Android NDK linker via `:core:engine:buildRustNativeLibs` | `native/rust/crates/ripdpi-android/` | `core/engine/build/generated/jniLibs/` |
| `libhev-socks5-tunnel.so` | Cargo + Android NDK linker via `:core:engine:buildRustNativeLibs` | `native/rust/crates/hs5t-android/` | `core/engine/build/generated/jniLibs/` |

- Kotlin bridge for `libripdpi.so`: `core/engine/src/main/java/com/poyka/ripdpi/core/RipDpiProxy.kt`
- Kotlin bridge for `libhev-socks5-tunnel.so`: `core/engine/src/main/java/com/poyka/ripdpi/core/Tun2SocksTunnel.kt`
- Supported ABIs: armeabi-v7a, arm64-v8a, x86, x86_64
- Never edit `.so` files -- they are compiled from source
- Use `ripdpi.localNativeAbis=arm64-v8a` only for local debug-only native iteration. CI and release always build the full ABI set.

## Build Logic

Convention plugins live in `build-logic/convention/` and provide shared configuration:
- `ripdpi.android.application`, `ripdpi.android.library`, `ripdpi.android.compose`
- `ripdpi.android.native`, `ripdpi.android.protobuf`
- `ripdpi.android.detekt`, `ripdpi.android.ktlint`, `ripdpi.android.lint`

All dependency versions are in `gradle/libs.versions.toml`.

## CI/CD

- **`ci.yml`** -- Runs on push/PR to main: build, unit tests, static analysis
- **`release.yml`** -- Runs on `v*` tags: builds signed release APK, creates GitHub Release

## Code Quality

```bash
./gradlew staticAnalysis   # Runs all checks (detekt, ktlint, Android lint)
```

- detekt config: `config/detekt/detekt.yml`
- Max line length: 120 characters
- SDK targets: compileSdk 36, minSdk 27, targetSdk 35

## Agent Skills

Project-specific skills are in `.github/skills/`:

| Skill | Use when |
|-------|----------|
| `native-jni-development` | Modifying Rust native crates, JNI exports, or native build integration |
| `android-compose-patterns` | Building Compose UI, ViewModels, navigation |
| `gradle-build-system` | Adding dependencies, modules, or convention plugins |
| `protobuf-datastore` | Modifying app settings schema or DataStore persistence |
| `service-lifecycle` | Working with VPN/proxy service start/stop logic |
| `rust-android-ndk` | Building Rust for Android, cargo-ndk, cross-compilation targets |
| `rust-jni-bridge` | Implementing JNI in Rust (jni crate vs UniFFI), type mapping |
