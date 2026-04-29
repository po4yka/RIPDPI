---
name: rust-anti-patterns
description: >
  Rust anti-patterns and discipline rules for the RIPDPI native workspace:
  panic policy, error propagation, Drop/RAII, hot-path allocation,
  concurrency primitives, atomic ordering, unsafe boundaries,
  clippy/deny regressions.
---

# Rust anti-patterns -- RIPDPI

## Purpose

Catch high-signal Rust mistakes before they land in the 23-crate native workspace. This skill extends the existing style/unsafe/async/security/memory-model skills by covering gap areas they do not address directly. Apply these rules in code review, pre-merge self-check, and when tightening existing code.

Each section lists the pattern to avoid and the corrective action. Where a neighbouring skill already covers adjacent surface, a **See also:** pointer is provided instead of duplicating content.

## Panic discipline

- **No `.unwrap()` in non-test code.** Replace with `?` for propagation or `.expect("<documented invariant>")` when the invariant is genuinely unconditional. Re-stated from `rust-code-style`; enforced by review, not lint.
- **`.expect` messages must be invariants, not wishes.** `"should never fail"` is not acceptable. Write what must be true: `"JavaVM registered in JNI_OnLoad"`, `"channel never closed: sender held for program lifetime"`.
- **`panic!` / `unreachable!` / `todo!`** are reserved for impossible cases. Each occurrence must carry a reason: `unreachable!("TcpChainStep kind {kind:?} filtered earlier")`.
- **`#[should_panic]`** is test-only. Do not structure library code around expected panics; return `Result` instead.
- **Panics must not cross FFI.** Re-stated for completeness. See also: `rust-unsafe`, `rust-debugging`.

## Error propagation

- Prefer `?` over `match Result { Ok(v) => v, Err(e) => return Err(e.into()) }` for pass-through.
- Use `anyhow::Context::context` for static messages and `with_context` only when the message requires allocation or formatting. Calling `with_context(|| format!(...))` on a happy path is an allocation hazard.
- **Library crates never return `Box<dyn std::error::Error>`.** Define a crate-level error enum with `thiserror` and translate at the boundary.
- Push `map_err` adapters to module boundaries (public APIs), not inside leaf functions where they obscure the original error source.
- See also: `rust-code-style`.

## Drop / RAII

- Prefer `std::os::fd::OwnedFd` / `OwnedSocket` over raw `i32`. Raw file descriptors leak on all error paths that do not explicitly `close()`.
- When implementing `Drop` for cleanup-critical types, document the cleanup order and any ordering dependencies between fields. Struct field declaration order is the drop order.
- Use `scopeguard::defer!` for fallible cleanup that must run on all exit paths (including panics when not `panic = "abort"`).
- Cross-reference the `rust-unsafe` dup-before-own rule for JVM-provided fds.

## Match exhaustiveness

- **No `_ =>` wildcard on internal (crate-private) enums.** Wildcards silently absorb new variants, defeating the compiler's exhaustiveness check. Replace with explicit arms.
- Mark cross-crate public enums `#[non_exhaustive]` so downstream code cannot break when a variant is added.
- For small internal enums (< 8 variants), list every arm explicitly even when the handling is identical -- this forces a review when a variant is added.
- `if let` / `let else` / `while let` are fine for single-variant extraction; they do not defeat exhaustiveness because they are not matches.

## Allocation in hot paths

- **No `Vec::new()`, `String::from`, `format!`, `to_owned()`, or `.to_string()` inside:** `io_loop` ticks, packet classifier paths, per-byte parsers, strategy-probe candidate loops, or DNS resolver fast paths.
- Prefer `SmallVec`/`ArrayVec` with a capacity matching the 95th percentile case; fall back to heap only on overflow.
- Reuse buffers via `&mut Vec<u8>` out-parameters instead of returning `Vec<u8>` by value.
- Avoid `.to_string()` in error constructors on hot paths; pass `&'static str` or an enum discriminant instead, then format at the logging boundary.
- See also: `rust-profiling` for measuring allocation cost with `cargo-bloat`/`cargo-llvm-lines`.

## Concurrency primitive selection

