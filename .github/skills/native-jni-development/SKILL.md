---
name: native-jni-development
description: Use when modifying Rust native crates, changing JNI exports, or debugging native crashes and JNI linkage errors
---

# Native JNI Development

## Overview

RIPDPI has two native Rust libraries built through the Android module build. Modifications require understanding the Gradle build task, JNI naming conventions, and the app's existing Kotlin/native contracts.

## Native Libraries

| Library | Build | Source | Notes |
|---------|-------|--------|-------|
| `libripdpi.so` | Cargo + Android NDK linker | `native/rust/crates/ripdpi-android/` | Repo-owned Android JNI shim over the vendored ByeDPI-derived crates |
| `libhev-socks5-tunnel.so` | Cargo + Android NDK linker | `native/rust/crates/hs5t-android/` | Repo-owned Android JNI shim over the vendored tunnel crates |

## Build Pipeline

`:core:engine:buildRustNativeLibs` runs before `preBuild` through the `ripdpi.android.rust-native` convention plugin. It builds the `native/rust` workspace for all configured ABIs and writes the packaged `.so` files to `core/engine/build/generated/jniLibs/`.

## JNI Bridge Pattern

The Kotlin wrappers live in `core/engine/src/main/java/com/poyka/ripdpi/core/RipDpiProxy.kt` and `core/engine/src/main/java/com/poyka/ripdpi/core/Tun2SocksTunnel.kt`. Their corresponding Rust exports live in the repo-owned Android adapter crates.

### Existing JNI Functions

```text
Java_com_poyka_ripdpi_core_RipDpiProxy_jniCreate(JNIEnv*, jobject, jstring) -> jlong
Java_com_poyka_ripdpi_core_RipDpiProxy_jniStart(JNIEnv*, jobject, jlong) -> jint
Java_com_poyka_ripdpi_core_RipDpiProxy_jniStop(JNIEnv*, jobject, jlong) -> void
Java_com_poyka_ripdpi_core_RipDpiProxy_jniDestroy(JNIEnv*, jobject, jlong) -> void
Java_com_poyka_ripdpi_core_Tun2SocksTunnel_jniCreate(JNIEnv*, jobject, jstring) -> jlong
Java_com_poyka_ripdpi_core_Tun2SocksTunnel_jniStart(JNIEnv*, jobject, jlong, jint) -> void
Java_com_poyka_ripdpi_core_Tun2SocksTunnel_jniStop(JNIEnv*, jobject, jlong) -> void
Java_com_poyka_ripdpi_core_Tun2SocksTunnel_jniGetStats(JNIEnv*, jobject, jlong) -> jlongArray
Java_com_poyka_ripdpi_core_Tun2SocksTunnel_jniDestroy(JNIEnv*, jobject, jlong) -> void
```

### Adding a New JNI Function

1. Declare `external fun` in the Kotlin wrapper under `core/engine/src/main/java/com/poyka/ripdpi/core/`
2. Implement the exact JNI symbol in the relevant Rust `*-jni` crate with `#[unsafe(no_mangle)] pub extern "system" fn ...`
3. Keep existing session semantics stable: proxy start stays blocking, tunnel start stays non-blocking, and setup failures surface as Java exceptions
4. If the function touches shared session state, preserve the existing synchronization model on both the Kotlin and Rust sides

### Kotlin Wrapper Pattern

```kotlin
// RipDpiProxy.kt uses Mutex to serialize native session access
private val mutex = Mutex()
private var handle = 0L

suspend fun startProxy(preferences: RipDpiProxyPreferences): Int = mutex.withLock {
    handle = jniCreate(preferences.toNativeConfigJson())
    withContext(Dispatchers.IO) { jniStart(handle) }
}
```

## Rules

- **Never edit `.so` files** -- they are built from source.
- **NDK version and ABI filters** are in `gradle.properties` (single source of truth). Do not hardcode them in Gradle module files.
- **Library names and JNI symbol names are compatibility boundaries**. Keep `libripdpi.so`, `libhev-socks5-tunnel.so`, and the existing exported function names stable unless Kotlin call sites change in the same patch.
- **Do not let Rust panics cross JNI boundaries**. Return an error or throw a Java exception instead.
- **Page size flags** live in `native/rust/.cargo/config.toml` for 16KB page compatibility.

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| JNI function name mismatch | Must match package: `Java_com_poyka_ripdpi_core_RipDpiProxy_<name>` |
| Exporting with the wrong ABI | Use `extern "system"` and `#[unsafe(no_mangle)]` |
| Forgetting Mutex on Kotlin side | All JNI calls touching session handles must be wrapped in `mutex.withLock` |
| Breaking the lifecycle contract | `jniStart(handle)` must stay blocking for proxy; `Tun2SocksTunnel.jniStart(...)` must stay non-blocking |
| Adding ABI filters in module build file | Use `gradle.properties` -- convention plugin reads it |
