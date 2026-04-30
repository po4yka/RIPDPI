---
name: rust-panic-safety
description: Rust panic safety, JNI boundaries, Java exceptions, typed errors, unwrap audits, and catch_unwind.
---

# Rust Panic Safety — RIPDPI

## Why this matters

A Rust panic that unwinds across an `extern "C"` / `extern "system"` boundary is **undefined behaviour**. On Android this manifests as a SIGABRT with a corrupted ART stack — the JVM crashes without a usable backtrace. Every FFI entry point must intercept the panic, convert it to a typed error, and either throw a Java exception or return a sentinel value.

This skill formalizes the pattern RIPDPI already uses across its 4 JNI adapter crates and defines the rules for extending it.

## The uniform `JNI_OnLoad` pattern

All four JNI adapter crates wrap `JNI_OnLoad` identically. New adapters MUST match this shape:

```rust
#[unsafe(no_mangle)]
#[allow(improper_ctypes_definitions)]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    // Store the VM handle BEFORE catch_unwind so it survives a panic in init.
    let _ = JVM.set(vm);
    match std::panic::catch_unwind(|| {
        android_support::ignore_sigpipe();
        init_android_logging("ripdpi-<crate>-native");
        android_support::install_panic_hook();
        JNI_VERSION
    }) {
        Ok(version) => version,
        Err(_) => jni::sys::JNI_ERR,
    }
}
```

Anchors:
- `native/rust/crates/ripdpi-android/src/lib.rs:32-40` — canonical
- `native/rust/crates/ripdpi-tunnel-android/src/lib.rs:20-27`
- `native/rust/crates/ripdpi-warp-android/src/lib.rs:21-27`
- `native/rust/crates/ripdpi-relay-android/src/lib.rs:17-27`

**Rule**: `install_panic_hook()` must run inside `catch_unwind` so panic hooks are installed even if earlier initialisation fails. Do not move hook installation outside the closure.

## Per-method JNI entry-point pattern

For all other `extern "system" fn Java_*` methods, use `EnvUnowned::with_env` + `into_outcome`. The `Outcome` tri-state (`Ok` / `Err` / `Panic`) surfaces panics distinctly from errors:

```rust
pub extern "system" fn Java_com_poyka_ripdpi_core_FooNativeBindings_jniCreate(
    mut env: EnvUnowned<'_>,
    _thiz: JObject,
    config_json: JString,
) -> jlong {
    match env.with_env(move |env| -> jni::errors::Result<jlong> {
        Ok(create_session(env, config_json))
    }).into_outcome() {
        Outcome::Ok(handle) => handle,
        Outcome::Err(err) => {
            let _ = env.throw_new("java/lang/RuntimeException", err.to_string());
            0
        }
        Outcome::Panic(payload) => {
            let msg = payload.downcast_ref::<&str>().copied().unwrap_or("panic at JNI boundary");
            let _ = env.throw_new("java/lang/RuntimeException", msg);
            0
        }
    }
}
```

**Rule**: Never write a bare `extern "system" fn` body without `with_env` + `into_outcome`. Bare bodies propagate panics as UB.

## `.unwrap()` / `.expect()` policy

As of 2026-04-17, the Rust workspace contains **1,727 `.unwrap()` / `.expect()` calls in production code** (excluding `target/`, `tests/`, `benches/`). The pre-existing count is a latent risk: any of these can fire in a JNI-reachable context and corrupt the JVM.

### Allowed

- `.expect()` on a `Mutex`/`RwLock` `.lock()` result — standard practice; a poisoned lock is a fatal invariant violation.
- `.unwrap()` on a compile-time-known infallible conversion (`u32::try_from(42_u16)`).
- `.unwrap()` inside a `catch_unwind`-wrapped closure that is itself the FFI entry point — the panic is contained.
- Unwraps in `#[cfg(test)]` gated blocks, integration tests, benchmarks, fuzz harnesses.

### Disallowed (replace with typed error)

- `.unwrap()` / `.expect()` on `Result<_, E>` where `E: std::error::Error` — propagate with `?`.
- `.unwrap()` on `Option<T>` where the `None` case is a downstream input (JSON parse, DNS reply, user config). Convert to a domain error.
- `.expect("should never happen")` — if it really can't happen, write a safety-comment proof and use `unreachable_unchecked()` with a SAFETY block, OR model the invariant in the type system.

### Protocol for adding `.unwrap()`

If you must add one, include a line comment directly above explaining the infallibility proof:

```rust
// Infallible: `buf.len() <= MAX_U32` checked above.
let len: u32 = buf.len().try_into().unwrap();
```

A bare `.unwrap()` with no proof fails `pr-reviewer` in CI.

## `anyhow` vs `thiserror`

| Context | Use | Why |
|---|---|---|
| Library crate (anything a JNI adapter depends on) | `thiserror` | Callers need to match on error variants. Typed errors enforce exhaustive handling. |
| Application crate (`ripdpi-cli`, integration tests, `warp-core` orchestration layer) | `anyhow` | Top-level error propagation; human-readable report at the boundary. |
| JNI adapter crates | `thiserror` internally, convert to string at the throw site | The JNI boundary flattens to a string anyway; variants matter during propagation, not at the exit. |

**Rule**: a library crate `src/lib.rs` that imports `anyhow::Result` in its public API is a smell. The `rust-api-auditor` agent flags this.

Current state (2026-04-17): 2 crates use `anyhow` (`ripdpi-warp-core`, `ripdpi-dns-resolver`); 6 use `thiserror` (`ripdpi-hysteria2`, `ripdpi-ipfrag`, `ripdpi-tls-profiles`, `ripdpi-tun-driver`, `ripdpi-tunnel-config`, `ripdpi-xhttp`). Most crates lack either — they propagate via `Result<_, String>` or bare unwraps, which is the drift this skill exists to reverse.

## Converting Rust panics to Java exceptions

Prefer `RuntimeException` at the JNI throw site unless there is a specific typed Java exception the caller expects. Rationale: the Kotlin side already has a single catch handler for JNI failures; adding new Java exception types for each Rust panic class fragments that handling.

The panic message that reaches Java should be the panic payload's string form. `android_support::install_panic_hook()` also emits the full payload + backtrace to `android_logger` / logcat so the hand-off doesn't lose diagnostic information.

## Audit checklist (for reviewers)

- [ ] New `extern "system" fn JNI_OnLoad` matches the uniform pattern above.
- [ ] New per-method JNI entry point uses `with_env` + `into_outcome`.
- [ ] New `.unwrap()` / `.expect()` in non-test code carries an infallibility comment.
- [ ] New library crate declares `thiserror` errors for its public `Result` types.
- [ ] Public API of a library crate does not return `anyhow::Result`.
- [ ] Panic conversion at the JNI site uses `env.throw_new(...)` with the panic message.

## Related skills

- `rust-unsafe` — overlapping ground on `extern "system"` + raw pointer safety.
- `rust-async-internals` — panic propagation in tokio task bodies (tokio converts panics into `JoinError::Panic`; the JNI-side spawn sites must handle this).
- `rust-io-loop` — the NoopWaker-based manual poll pattern does NOT wrap its poll calls in `catch_unwind`; that's accepted because it runs inside an already-wrapped async task.
