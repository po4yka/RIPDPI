---
name: native-jni-development
description: Rust native crate changes, JNI exports, native crashes, and JNI linkage errors.
---

# Native JNI Development

## Overview

RIPDPI has two native Rust libraries built through the Android module build. Modifications require understanding the Gradle build task, JNI naming conventions, and the app's existing Kotlin/native contracts.

## Native Libraries

| Library | Build | Source | Notes |
|---------|-------|--------|-------|
| `libripdpi.so` | Cargo + Android NDK linker | `native/rust/crates/ripdpi-android/` | Repo-owned Android JNI shim over the in-repo proxy runtime, diagnostics monitor, and shared config crates |
| `libripdpi-tunnel.so` | Cargo + Android NDK linker | `native/rust/crates/ripdpi-tunnel-android/` | Repo-owned Android JNI shim over the in-repo tunnel runtime crates |

## Build Pipeline

`:core:engine:buildRustNativeLibs` runs before `preBuild` through the `ripdpi.android.rust-native` convention plugin. It builds the `native/rust` workspace for all configured ABIs and writes the packaged `.so` files to `core/engine/build/generated/jniLibs/`.

## JNI Bridge Pattern

The Kotlin bindings live in `core/engine/src/main/kotlin/com/poyka/ripdpi/core/` and the corresponding Rust exports live in the repo-owned Android adapter crates.

Current binding classes:

- `RipDpiProxyBindings` / `RipDpiProxyNativeBindings`
- `Tun2SocksBindings` / `Tun2SocksNativeBindings`
- `NetworkDiagnosticsBindings` / `NetworkDiagnosticsNativeBindings`

Loader split:

- `RipDpiProxyNativeBindings` and `NetworkDiagnosticsNativeBindings` use `RipDpiNativeLoader.ensureLoaded()`
- `Tun2SocksNativeBindings` calls `System.loadLibrary("ripdpi-tunnel")` directly

### Existing JNI Functions

```text
Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniCreate(JNIEnv*, jobject, jstring) -> jlong
Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniStart(JNIEnv*, jobject, jlong) -> jint
Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniStop(JNIEnv*, jobject, jlong) -> void
Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniPollTelemetry(JNIEnv*, jobject, jlong) -> jstring
Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniDestroy(JNIEnv*, jobject, jlong) -> void
Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniUpdateNetworkSnapshot(JNIEnv*, jobject, jlong, jstring) -> void
Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniCreate(JNIEnv*, jobject) -> jlong
Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniStartScan(JNIEnv*, jobject, jlong, jstring, jstring) -> void
Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniCancelScan(JNIEnv*, jobject, jlong) -> void
Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniPollProgress(JNIEnv*, jobject, jlong) -> jstring
Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniTakeReport(JNIEnv*, jobject, jlong) -> jstring
Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniPollPassiveEvents(JNIEnv*, jobject, jlong) -> jstring
Java_com_poyka_ripdpi_core_NetworkDiagnosticsNativeBindings_jniDestroy(JNIEnv*, jobject, jlong) -> void
Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniCreate(JNIEnv*, jobject, jstring) -> jlong
Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniStart(JNIEnv*, jobject, jlong, jint) -> void
Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniStop(JNIEnv*, jobject, jlong) -> void
Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniGetStats(JNIEnv*, jobject, jlong) -> jlongArray
Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniGetTelemetry(JNIEnv*, jobject, jlong) -> jstring
Java_com_poyka_ripdpi_core_Tun2SocksNativeBindings_jniDestroy(JNIEnv*, jobject, jlong) -> void
```

### Adding a New JNI Function

1. Declare the `external fun` on the relevant `*NativeBindings` class under `core/engine/src/main/kotlin/com/poyka/ripdpi/core/`
2. Implement the exact JNI symbol in the relevant Android adapter crate with `#[unsafe(no_mangle)] pub extern "system" fn ...`
3. Keep existing session semantics stable: proxy start stays blocking, tunnel start stays non-blocking, and setup failures surface as Java exceptions
4. If the function touches shared session state, preserve the existing synchronization model on both the Kotlin and Rust sides

### Diagnostics Contract Notes

Diagnostics JNI is not just a transport shell. It carries compatibility-sensitive JSON payloads used by Kotlin tests and exports:

- progress snapshots, including `strategyProbeProgress`
- scan reports, including `auditAssessment` and `targetSelection`
- passive diagnostics event batches

When changing diagnostics JNI, update both Rust and Kotlin contract tests and treat field removals/renames as breaking changes.

### Kotlin Wrapper Pattern

```kotlin
class RipDpiProxyNativeBindings @Inject constructor() : RipDpiProxyBindings {
    companion object {
        init {
            RipDpiNativeLoader.ensureLoaded()
        }
    }

    override fun create(configJson: String): Long = jniCreate(configJson)
    override fun start(handle: Long): Int = jniStart(handle)
    override fun stop(handle: Long) { jniStop(handle) }
    override fun pollTelemetry(handle: Long): String? = jniPollTelemetry(handle)
    override fun destroy(handle: Long) { jniDestroy(handle) }
    override fun updateNetworkSnapshot(handle: Long, snapshotJson: String) {
        jniUpdateNetworkSnapshot(handle, snapshotJson)
    }
}
```

## Rules

- **Never edit `.so` files** -- they are built from source.
- **NDK version and ABI filters** are in `gradle.properties` (single source of truth). Do not hardcode them in Gradle module files.
- **Library names and JNI symbol names are compatibility boundaries**. Keep `libripdpi.so`, `libripdpi-tunnel.so`, and the existing exported function names stable unless Kotlin call sites change in the same patch.
- **Do not let Rust panics cross JNI boundaries**. Return an error or throw a Java exception instead.
- **Page size flags** live in `native/rust/.cargo/config.toml` for 16KB page compatibility.

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| JNI function name mismatch | Must match the binding class, for example `Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_<name>` |
| Exporting with the wrong ABI | Use `extern "system"` and `#[unsafe(no_mangle)]` |
| Forgetting Mutex on Kotlin side | All JNI calls touching session handles must be wrapped in `mutex.withLock` |
| Breaking the lifecycle contract | `jniStart(handle)` must stay blocking for proxy; `Tun2SocksNativeBindings.jniStart(...)` must stay non-blocking |
| Adding ABI filters in module build file | Use `gradle.properties` -- convention plugin reads it |

## See Also

- `rust-code-style` -- Rust code style rules for the native workspace
- `rust-lint-config` -- Clippy, rustfmt, and cargo-deny configuration
- `rust-crate-architecture` -- Crate layering and dependency rules
- `native-profiling` -- CPU/memory profiling for native Rust code on Android
- `network-traffic-debug` -- Traffic capture and inspection for SOCKS5 proxy and VPN tunnel
