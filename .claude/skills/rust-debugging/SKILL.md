---
name: rust-debugging
description: Rust debugging skill for Android NDK targets. Use when debugging Rust native libraries on Android (JNI panics, logcat tracing, tombstones, addr2line), using GDB/LLDB with Rust pretty-printers, interpreting panics and backtraces, or debugging async tokio code. Activates on queries about native crashes, JNI debugging, rust-gdb, rust-lldb, RUST_BACKTRACE, Rust panics on Android, tracing-to-logcat, or tokio-console.
---

# Rust Debugging (Android NDK Focus)

## Purpose

Guide debugging of Rust native libraries running on Android via JNI. Covers
Android-specific tooling (logcat, tombstones, addr2line), JNI panic safety,
tracing-to-logcat wiring, and standard GDB/LLDB workflows.

## Triggers

- "Native crash in Rust on Android"
- "How do I debug JNI panics?"
- "How do I see Rust logs in logcat?"
- "RUST_BACKTRACE not working on Android"
- "How do I use GDB/LLDB to debug Rust?"
- "How do I debug async Rust / Tokio?"

## Project Architecture

Two `cdylib` crates are loaded via JNI on Android:
- `ripdpi-android` -- proxy engine (loaded as `libripdpi_native.so`)
- `ripdpi-tunnel-android` -- tun2socks tunnel (loaded as `libripdpi_tunnel_native.so`)

Shared infrastructure lives in `android-support` crate:
- `init_android_logging(tag)` -- wires `tracing` to logcat via `android_logger` + `tracing_subscriber`
- `install_panic_hook()` -- logs panic + full backtrace via `log::error!` (visible in logcat)
- `ignore_sigpipe()` -- prevents SIGPIPE kills on socket disconnect

Both crates call these in `JNI_OnLoad`, wrapped in `catch_unwind`.

## Workflow

### 1. Android-Specific Debugging

#### Logcat native crash filtering

```bash
# Filter Rust native logs (tags set in JNI_OnLoad)
adb logcat -s ripdpi-native:V ripdpi-tunnel-native:V

# Filter crash/panic output
adb logcat | grep -E "PANIC:|backtrace|signal|SIGABRT|SIGSEGV"

# Verbose native + runtime logs
adb logcat -s RustStdoutStderr:V AndroidRuntime:E DEBUG:*
```

#### Tombstone analysis after native crash

```bash
# Pull latest tombstone
adb shell ls -lt /data/tombstones/ | head -5
adb pull /data/tombstones/tombstone_00

# Or use bugreport
adb bugreport bugreport.zip
```

#### Symbolicate with addr2line (NDK)

```bash
# Find NDK addr2line (adjust NDK version)
ADDR2LINE=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/*/bin/llvm-addr2line

# Symbolicate addresses from tombstone/logcat
$ADDR2LINE -e native/rust/target/aarch64-linux-android/debug/libripdpi_native.so \
    0x12345 0x67890

# Demangle Rust symbols
$ADDR2LINE -Cfe native/rust/target/aarch64-linux-android/debug/libripdpi_native.so \
    0x12345
```

#### LLDB via Android Studio

1. Open the Android project in Android Studio
2. Run > Edit Configurations > Debugger tab > Debug type: **Dual (Java + Native)**
3. Add symbol search path: `native/rust/target/aarch64-linux-android/debug/`
4. Set breakpoints in Rust source files
5. Run with debugger attached -- Studio invokes `lldb-server` on device

#### RUST_BACKTRACE on Android

`RUST_BACKTRACE` env vars are **not inherited** by Android app processes.
The project handles this via `install_panic_hook()` which calls
`Backtrace::force_capture()` unconditionally -- no env var needed.

If you need backtraces in a standalone binary (not app process):
```bash
adb shell "cd /data/local/tmp && RUST_BACKTRACE=1 ./my_test_binary"
```

### 2. JNI Panic Safety

**Rule: Rust panics must never unwind across `extern "system"` JNI boundaries.**
Unwinding across FFI is undefined behavior and typically aborts the process.

This project's pattern (both cdylib crates):

```rust
// JNI_OnLoad wraps all init in catch_unwind
#[unsafe(no_mangle)]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut c_void) -> jint {
    match std::panic::catch_unwind(|| {
        android_support::ignore_sigpipe();
        init_android_logging("ripdpi-native");
        android_support::install_panic_hook();
        JNI_VERSION
    }) {
        Ok(version) => version,
        Err(_) => jni::sys::JNI_ERR,
    }
}
```

