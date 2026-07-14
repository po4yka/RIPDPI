---
name: rust-sanitizers-miri
description: Use when running AddressSanitizer/ThreadSanitizer/MemorySanitizer on Rust code, configuring Miri for unsafe-Rust UB detection (Stacked Borrows or Tree Borrows), stubbing FFI for Miri compatibility, enabling HWASan or MTE on Android 14+, interpreting tombstones tagged SEGV_MTEAERR/SEGV_MTESERR, or wiring sanitizers into CI. Triggers on "sanitizer", "miri", "ASan/TSan/HWASan/MTE", "undefined behavior", or memory-safety validation questions.
---

# Rust Sanitizers and Miri

## Purpose

Guide agents through runtime safety validation for Rust: ASan/TSan/MSan/UBSan via RUSTFLAGS, Miri for compile-time UB detection in unsafe code, Android NDK HWASan for on-device validation, and interpreting sanitizer reports.

## RIPDPI Project Context

This project has ~135 `unsafe` occurrences across 97 Rust crates. JNI interop crates (`ripdpi-android`, `ripdpi-tunnel-android`) use heavy FFI with the JVM. Other crates use raw pointers, libc syscalls, and platform-specific code. Miri cannot execute JNI/FFI code -- see the FFI caveat in section 4.

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
    cargo +nightly test --locked -Zbuild-std \
    --target x86_64-unknown-linux-gnu

# ThreadSanitizer (Linux)
RUSTFLAGS="-Z sanitizer=thread" \
    cargo +nightly test --locked -Zbuild-std \
    --target x86_64-unknown-linux-gnu

# MemorySanitizer (Linux, requires all-instrumented build)
RUSTFLAGS="-Z sanitizer=memory -Zsanitizer-memory-track-origins" \
    cargo +nightly test --locked -Zbuild-std \
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
    cargo +nightly build --locked -Zbuild-std \
    --target aarch64-linux-android

# ASan for Android (works on both ARM and x86 emulators)
RUSTFLAGS="-Z sanitizer=address" \
    cargo +nightly build --locked -Zbuild-std \
    --target aarch64-linux-android
```

HWASan is preferred on ARM64: lower overhead than ASan, catches the same bugs, and is hardware-accelerated via top-byte-ignore (TBI). Requires Android 10+ and ARM64.

To run on-device:
1. Build the `.so` with the sanitizer flags above
2. Push to device and set `LD_PRELOAD` for the sanitizer runtime, or
3. Use Android Gradle plugin `android.defaultConfig.externalNativeBuild` with `arguments "-DANDROID_STL=c++_shared"` and enable HWASan in CMake

### 3a. MTE (Memory Tagging Extension) — Android 14+ on supporting SoCs

MTE is the hardware-accelerated successor to HWASan, available on arm64 Android 14+ with a supporting SoC (Pixel 8+, recent Samsung flagships). Unlike HWASan (software top-byte-ignore), MTE uses dedicated CPU instructions to tag heap allocations and check on access. The runtime cost in production is near-zero in async mode.

#### Activation (no Rust code changes)

In `AndroidManifest.xml`:

```xml
<application
    android:memtagMode="async"
    ... >
```

Modes:
- `async` — production-safe. Tag mismatches detected at some delay (typically next syscall); minimal latency cost.
- `sync` — debug only. Tag mismatches detected at the exact access; higher cost but precise.
- `off` — explicit disable.

For Rust code, MTE works transparently through bionic's allocator (jemalloc-based on Android). No `RUSTFLAGS`, no `#![cfg_attr(sanitize, ...)]`, no rebuild. The allocator inserts tags; the CPU verifies; mismatches deliver `SIGSEGV` with `si_code = SEGV_MTEAERR` (async) or `SEGV_MTESERR` (sync).

#### Production vs debug trade-off

| Setting | Detection | Latency cost | Use |
|---------|-----------|--------------|-----|
| `memtagMode="async"` | Eventually consistent, ~10-100 µs delay | ~3% on benchmarked workloads | Production builds |
| `memtagMode="sync"` | Exact, at the access | ~15-25% | Internal dogfood, soak tests |
| HWASan | Exact, at the access | ~15% RAM overhead + ~5% CPU | When MTE hardware not available |
| ASan | Exact | ~100% RAM + ~50% CPU | Legacy / when HWASan/MTE unavailable |

