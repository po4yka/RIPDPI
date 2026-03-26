---
name: rust-jni-bridge
description: Use when implementing JNI functions in Rust with the jni crate, exposing Rust functions to Kotlin, or choosing between raw JNI and UniFFI for Android bindings
---

# Rust JNI Bridge

## Overview

Two approaches for Rust-to-Kotlin bindings on Android: raw `jni` crate (manual, full control) and UniFFI (auto-generated, higher-level). Choose based on complexity and how many functions you expose.

## Approach Comparison

| | Raw `jni` crate | UniFFI |
|--|----------------|--------|
| Control | Full | Framework-managed |
| Boilerplate | High (manual signatures) | Low (generated from UDL/proc-macro) |
| Error handling | Manual JNI exception throwing | Automatic mapping |
| Best for | Few functions, performance-critical | Many functions, complex types |
| Kotlin code | Write manually (`external fun`) | Auto-generated bindings |
| Crate | `jni = "0.22"` | `uniffi = "0.28"` |

For RIPDPI's small handle-based JNI surface, raw `jni` crate is simpler. Consider UniFFI only if the API grows well beyond the current proxy and tunnel session methods.

## Raw JNI Crate

### Rust Side

```rust
use jni::JNIEnv;
use jni::objects::{JObject, JString};
use jni::sys::{jint, jlong};

// Function name must match: Java_<package>_<Class>_<method>
// Package: com.poyka.ripdpi.core, Class: RipDpiProxyNativeBindings
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniStart(
    mut env: JNIEnv,
    _thiz: JObject,
    handle: jlong,
) -> jint {
    match start_proxy(handle) {
        Ok(result) => result,
        Err(e) => {
            let _ = env.throw_new("java/io/IOException", e.to_string());
            -1
        }
    }
}

// String parameter handling
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_jniCreate(
    mut env: JNIEnv,
    _thiz: JObject,
    config_json: JString,
) -> jlong {
    let config_json: String = match env.get_string(&config_json) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    create_session(&config_json).unwrap_or(0)
}
```

### Kotlin Side

```kotlin
class RipDpiProxyNativeBindings @Inject constructor() : RipDpiProxyBindings {
    companion object {
        init {
            RipDpiNativeLoader.ensureLoaded()
        }
    }

    override fun create(configJson: String): Long = jniCreate(configJson)
    override fun start(handle: Long): Int = jniStart(handle)

    private external fun jniCreate(configJson: String): Long
    private external fun jniStart(handle: Long): Int
}
```

### JNI Naming Convention

```
Java_com_poyka_ripdpi_core_RipDpiProxyNativeBindings_<methodName>
     ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^   ^^^^^^^^^^
     package (dots -> underscores)       method name
```

Must match exactly. Use `#[unsafe(no_mangle)]` and `extern "system"` on every exported function.

RIPDPI currently exports JNI entry points on `RipDpiProxyNativeBindings`, `Tun2SocksNativeBindings`, and `NetworkDiagnosticsNativeBindings`.

## UniFFI Approach

### Define Interface (UDL)

```udl
// src/main/rust/src/ripdpi.udl
namespace ripdpi {
    [Throws=ProxyError]
    i32 create_socket(string ip, i32 port);

    [Throws=ProxyError]
    i32 start_proxy(i32 fd);

    [Throws=ProxyError]
    i32 stop_proxy(i32 fd);
};

[Error]
enum ProxyError {
    "SocketError",
    "BindError",
    "InvalidAddress",
};
```

### Rust Implementation

```rust
uniffi::setup_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum ProxyError {
    #[error("Socket error")]
    SocketError,
    #[error("Bind error")]
    BindError,
    #[error("Invalid address")]
    InvalidAddress,
}

#[uniffi::export]
pub fn create_socket(ip: String, port: i32) -> Result<i32, ProxyError> {
    // implementation
}
```

### Generate Kotlin Bindings

```bash
cargo run --bin uniffi-bindgen generate \
    --library target/debug/libripdpi.so \
    --language kotlin \
    --out-dir core/engine/src/main/kotlin/com/poyka/ripdpi/rust
```

UniFFI generates Kotlin classes, enums, and error types automatically. No `external fun` declarations needed. RIPDPI does not currently use UniFFI for the Android bridge.

## Type Mapping

| Kotlin/Java | JNI type | Rust (`jni` crate) | Rust (UniFFI) |
|-------------|----------|-------------------|---------------|
| `Int` | `jint` | `jint` (i32) | `i32` |
| `Long` | `jlong` | `jlong` (i64) | `i64` |
| `Boolean` | `jboolean` | `jboolean` (u8) | `bool` |
| `String` | `JString` | `JString` -> `String` | `String` |
| `ByteArray` | `jbyteArray` | `JByteArray` -> `Vec<u8>` | `Vec<u8>` |
| `Array<String>` | `JObjectArray` | iterate with `get_object_array_element` | `Vec<String>` |

## RIPDPI-Specific Native Surface

| Current function | Parameters | Returns | Notes |
|------------|-----------|---------|-------|
| `jniCreate` | `String` (JSON) | `Long` (handle) | Creates a proxy or tunnel session |
| `jniStart` | `Long` / `Long, Int` | `Int` or `Unit` | Proxy start blocks; tunnel start owns its worker thread |
| `jniStop` | `Long` | `Unit` | Graceful shutdown |
| `jniPollTelemetry` / `jniGetTelemetry` | `Long` | `String?` | Proxy and tunnel runtime telemetry snapshots |
| `jniPollProgress` / `jniTakeReport` / `jniPollPassiveEvents` | `Long` | `String?` | Diagnostics progress, final report, and passive events |
| `jniUpdateNetworkSnapshot` | `Long, String` | `Unit` | Updates the active proxy session with network metadata |
| `jniDestroy` | `Long` | `Unit` | Releases native session state |

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Missing `#[unsafe(no_mangle)]` | Required for JNI to find the function by name |
| Using `extern "C"` instead of `extern "system"` | Use `extern "system"` for JNI on Android |
| Panicking across FFI boundary | Catch panics with `std::panic::catch_unwind`; never let panics cross into JVM |
| Leaking JNI local references | Use `env.auto_local()` or `delete_local_ref` in loops |
| Not handling JNI exceptions | Check `env.exception_check()` after calling Java methods from Rust |

## See Also

- `rust-code-style` -- Rust code style rules for the native workspace
- `rust-lint-config` -- Clippy, rustfmt, and cargo-deny configuration
- `rust-crate-architecture` -- Crate layering and dependency rules
