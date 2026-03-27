---
name: rust-android-ndk
description: Use when building Rust code for Android, configuring cross-compilation targets, or integrating Rust `.so` output into Gradle jniLibs
---

# Rust Android NDK

## Overview

RIPDPI builds Rust libraries as Android `.so` files with plain `cargo build` plus the Android NDK linker toolchain. The project-standard entrypoint is `:core:engine:buildRustNativeLibs`, which is registered by the `ripdpi.android.rust-native` convention plugin and builds the `native/rust` workspace before `preBuild`.

## Toolchain Setup

```bash
# Install Rust Android targets
rustup target add \
    aarch64-linux-android \
    armv7-linux-androideabi \
    x86_64-linux-android \
    i686-linux-android

```

Set `sdk.dir` in `local.properties` or `ANDROID_SDK_ROOT`, and make sure the matching NDK exists at `$ANDROID_SDK_ROOT/ndk/29.0.14206865`.

## Target Mapping

| Android ABI | Rust target | Min API |
|-------------|-------------|---------|
| `arm64-v8a` | `aarch64-linux-android` | 27 |
| `armeabi-v7a` | `armv7-linux-androideabi` | 27 |
| `x86_64` | `x86_64-linux-android` | 27 |
| `x86` | `i686-linux-android` | 27 |

RIPDPI supports all four ABIs with minSdk 27.

## Current Project Build

```bash
./gradlew :core:engine:buildRustNativeLibs
```

This creates `generated/jniLibs/<abi>/libripdpi.so` and `generated/jniLibs/<abi>/libripdpi-tunnel.so`.

`libripdpi.so` includes both the proxy runtime bridge and the diagnostics monitor (`ripdpi-monitor`). Native diagnostics changes use the same Android build path as proxy changes.

### Gradle Task Integration

The task sets the target linker for each ABI explicitly and builds:
- `native/rust` package `ripdpi-android`
- `native/rust` package `ripdpi-tunnel-android`

The ABI set comes from `gradle.properties`. Local non-release builds default to `ripdpi.localNativeAbisDefault=arm64-v8a`, and `ripdpi.localNativeAbis=x86_64` is the fast path for emulator-heavy iteration. CI and release builds must keep the full ABI set.

## Cargo.toml Setup

```toml
[lib]
crate-type = ["cdylib"]  # Required: produces .so for Android

[dependencies]
jni = "0.22"              # JNI bindings
log = "0.4"               # Logging
android-support = { workspace = true }  # Android logging + JNI support helpers

[profile.android-jni]
inherits = "release"
opt-level = "z"           # Optimize for size
panic = "unwind"

[profile.android-jni-dev]
inherits = "dev"
opt-level = 1             # Faster local iteration with symbols
panic = "unwind"
```

`crate-type = ["cdylib"]` is mandatory because Android loads the libraries through `System.loadLibrary()`. RIPDPI's Gradle task selects the Cargo profile from `gradle.properties`: release-like builds use `ripdpi.nativeCargoProfile`, while local non-release builds can fall back to `ripdpi.localNativeCargoProfileDefault`.

## 16KB Page Size Compatibility

Android 15+ requires 16KB page alignment. Add to `.cargo/config.toml`:

```toml
[target.aarch64-linux-android]
rustflags = ["-C", "link-arg=-Wl,-z,max-page-size=16384"]

[target.armv7-linux-androideabi]
rustflags = ["-C", "link-arg=-Wl,-z,max-page-size=16384"]

[target.x86_64-linux-android]
rustflags = ["-C", "link-arg=-Wl,-z,max-page-size=16384"]

[target.i686-linux-android]
rustflags = ["-C", "link-arg=-Wl,-z,max-page-size=16384"]
```

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Missing `crate-type = ["cdylib"]` | Required for `.so` output; `rlib` is Rust-only |
| Forgetting 16KB page alignment | Add `-Wl,-z,max-page-size=16384` rustflag per target |
| Not pointing Gradle at the Android SDK/NDK | Set `sdk.dir` or `ANDROID_SDK_ROOT` and install the configured NDK |
| Hardcoding a Cargo profile in local commands | Prefer `:core:engine:buildRustNativeLibs`; the convention plugin already selects `android-jni` or `android-jni-dev` based on build context |
| Forgetting to keep Android profiles unwind-safe | `android-jni` and `android-jni-dev` must keep `panic = "unwind"` so JNI boundaries can translate failures safely |
| Wrong target triple | Use `armv7-linux-androideabi` (not `armv7a-`) for armeabi-v7a |

## See Also

- `rust-code-style` -- Rust code style rules for the native workspace
- `rust-lint-config` -- Clippy, rustfmt, and cargo-deny configuration
- `rust-crate-architecture` -- Crate layering and dependency rules
- `native-jni-development` -- JNI boundary rules and diagnostics payload compatibility
