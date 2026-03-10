---
name: rust-android-ndk
description: Use when building Rust code for Android, configuring cross-compilation targets, or integrating Rust `.so` output into Gradle jniLibs
---

# Rust Android NDK

## Overview

RIPDPI builds Rust libraries as Android `.so` files with plain `cargo build` plus the Android NDK linker toolchain. The project-standard entrypoint is `scripts/native/build-rust-android.sh`, which the `:core:engine` Gradle module invokes before `preBuild`.

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
| `arm64-v8a` | `aarch64-linux-android` | 21 |
| `armeabi-v7a` | `armv7-linux-androideabi` | 19 |
| `x86_64` | `x86_64-linux-android` | 21 |
| `x86` | `i686-linux-android` | 19 |

RIPDPI supports all four ABIs with minSdk 27.

## Current Project Build

```bash
bash scripts/native/build-rust-android.sh \
    --sdk-dir "$ANDROID_SDK_ROOT" \
    --ndk-version 29.0.14206865 \
    --min-sdk 27 \
    --abis "armeabi-v7a,arm64-v8a,x86,x86_64" \
    --build-dir core/engine/build/intermediates/rust \
    --output-dir core/engine/build/generated/jniLibs
```

This creates `generated/jniLibs/<abi>/libripdpi.so` and `generated/jniLibs/<abi>/libhev-socks5-tunnel.so`.

### Gradle Task Integration

```kotlin
tasks.register<Exec>("buildRustNativeLibs") {
    group = "build"
    description = "Builds Rust native libraries for all ABIs"
    executable = "bash"
    args(rootProject.file("scripts/native/build-rust-android.sh").invariantSeparatorsPath, ...)
}
tasks.named("preBuild") { dependsOn("buildRustNativeLibs") }
```

The script sets the target linker for each ABI explicitly and builds:
- `native/rust/third_party/byedpi` crate `ciadpi-jni`
- `native/rust/third_party/hev-socks5-tunnel` crate `hs5t-jni`

## Cargo.toml Setup

```toml
[lib]
crate-type = ["cdylib"]  # Required: produces .so for Android

[dependencies]
jni = "0.22"              # JNI bindings
log = "0.4"               # Logging
android_logger = "0.14"   # Android logcat integration

[profile.release]
lto = true                # Link-time optimization
strip = true              # Strip debug symbols
opt-level = "z"           # Optimize for size
```

`crate-type = ["cdylib"]` is mandatory because Android loads the libraries through `System.loadLibrary()`.

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
| Not pointing Gradle or the script at the Android SDK/NDK | Set `sdk.dir` or `ANDROID_SDK_ROOT` and install the configured NDK |
| Building with debug profile | Use `--release` for APK; debug `.so` files are huge |
| Missing `strip = true` in release profile | Without stripping, `.so` files include debug info (~10x larger) |
| Wrong target triple | Use `armv7-linux-androideabi` (not `armv7a-`) for armeabi-v7a |
