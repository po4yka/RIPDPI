---
name: rust-sanitizers-miri
description: Rust sanitizers and Miri skill for memory safety validation. Use when running AddressSanitizer or ThreadSanitizer on Rust code, interpreting sanitizer reports, using Miri to detect undefined behaviour in unsafe Rust, or validating unsafe code correctness. Activates on queries about Rust ASan, Rust TSan, Miri, RUSTFLAGS sanitize, cargo miri, unsafe Rust UB, Android HWASan, or interpreting Rust sanitizer output.
---

# Rust Sanitizers and Miri

## Purpose

Guide agents through runtime safety validation for Rust: ASan/TSan/MSan/UBSan via RUSTFLAGS, Miri for compile-time UB detection in unsafe code, Android NDK HWASan for on-device validation, and interpreting sanitizer reports.

## RIPDPI Project Context

This project has ~135 `unsafe` occurrences across 20 Rust crates. JNI interop crates (`ripdpi-android`, `ripdpi-tunnel-android`) use heavy FFI with the JVM. Other crates use raw pointers, libc syscalls, and platform-specific code. Miri cannot execute JNI/FFI code -- see the FFI caveat in section 4.

## Triggers

- "How do I run AddressSanitizer on Rust code?"
- "How do I use Miri to check my unsafe Rust?"
- "How do I run ThreadSanitizer on a Rust program?"
- "My unsafe Rust might have UB -- how do I detect it?"
- "How do I run HWASan on Android native code?"

## Workflow

### 1. Sanitizers in Rust (nightly required)

Rust sanitizers require nightly and a compatible platform:

```bash
# Install nightly
rustup toolchain install nightly
rustup component add rust-src --toolchain nightly

# AddressSanitizer (Linux, macOS)
RUSTFLAGS="-Z sanitizer=address" \
    cargo +nightly test -Zbuild-std \
    --target x86_64-unknown-linux-gnu

# ThreadSanitizer (Linux)
RUSTFLAGS="-Z sanitizer=thread" \
    cargo +nightly test -Zbuild-std \
    --target x86_64-unknown-linux-gnu

# MemorySanitizer (Linux, requires all-instrumented build)
RUSTFLAGS="-Z sanitizer=memory -Zsanitizer-memory-track-origins" \
    cargo +nightly test -Zbuild-std \
    --target x86_64-unknown-linux-gnu
```

`-Zbuild-std` rebuilds the standard library with the sanitizer, which is necessary for accurate results.

### 2. Interpreting ASan output in Rust

```
==12345==ERROR: AddressSanitizer: heap-buffer-overflow on address 0x602000000050
READ of size 4 at 0x602000000050 thread T0
    #0 0x401234 in myapp::module::function /src/main.rs:15
    #1 0x401567 in myapp::main /src/main.rs:42
```

Rust-specific patterns:
| ASan error | Likely Rust cause |
|------------|------------------|
| `heap-buffer-overflow` | `unsafe` slice access past bounds |
| `use-after-free` | `unsafe` pointer use after Vec realloc |
| `stack-use-after-return` | Returning reference to local |
| `heap-use-after-free` | Use after `drop()` or `Box::from_raw` |

### 3. Android NDK ASan / HWASan

For on-device testing of JNI crates cross-compiled to Android NDK:

```bash
# HWASan (ARM64 only, Android 10+, preferred over ASan on ARM64)
# In .cargo/config.toml or via env:
RUSTFLAGS="-Z sanitizer=hwaddress" \
    cargo +nightly build -Zbuild-std \
    --target aarch64-linux-android

# ASan for Android (works on both ARM and x86 emulators)
RUSTFLAGS="-Z sanitizer=address" \
    cargo +nightly build -Zbuild-std \
    --target aarch64-linux-android
```

HWASan is preferred on ARM64: lower overhead than ASan, catches the same bugs, and is hardware-accelerated via top-byte-ignore (TBI). Requires Android 10+ and ARM64.

