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
| `libripdpi.so` | Cargo + Android NDK linker | `native/rust/third_party/byedpi/crates/ciadpi-jni/` | Rust JNI shim over the vendored ByeDPI-derived crates |
| `libhev-socks5-tunnel.so` | Cargo + Android NDK linker | `native/rust/third_party/hev-socks5-tunnel/crates/hs5t-jni/` | Rust JNI shim over the vendored tunnel crates |

## Build Pipeline

`:core:engine:buildRustNativeLibs` runs before `preBuild` and invokes `scripts/native/build-rust-android.sh`. That script builds both JNI crates for all configured ABIs and writes the packaged `.so` files to `core/engine/build/generated/jniLibs/`.

## JNI Bridge Pattern

The Kotlin wrappers live in `core/engine/src/main/java/com/poyka/ripdpi/core/RipDpiProxy.kt` and `core/engine/src/main/java/com/poyka/ripdpi/core/TProxyService.kt`. Their corresponding Rust exports live in the two `*-jni` crates.

### Existing JNI Functions

```
Java_com_poyka_ripdpi_core_RipDpiProxy_jniCreateSocketWithCommandLine(JNIEnv*, jobject, jobjectArray) -> jint
Java_com_poyka_ripdpi_core_RipDpiProxy_jniCreateSocket(JNIEnv*, jobject, ..28 params..) -> jint
Java_com_poyka_ripdpi_core_RipDpiProxy_jniStartProxy(JNIEnv*, jobject, jint) -> jint
Java_com_poyka_ripdpi_core_RipDpiProxy_jniStopProxy(JNIEnv*, jobject, jint) -> jint
Java_com_poyka_ripdpi_core_TProxyService_TProxyStartService(JNIEnv*, jobject, jstring, jint) -> void
Java_com_poyka_ripdpi_core_TProxyService_TProxyStopService(JNIEnv*, jobject) -> void
Java_com_poyka_ripdpi_core_TProxyService_TProxyGetStats(JNIEnv*, jobject) -> jlongArray
```

### Adding a New JNI Function

1. Declare `external fun` in the Kotlin wrapper under `core/engine/src/main/java/com/poyka/ripdpi/core/`
2. Implement the exact JNI symbol in the relevant Rust `*-jni` crate with `#[unsafe(no_mangle)] pub extern "system" fn ...`
3. Keep existing return semantics stable, including `-1` error returns or `Unit` return shape where already established
4. If the function touches shared fd or worker-thread state, preserve the existing synchronization model on both the Kotlin and Rust sides

### Kotlin Wrapper Pattern

```kotlin
// RipDpiProxy.kt uses Mutex to serialize fd access
private val mutex = Mutex()
private var fd: Int = -1

suspend fun startProxy(preferences: RipDpiProxyPreferences): Int = mutex.withLock {
    fd = createSocket(preferences)
    withContext(Dispatchers.IO) { jniStartProxy(fd) }
}
```

## Rules

- **Never edit `.so` files** -- they are built from source.
- **NDK version and ABI filters** are in `gradle.properties` (single source of truth). Do not hardcode them in Gradle module files.
- **Library names and JNI symbol names are compatibility boundaries**. Keep `libripdpi.so`, `libhev-socks5-tunnel.so`, and the existing exported function names stable unless Kotlin call sites change in the same patch.
- **Do not let Rust panics cross JNI boundaries**. Return an error or throw a Java exception instead.
- **Page size flags** are injected by `scripts/native/build-rust-android.sh` via `-Wl,-z,max-page-size=16384` for 16KB page compatibility.

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| JNI function name mismatch | Must match package: `Java_com_poyka_ripdpi_core_RipDpiProxy_<name>` |
| Exporting with the wrong ABI | Use `extern "system"` and `#[unsafe(no_mangle)]` |
| Forgetting Mutex on Kotlin side | All JNI calls touching fd must be wrapped in `mutex.withLock` |
| Breaking the proxy lifecycle contract | `jniStartProxy(fd)` must stay blocking; `TProxyStartService(...)` must stay non-blocking |
| Adding ABI filters in module build file | Use `gradle.properties` -- convention plugin reads it |