For RIPDPI: enable `memtagMode="async"` on release builds for arm64-v8a; the cost is well within the 5% size-baseline allowance. Keep HWASan available for CI runs on emulators (which typically don't expose MTE hardware) and for non-Pixel test devices.

#### Detection of MTE-caught crashes

Tombstone analysis: `adb pull /data/tombstones/<latest>` and grep for `MTEAERR` / `MTESERR`. The tombstone includes the tagged address and the access kind (read/write). For Rust-side debugging, pair with `addr2line` on the symbol-bearing build (see `rust-debugging` skill).

#### What MTE catches

Same UB class as HWASan: use-after-free on heap allocations, double-free, buffer overflow into adjacent tagged allocation, type confusion that crosses allocation boundaries. NOT caught: stack-based UAF (different mechanism — Stack-MTE is a separate, less-deployed extension), uninit reads (use MSan or Miri), data races (use TSan or loom).

For RIPDPI's hot-path code with raw pointers (`ripdpi-runtime-platform/src/linux.rs`, packet parsers using `ptr::read_unaligned`), MTE is the right production-grade safety net once the hardware supports it.

#### Rollout

1. Bump `targetSdkVersion` to 34+ (already done in RIPDPI).
2. Add `android:memtagMode="async"` to `<application>` in `AndroidManifest.xml`.
3. Run the full soak suite on a Pixel 8+ to verify no false positives surface.
4. Ship.

### 4. Miri -- interpreter for undefined behaviour

> **FFI LIMITATION**: Miri cannot execute `extern "C"`, JNI, libc syscalls, or inline assembly. This means JNI interop crates (`ripdpi-android`, `ripdpi-tunnel-android`) and any code calling libc directly cannot be tested under Miri without stubs. Use `#[cfg(miri)]` to provide mock implementations (see below).

```bash
# Install Miri (requires nightly)
rustup +nightly component add miri

# Run tests under Miri
cargo +nightly miri test --locked

# Run specific test
cargo +nightly miri test --locked test_name

# Strict provenance mode (recommended for CI)
MIRIFLAGS="-Zmiri-strict-provenance" cargo +nightly miri test --locked

# Disable isolation (allow file I/O, randomness)
MIRIFLAGS="-Zmiri-disable-isolation" cargo +nightly miri test --locked
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
    cargo +nightly miri test --locked
  env:
    MIRIFLAGS: "-Zmiri-disable-isolation -Zmiri-strict-provenance"

- name: ASan (nightly)
  run: |
    rustup component add rust-src --toolchain nightly
    RUSTFLAGS="-Z sanitizer=address" \
    cargo +nightly test --locked -Zbuild-std \
    --target x86_64-unknown-linux-gnu
```

## Aliasing assumptions transit via `Box`

**Severity: WARNING when mixing `Box<T>` with raw pointer FFI**

`Box<T>` carries `Unique<T>` semantics: the compiler assumes that the data inside is exclusively owned by the `Box` and that no other pointer aliases it (the `noalias` LLVM attribute). If you extract a `*mut T` from a `Box`, pass it to FFI, and the FFI code stores it alongside the live `Box`, you have aliasing — `Box` and the raw pointer both claim unique access.

Concrete failure mode:
```rust
let mut boxed = Box::new(MyStruct::new());
let raw: *mut MyStruct = &mut *boxed as *mut _;
unsafe { ffi_register(raw); }   // FFI stores `raw`
boxed.field = 42;               // Box load — LLVM may reorder past FFI store
// `raw` and `boxed` now alias — Tree Borrows flags this
```

Correct patterns:
- Use `Box::into_raw` to transfer ownership to FFI; never use the `Box` again.
- If FFI must borrow a pointer from Rust, go through `Pin<Box<T>>` so Rust knows the address is stable and does not optimize based on `noalias`.
- Run `MIRIFLAGS="-Zmiri-tree-borrows"` to catch this — Tree Borrows (PLDI 2025) is more precise than Stacked Borrows and surfaces this aliasing violation earlier.

Reference: `crabbook/raii_and_memory_safety.md` (footnote on Box/noalias interaction)

## Related skills

- `rust-debugging` -- GDB/LLDB debugging of Rust panics
- `rust-unsafe` -- unsafe Rust patterns and review checklist
- `rust-security` -- supply chain safety and memory-safe development
- `rust-ffi` -- C interoperability and safe FFI wrappers
- `memory-model` -- atomics, memory ordering, lock-free structures
