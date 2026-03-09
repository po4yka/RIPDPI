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

For RIPDPI's 4 JNI functions, raw `jni` crate is simpler. Consider UniFFI if the API surface grows significantly.

## Raw JNI Crate

### Rust Side

```rust
use jni::JNIEnv;
use jni::objects::{JClass, JString, JObjectArray};
use jni::sys::{jint, jstring};

// Function name must match: Java_<package>_<Class>_<method>
// Package: com.poyka.ripdpi.core, Class: RipDpiProxy
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxy_jniStartProxy(
    mut env: JNIEnv,
    _class: JClass,
    fd: jint,
) -> jint {
    match start_proxy(fd) {
        Ok(result) => result,
        Err(e) => {
            let _ = env.throw_new("java/io/IOException", e.to_string());
            -1
        }
    }
}

// String parameter handling
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxy_jniCreateSocket(
    mut env: JNIEnv,
    _class: JClass,
    ip: JString,
    port: jint,
) -> jint {
    let ip: String = match env.get_string(&ip) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    create_socket(&ip, port as u16).unwrap_or(-1)
}
```

### Kotlin Side (unchanged from C)

```kotlin
class RipDpiProxy {
    companion object {
        init { System.loadLibrary("ripdpi") }
    }
    external fun jniStartProxy(fd: Int): Int
    external fun jniCreateSocket(ip: String, port: Int): Int
}
```

### JNI Naming Convention

```
Java_com_poyka_ripdpi_core_RipDpiProxy_<methodName>
     ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^   ^^^^^^^^^^
     package (dots -> underscores)       method name
```

Must match exactly. Use `#[unsafe(no_mangle)]` and `extern "system"` on every exported function.

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
    --out-dir app/src/main/java/com/poyka/ripdpi/rust
```

UniFFI generates Kotlin classes, enums, and error types automatically. No `external fun` declarations needed.

## Type Mapping

| Kotlin/Java | JNI type | Rust (`jni` crate) | Rust (UniFFI) |
|-------------|----------|-------------------|---------------|
| `Int` | `jint` | `jint` (i32) | `i32` |
| `Long` | `jlong` | `jlong` (i64) | `i64` |
| `Boolean` | `jboolean` | `jboolean` (u8) | `bool` |
| `String` | `JString` | `JString` -> `String` | `String` |
| `ByteArray` | `jbyteArray` | `JByteArray` -> `Vec<u8>` | `Vec<u8>` |
| `Array<String>` | `JObjectArray` | iterate with `get_object_array_element` | `Vec<String>` |

## RIPDPI-Specific: Existing C Functions to Replace

| C function | Parameters | Returns | Notes |
|------------|-----------|---------|-------|
| `jniCreateSocketWithCommandLine` | `Array<String>` | `Int` (fd) | Parse CLI args |
| `jniCreateSocket` | 28 typed params | `Int` (fd) | UI-configured mode |
| `jniStartProxy` | `Int` (fd) | `Int` | Blocking event loop |
| `jniStopProxy` | `Int` (fd) | `Int` | Graceful shutdown |

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Missing `#[unsafe(no_mangle)]` | Required for JNI to find the function by name |
| Using `extern "C"` instead of `extern "system"` | Use `extern "system"` for JNI on Android |
| Panicking across FFI boundary | Catch panics with `std::panic::catch_unwind`; never let panics cross into JVM |
| Leaking JNI local references | Use `env.auto_local()` or `delete_local_ref` in loops |
| Not handling JNI exceptions | Check `env.exception_check()` after calling Java methods from Rust |