- **`RwLock` for read-heavy state** (at least 3:1 read:write ratio); `Mutex` otherwise. An `RwLock` under write contention is slower than a `Mutex`.
- Document lock order at the struct level with a `// Lock order: a -> b -> c` comment. Nested lock acquisition must follow this order.
- Prefer `parking_lot` for contended locks (faster, smaller, no poisoning). Stay on `std::sync` only when `Arc<Mutex<T>>` needs to be `Send`-over-a-`!Send` guard pattern that parking_lot changes.
- **Never hold a lock across `.await`.** Acquire, extract what you need, drop the guard explicitly before any `.await`.
- See also: `rust-async-internals`, `memory-model`.

## Atomic memory ordering audits

- Every new `AtomicBool`/`AtomicUsize`/`AtomicPtr` call site must carry a `// Ordering:` comment explaining the happens-before contract (what prior writes must be visible, to whom).
- Do not copy `Relaxed` from neighbouring code without re-auditing -- ordering is per-use, not per-type.
- Publish/subscribe atomics (flag signalling a completed write) require `Release` on the store and `Acquire` on the load. `Relaxed` here is silently wrong on ARM64.
- Add a loom or targeted test for any new publish/subscribe atomic, mirroring the `ripdpi-monitor::engine.rs` pattern.
- See also: `memory-model`.

## `spawn_blocking` vs dedicated thread

- **`spawn_blocking`** for bounded CPU work (< 100ms target) that should share the tokio blocking pool. Good fit: synchronous DNS, single ioctl, short file I/O.
- **`std::thread::spawn`** for long-lived loops or large-ish blocking work that would otherwise starve the blocking pool. The `ripdpi-ws-tunnel` relay thread is the reference pattern.
- Never call blocking syscalls (`std::thread::sleep`, `std::fs::*`, `std::net::*`) directly inside async code without one of these escapes.
- See also: `rust-async-internals`.

## Unsafe boundary encapsulation

- Within a crate, keep `unsafe fn` `pub(crate)` behind a safe `pub` wrapper. External callers should never need to write `unsafe { ... }` to use the crate's API.
- Every `unsafe` block requires a `// Safety:` comment, even in crates where `missing_safety_doc` is allowed workspace-wide.
- The `missing_safety_doc` and `not_unsafe_ptr_arg_deref` workspace-wide `allow`s exist **only for `extern "system"` JNI entry points** in `ripdpi-android` and `ripdpi-tunnel-android`. Internal `unsafe fn` in non-FFI modules must still carry a `# Safety` rustdoc section describing preconditions.
- See also: `rust-unsafe`, `rust-jni-bridge`.

## Lint non-regression

- Never silence `clippy::correctness` or `clippy::suspicious` findings with `#[allow(...)]`. These are workspace-deny for a reason; fix the code instead.
- New `ignore` entries in `deny.toml` require a tracking issue and the 90/30/7-day SLA from `rust-security` (severity-scaled).
- Keep `clippy.toml`'s `disallowed-methods` enforced on new code (notably `Iterator::for_each` is banned).
- `#[allow(clippy::pedantic_*)]` is acceptable at the module or block level with a one-line justification; crate-wide pedantic allows are not.
- See also: `rust-security`, `cargo-workflows`.

## Quick review checklist

When reviewing a Rust PR, walk this list top-to-bottom:

1. Any new `.unwrap()` or bare `.expect()` (no invariant in the message) outside tests?
2. Any `Box<dyn std::error::Error>` returned from a library crate?
3. Any raw `i32` file descriptor held across error paths? Any `Drop` impl without documented ordering?
4. Any `_ =>` arm in a match over an internal enum?
5. Any allocation inside an `io_loop` tick / packet path / parser hot path?
6. Any lock held across `.await`? Any `RwLock` protecting a write-heavy field?
7. Any new atomic without a `// Ordering:` comment? Any `Relaxed` on a publish/subscribe flag?
8. Any blocking syscall inside async without `spawn_blocking` or a dedicated thread?
9. Any internal `unsafe fn` without a `# Safety` rustdoc section?
10. Any new `#[allow(clippy::correctness | suspicious)]`? Any new `deny.toml` ignore without an SLA?

If the answer to any is yes, the change needs revision before merge.
