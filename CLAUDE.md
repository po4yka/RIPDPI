# CLAUDE.md -- RIPDPI

## Project Overview

Android VPN/proxy app for DPI bypass using a local SOCKS5 proxy (byedpi) and VPN tunnel (hev-socks5-tunnel).

## Module Structure

| Module | Purpose |
|--------|---------|
| `:app` | UI layer: Jetpack Compose + Material 3, navigation, ViewModels |
| `:core:data` | Protobuf schema + DataStore for app settings persistence |
| `:core:engine` | Native C proxy (byedpi) + VPN tunnel (hev-socks5-tunnel) + JNI bridge |
| `:core:service` | Android VPN and proxy foreground services |

## Tech Stack

- Kotlin, Jetpack Compose (BOM 2026.02.01), Material 3
- Android NDK 29.0.14206865, CMake 3.22.1
- Protobuf 4.34.0 (javalite) + DataStore
- Gradle 9.4 with Kotlin DSL, AGP 9.1.0
- JDK 17 (Temurin)

## Build Commands

```bash
./gradlew assembleDebug          # Full build with native code
./gradlew testDebugUnitTest      # All unit tests
./gradlew :core:data:testDebugUnitTest  # Single module tests
./gradlew staticAnalysis         # detekt + ktlint + Android lint
```

Clone with submodules:
```bash
git clone --recurse-submodules <repo-url>
```

## Native Code Rules

- **byedpi** lives in `core/engine/src/main/cpp/byedpi/` -- upstream fork, minimize modifications
- **JNI bridge**: `core/engine/src/main/cpp/native-lib.c` (plus `utils.c`, `main.h`, `utils.h`)
- **hev-socks5-tunnel** is a git submodule at `core/engine/src/main/jni/hev-socks5-tunnel/`
- CMake builds `libripdpi.so` from byedpi sources
- ndk-build builds `libhev-socks5-tunnel.so` from the submodule
- Both output to `core/engine/build/generated/jniLibs/`
- NDK version, CMake version, and ABI filters are in `gradle.properties` (single source of truth)
- Never edit `.so` files directly -- they are built from source
- Supported ABIs: armeabi-v7a, arm64-v8a, x86, x86_64

## Code Conventions

- Convention plugins in `build-logic/convention/` -- use them, don't add raw plugin config to modules
  - `ripdpi.android.application`, `ripdpi.android.library`, `ripdpi.android.compose`
  - `ripdpi.android.native`, `ripdpi.android.protobuf`
  - `ripdpi.android.detekt`, `ripdpi.android.ktlint`, `ripdpi.android.lint`
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

- `android.newDsl=false` in `gradle.properties` is a workaround for protobuf-gradle-plugin 0.9.6 incompatibility with AGP 9's new DSL. Do not remove until the plugin supports AGP 9 natively.
- The `runNdkBuild` task runs before `preBuild` in `:core:engine` -- it builds hev-socks5-tunnel via ndk-build separately from the CMake-based byedpi build.
- Signing config for release builds uses environment variables (`SIGNING_STORE_FILE`, etc.) -- never commit keystores.

## CI/CD

- `ci.yml`: build + unit tests + static analysis on push/PR to main
- `release.yml`: signed release APK on `v*` tags or manual dispatch
