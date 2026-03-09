# AGENTS.md -- RIPDPI

## Project

RIPDPI is an Android VPN/proxy application for DPI (Deep Packet Inspection) bypass. It runs a local SOCKS5 proxy using byedpi and tunnels traffic through a VPN service powered by hev-socks5-tunnel.

## Setup

1. Clone with submodules:
   ```bash
   git clone --recurse-submodules <repo-url>
   ```
2. Requirements: JDK 17, Android SDK, NDK 29.0.14206865, CMake 3.22.1
3. NDK and CMake versions are defined in `gradle.properties` -- do not hardcode them elsewhere

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
                     :core:engine (native C + JNI)
                          |
                     :core:data (protobuf + DataStore)
```

### Modules

- **`:app`** -- Jetpack Compose UI with Material 3, navigation, ViewModels
- **`:core:data`** -- App settings via Protobuf (schema: `core/data/src/main/proto/app_settings.proto`) and Jetpack DataStore
- **`:core:engine`** -- Native proxy engine with JNI bridge, wraps byedpi (C99) and hev-socks5-tunnel
- **`:core:service`** -- Android VPN and proxy foreground services

## Native Code

Two native libraries are built separately:

| Library | Build system | Source | Output |
|---------|-------------|--------|--------|
| `libripdpi.so` | CMake | `core/engine/src/main/cpp/` | Built by AGP's externalNativeBuild |
| `libhev-socks5-tunnel.so` | ndk-build | `core/engine/src/main/jni/hev-socks5-tunnel/` (git submodule) | `core/engine/build/generated/jniLibs/` |

- JNI bridge: `core/engine/src/main/cpp/native-lib.c`
- byedpi is an upstream fork -- minimize modifications to `core/engine/src/main/cpp/byedpi/`
- Supported ABIs: armeabi-v7a, arm64-v8a, x86, x86_64
- Never edit `.so` files -- they are compiled from source

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
| `native-jni-development` | Modifying C code, JNI bridge, or CMake build |
| `android-compose-patterns` | Building Compose UI, ViewModels, navigation |
| `gradle-build-system` | Adding dependencies, modules, or convention plugins |
| `protobuf-datastore` | Modifying app settings schema or DataStore persistence |
| `service-lifecycle` | Working with VPN/proxy service start/stop logic |
| `rust-android-ndk` | Building Rust for Android, cargo-ndk, cross-compilation targets |
| `rust-jni-bridge` | Implementing JNI in Rust (jni crate vs UniFFI), type mapping |
| `c-to-rust-migration` | Incremental C-to-Rust migration strategy, FFI interop during transition |
