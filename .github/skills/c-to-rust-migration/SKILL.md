---
name: c-to-rust-migration
description: Use when planning or executing migration of C native code to Rust, replacing byedpi or hev-socks5-tunnel, or maintaining C-Rust FFI interop during incremental transition
---

# C to Rust Migration

## Overview

Migrate RIPDPI's native C libraries to Rust incrementally. Replace one module at a time while maintaining the same JNI interface. The Kotlin layer and Android services should not change during migration.

## Current Native Architecture

```
Kotlin (RipDpiProxy.kt)
    |
    v  JNI
native-lib.c (bridge) --> byedpi/*.c (proxy engine)
    |
    v  separate build
hev-socks5-tunnel (git submodule, ndk-build)
```

## Migration Strategy

### Phase 1: Replace JNI Bridge Only

Rewrite `native-lib.c` and `utils.c` in Rust while keeping byedpi as a C dependency linked via FFI.

```
Kotlin (unchanged)
    |
    v  JNI (same function names)
lib.rs (Rust bridge) --FFI--> byedpi/*.c (unchanged C code)
```

**Why start here:** The bridge is small (4 functions), fully under your control, and validates the Rust build pipeline without touching upstream code.

### Phase 2: Replace byedpi with Rust Implementation

Rewrite the proxy engine (desync, packet manipulation, event loop) in Rust. Remove C dependency entirely.

```
Kotlin (unchanged)
    |
    v  JNI
lib.rs (Rust bridge + engine)  // pure Rust, no C FFI
```

### Phase 3: Replace hev-socks5-tunnel

Find or write a Rust VPN tunnel library. Remove the git submodule and ndk-build step.

```
Kotlin (unchanged)
    |
    v  JNI
lib.rs (complete Rust native layer)
```

## Phase 1 Implementation: Rust Bridge with C FFI

### Project Structure

```
core/engine/src/main/rust/
    Cargo.toml
    build.rs           # Link byedpi C sources
    src/
        lib.rs          # JNI exports
        ffi.rs          # C function declarations
        bridge.rs       # Safe Rust wrappers around C
    .cargo/
        config.toml     # Android target flags
```

### build.rs: Compile and Link C Sources

```rust
fn main() {
    let byedpi_dir = std::path::Path::new("../cpp/byedpi");

    cc::Build::new()
        .files(
            std::fs::read_dir(byedpi_dir)
                .unwrap()
                .filter_map(|e| e.ok())
                .map(|e| e.path())
                .filter(|p| p.extension().is_some_and(|ext| ext == "c"))
                .filter(|p| !p.ends_with("win_service.c"))
                .filter(|p| !p.ends_with("main.c")),
        )
        .include(byedpi_dir)
        .flag("-std=c99")
        .flag("-D_XOPEN_SOURCE=500")
        .flag("-DANDROID_APP")
        .compile("byedpi");
}
```

### ffi.rs: Declare C Functions

```rust
use std::os::raw::{c_char, c_int};

extern "C" {
    pub fn get_addr(addr: *const c_char, sa: *mut libc::sockaddr_storage) -> c_int;
    pub fn listen_socket(sa: *const libc::sockaddr_storage) -> c_int;
    pub fn event_loop(fd: c_int) -> c_int;
    pub fn reset_params();
    // ... other byedpi functions
}
```

### lib.rs: JNI Exports

```rust
mod ffi;
mod bridge;

use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::jint;

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiProxy_jniStartProxy(
    _env: JNIEnv,
    _class: JClass,
    fd: jint,
) -> jint {
    // Safe wrapper around C event_loop
    bridge::start_proxy(fd as i32)
}
```

## Build System Changes

### Replace CMake with Cargo

```kotlin
// core/engine/build.gradle.kts

// Remove:
// externalNativeBuild { cmake { ... } }

// Add Rust build task:
tasks.register<Exec>("buildRustLibrary") {
    group = "build"
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

## Validation Checklist

After each phase, verify:

1. **Same JNI interface**: Kotlin `external fun` declarations unchanged
2. **Same behavior**: Proxy connects, desync works, VPN tunnels traffic
3. **Same ABIs**: All four architectures build and load
4. **CI passes**: `./gradlew assembleDebug` and `testDebugUnitTest` green
5. **APK size**: Compare `.so` sizes before/after (Rust with LTO + strip should be comparable)

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Rewriting everything at once | Migrate one layer at a time; keep JNI interface stable |
| Changing Kotlin layer during migration | Kotlin side should not change -- same `external fun` signatures |
| Forgetting to link C libs in build.rs | Use `cc` crate to compile byedpi sources during Phase 1 |
| Panic across FFI boundary | Wrap all JNI exports with `catch_unwind`; return -1 on error |
| Not testing on all ABIs | Some C code may behave differently on x86 vs ARM; test all four |
| Removing ndk-build too early | Keep hev-socks5-tunnel ndk-build until Phase 3 |