To run on-device:
1. Build the `.so` with the sanitizer flags above
2. Push to device and set `LD_PRELOAD` for the sanitizer runtime, or
3. Use Android Gradle plugin `android.defaultConfig.externalNativeBuild` with `arguments "-DANDROID_STL=c++_shared"` and enable HWASan in CMake

### 4. Miri -- interpreter for undefined behaviour

> **FFI LIMITATION**: Miri cannot execute `extern "C"`, JNI, libc syscalls, or inline assembly. This means JNI interop crates (`ripdpi-android`, `ripdpi-tunnel-android`) and any code calling libc directly cannot be tested under Miri without stubs. Use `#[cfg(miri)]` to provide mock implementations (see below).

```bash
# Install Miri (requires nightly)
rustup +nightly component add miri

# Run tests under Miri
cargo +nightly miri test

# Run specific test
cargo +nightly miri test test_name

# Strict provenance mode (recommended for CI)
MIRIFLAGS="-Zmiri-strict-provenance" cargo +nightly miri test

# Disable isolation (allow file I/O, randomness)
MIRIFLAGS="-Zmiri-disable-isolation" cargo +nightly miri test
```

#### Stubbing FFI for Miri

For crates with JNI or libc FFI, gate real implementations behind `#[cfg(not(miri))]` and provide stubs:

```rust
#[cfg(not(miri))]
extern "C" {
    fn platform_specific_call(fd: i32) -> i32;
}

#[cfg(miri)]
unsafe fn platform_specific_call(_fd: i32) -> i32 {
    0 // safe stub for Miri interpretation
}

// For JNI: skip JNI tests entirely under Miri
#[test]
fn test_pure_logic() {
    // This runs under Miri -- no JNI calls
}

#[test]
#[cfg_attr(miri, ignore)]
fn test_jni_integration() {
    // This is skipped under Miri
}
```

### 5. What Miri detects

- **Dangling pointers**: use after free, use after reallocation
- **Invalid values**: bad enum discriminants, non-0/1 bools, unaligned refs
- **Uninitialized memory**: reading `MaybeUninit` before init
- **Stacked Borrows / Tree Borrows violations**: aliasing rule breaches
- **Data races**: Miri has a dedicated **concurrency model that interleaves threads** at yield points, detecting unsynchronized accesses to shared state. This is independent of the aliasing model (Stacked Borrows / Tree Borrows).

### 6. Miri configuration via MIRIFLAGS

| Flag | Effect |
|------|--------|
| `-Zmiri-disable-isolation` | Allow I/O, clock, randomness |
| `-Zmiri-strict-provenance` | Strict pointer provenance checks |
| `-Zmiri-symbolic-alignment-check` | Stricter alignment checking |
| `-Zmiri-num-cpus=N` | Simulate N CPUs (for concurrency) |
| `-Zmiri-seed=N` | Seed for random thread scheduling |
| `-Zmiri-ignore-leaks` | Suppress memory leak errors |
| `-Zmiri-tree-borrows` | Use Tree Borrows model instead of Stacked Borrows |

### 7. CI integration

```yaml
# GitHub Actions
- name: Miri
  run: |
    rustup toolchain install nightly
    rustup +nightly component add miri
    cargo +nightly miri test
  env:
    MIRIFLAGS: "-Zmiri-disable-isolation -Zmiri-strict-provenance"

- name: ASan (nightly)
  run: |
    rustup component add rust-src --toolchain nightly
    RUSTFLAGS="-Z sanitizer=address" \
    cargo +nightly test -Zbuild-std \
    --target x86_64-unknown-linux-gnu
```

## Related skills

- `rust-debugging` -- GDB/LLDB debugging of Rust panics
- `rust-unsafe` -- unsafe Rust patterns and review checklist
- `rust-security` -- supply chain safety and memory-safe development
- `rust-ffi` -- C interoperability and safe FFI wrappers
- `memory-model` -- atomics, memory ordering, lock-free structures