For individual JNI methods, the `jni` crate's `Outcome` enum handles panics:
```rust
// android-support uses EnvUnowned::with_env() which returns Outcome
match env.with_env(|env| { /* ... */ }).into_outcome() {
    Outcome::Ok(val) => val,
    Outcome::Err(err) => { /* log + throw Java exception */ }
    Outcome::Panic(_) => { /* log, already caught */ }
}
```

When adding new JNI exports: always use `EnvUnowned::with_env()` or wrap the
body in `catch_unwind(AssertUnwindSafe(|| { ... }))`.

### 3. Tracing to Logcat

The `android-support` crate wires tracing to logcat:

```
tracing macros -> tracing_subscriber::registry()
    -> AndroidLogLayer (forwards to android_logger)
    -> EventRingLayer (buffers events for Kotlin polling)
LogTracer bridges log crate -> tracing (for non-tracing deps)
```

Initialized in `init_android_logging(tag)` which calls `android_logger::init_once`,
`LogTracer::init()`, and sets up the subscriber registry. Default log level is
Debug in debug builds, Info in release. Override per scope at runtime:
```rust
android_support::set_android_log_scope_level("proxy", LevelFilter::Trace);
android_support::clear_android_log_scope_level("proxy");
```

### 4. Build for Debugging

```bash
cargo build --target aarch64-linux-android          # debug
cargo build --target aarch64-linux-android --release # release w/ debug info
# Ensure Cargo.toml has: [profile.release] debug = true
```

### 5. GDB/LLDB with Rust Pretty-Printers

```bash
# Use rust-gdb/rust-lldb wrappers (auto-configure pretty-printers)
rust-gdb target/debug/myapp
rust-lldb target/debug/myapp
```

Essential commands:
```
# Break on panic
(gdb) break rust_panic
(lldb) b rust_panic

# Break on function
(gdb) break myapp::module::function_name
(lldb) b myapp::module::function_name

# Inspect Rust types (pretty-printed)
(gdb) print my_string    # "hello world"
(gdb) print my_vec       # vec![1, 2, 3]
(gdb) print my_option    # Some(42) or None

# Backtrace
(gdb) bt full
(lldb) thread backtrace all
```

For full GDB/LLDB command reference, see
[references/rust-gdb-pretty-printers.md](references/rust-gdb-pretty-printers.md).

### 6. The dbg! Macro

```rust
let result = dbg!(some_computation(x));
// prints: [src/main.rs:15] some_computation(x) = 42
```

**Note:** `dbg!` writes to stderr, which is **not visible in logcat** on
Android. Use `tracing::debug!()` instead for on-device debugging.

### 7. Structured Logging with tracing

```rust
use tracing::{debug, error, info, instrument, warn};

#[instrument]  // Auto-traces function entry/exit with arguments
fn process(id: u64, data: &str) -> Result<(), Error> {
    info!(item_id = id, "Started processing");
    if data.is_empty() {
        warn!(item_id = id, "Empty data");
    }
    Ok(())
}
```

### 8. Async Debugging with tokio-console

**Caveat:** `console-subscriber` is **not** in the project's dependencies.
It requires `tokio_unstable` cfg and opens a TCP port -- not suitable for
production Android builds. For local dev only, add `console-subscriber`
temporarily and build with `RUSTFLAGS="--cfg tokio_unstable"`.

For async debugging on Android, rely on `#[instrument]` tracing spans
which appear in logcat with enter/exit events.

### 9. Panic Triage Quick Reference

| Panic message | Likely cause |
|---|---|
| `called Option::unwrap() on a None value` | Unwrap on None |
| `called Result::unwrap() on an Err value` | Unwrap on error |
| `index out of bounds` | Array/vec OOB access |
| `attempt to subtract with overflow` | Integer underflow |
| Signal 6 (SIGABRT) in tombstone | Rust panic with `panic = "abort"` |
| Signal 11 (SIGSEGV) in tombstone | Null pointer / use-after-free in unsafe |

## Related Skills

- [rust-unsafe](../rust-unsafe/) -- unsafe code review and FFI patterns
- [rust-async-internals](../rust-async-internals/) -- Future trait, poll model, waker debugging
- [rust-sanitizers-miri](../rust-sanitizers-miri/) -- ASan, TSan, Miri for memory safety
- [rust-profiling](../rust-profiling/) -- flamegraphs, cargo-bloat, Criterion benchmarks
