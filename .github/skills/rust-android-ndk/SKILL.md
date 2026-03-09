---
name: rust-android-ndk
description: Use when building Rust code for Android, configuring cargo-ndk or rust-android-gradle, setting up cross-compilation targets, or integrating Rust .so output into Gradle jniLibs
---

# Rust Android NDK

## Overview

Build Rust libraries as Android `.so` files using `cargo-ndk` for CLI builds or `rust-android-gradle` for Gradle-integrated builds. Both output to jniLibs for APK packaging.

## Toolchain Setup

```bash
# Install Rust Android targets
rustup target add \
    aarch64-linux-android \
    armv7-linux-androideabi \
    x86_64-linux-android \
    i686-linux-android

# Install cargo-ndk
cargo install cargo-ndk
```

Set `ANDROID_NDK_HOME` to your NDK path (e.g., `$ANDROID_SDK_ROOT/ndk/29.0.14206865`). cargo-ndk auto-detects the latest NDK if installed via Android Studio.

## Target Mapping

| Android ABI | Rust target | Min API |
|-------------|-------------|---------|
| `arm64-v8a` | `aarch64-linux-android` | 21 |
| `armeabi-v7a` | `armv7-linux-androideabi` | 19 |
| `x86_64` | `x86_64-linux-android` | 21 |
| `x86` | `i686-linux-android` | 19 |

RIPDPI supports all four ABIs with minSdk 27.

## Option A: cargo-ndk (CLI)

```bash
# Build for all targets, output to jniLibs structure
cargo ndk \
    -t arm64-v8a \
    -t armeabi-v7a \
    -t x86_64 \
    -t x86 \
    -o core/engine/build/generated/jniLibs \
    build --release
```

This creates `jniLibs/<abi>/lib<name>.so` matching Android's expected layout.

### Gradle Task Integration

```kotlin
// core/engine/build.gradle.kts
tasks.register<Exec>("buildRustLibrary") {
    group = "build"
    description = "Builds Rust native library for all ABIs"
    workingDir = file("src/main/rust")
    val jniLibsDir = layout.buildDirectory.dir("generated/jniLibs")
    outputs.dir(jniLibsDir)
    commandLine("cargo", "ndk",
        "-t", "arm64-v8a", "-t", "armeabi-v7a",
        "-t", "x86_64", "-t", "x86",
        "-o", jniLibsDir.get().asFile.absolutePath,
        "build", "--release")
}
tasks.named("preBuild") { dependsOn("buildRustLibrary") }
```

## Option B: rust-android-gradle Plugin

```kotlin
// build.gradle.kts
plugins {
    id("org.mozilla.rust-android-gradle.rust-android") version "0.9.6"
}

cargo {
    module = "src/main/rust"
    libname = "ripdpi"
    targets = listOf("arm64", "arm", "x86_64", "x86")
    profile = "release"
    prebuiltToolchains = true
}
```

The plugin auto-wires `cargoBuild` into the Gradle lifecycle and copies `.so` files to jniLibs.

## Cargo.toml Setup

```toml
[package]
name = "ripdpi-native"
version = "0.1.0"
edition = "2021"

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

`crate-type = ["cdylib"]` is mandatory -- it produces a C-compatible shared library that Android's `System.loadLibrary()` can load.

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
| Not setting `ANDROID_NDK_HOME` | cargo-ndk needs it if NDK isn't in default Android Studio path |
| Building with debug profile | Use `--release` for APK; debug `.so` files are huge |
| Missing `strip = true` in release profile | Without stripping, `.so` files include debug info (~10x larger) |
| Wrong target triple | Use `armv7-linux-androideabi` (not `armv7a-`) for armeabi-v7a |
