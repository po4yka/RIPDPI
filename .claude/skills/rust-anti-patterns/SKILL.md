---
name: rust-anti-patterns
description: Rust code discipline for panic policy, errors, RAII, allocation, concurrency, unsafe, and lints.
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

## `Drop` blocks partial moves

**Severity: WARNING**

When a struct implements `Drop`, Rust forbids moving any field out of it — even in `Drop::drop` itself. This is a common surprise when you want to, say, consume a `Vec<T>` field after signalling completion.

```rust
// BAD: impl Drop prevents moving out of `data`
struct Sink {
    data: Vec<u8>,
}
impl Drop for Sink {
    fn drop(&mut self) {
        let owned = std::mem::take(&mut self.data); // forced to use take()
    }
}

// GOOD: use a dedicated guard type; `data` stays moveable
#[repr(transparent)]
struct SinkGuard(std::mem::ManuallyDrop<Vec<u8>>);
impl Drop for SinkGuard {
    fn drop(&mut self) {
        // SAFETY: only dropped once here
        let owned = unsafe { std::mem::ManuallyDrop::take(&mut self.0) };
        flush(owned);
    }
}
```

Rule: before adding `impl Drop` to a struct, check whether downstream code (or the struct's own `Drop::drop`) needs to consume a field. If yes, use a dedicated guard type with `ManuallyDrop` + `#[repr(transparent)]` on the guard instead.

Reference: `crabbook/you_dont_want_drop.md`

## Value-passing performance trap

**Severity: WARNING on hot paths**

`fn(T) -> T` for a large struct (> 4 pointer-sized fields) forces a `memcpy` per call — rustc cannot optimize it back to `&mut T` mutation because panic semantics require the original to remain valid until the function returns. On hot paths this silently doubles allocation traffic.

```rust
// BAD for large structs on hot path: forces memcpy in/out
fn transform(mut state: BigState) -> BigState {
    state.counter += 1;
    state
}

// GOOD for hot paths: in-place mutation
fn transform(state: &mut BigState) {
    state.counter += 1;
}
```

Use `fn(T) -> T` (value-passing) only for:
- state-machine transitions where ownership transfer is the semantic (e.g., `Builder::set_foo(mut self) -> Self`)
- small structs (≤ 4 pointer-sized fields)

Profile with `cargo-flamegraph` or Criterion before choosing value-passing on any path that runs per-packet or per-connection.

Reference: `crabbook/consume_and_borrowing.md`

## `Hash` + `PartialEq` contract violation

**Severity: CRITICAL**

The standard library requires: `k1 == k2` implies `hash(k1) == hash(k2)`. If you implement `PartialEq` manually but derive `Hash` (or vice versa), the compiler does not warn — but `HashMap` and `HashSet` produce silently incorrect results: equal keys may be treated as different (duplicates inserted) or equal items may not be found after insertion.

Common scenario: adding case-insensitive string comparison via manual `PartialEq` while forgetting to implement a matching custom `Hash`:
```rust
// BUG: derived Hash uses original case; manual PartialEq ignores it
#[derive(Hash)]
struct Tag(String);
impl PartialEq for Tag {
    fn eq(&self, other: &Self) -> bool {
        self.0.to_lowercase() == other.0.to_lowercase()
    }
}
impl Eq for Tag {}
// HashSet<Tag> will store "Foo" and "foo" as different entries!
```

Fix: when implementing custom `PartialEq`, always implement a matching custom `Hash` that hashes the same normalized form used for equality. Add a test: insert via one form, look up via the other.

## `#[derive(Clone)]` on resource-backed types

**Severity: WARNING**

Deriving `Clone` on a struct that contains a resource-backed type (database connection, `Arc<Mutex<Connection>>`, `Arc<Pool>`) does not duplicate the resource — it clones the handle. Two clones share the same underlying resource. This creates unintended shared mutable state when teams derive `Clone` to make config/connection structs easy to pass around.

```rust
// Looks harmless: clone for thread B
#[derive(Clone)]
struct AppState {
    pool: Arc<PgPool>,   // Clone = new Arc pointing to SAME pool
    config: Arc<Config>, // Clone = new Arc pointing to SAME config
}
// state.clone() does NOT create a new pool — it shares it.
```

This is usually the correct behavior for `Arc`-wrapped resources. The hazard is when teams expect `Clone` to produce an isolated copy (for testing, or for per-connection isolation) but get aliasing instead. Document explicitly: `/// Cloning shares the underlying connection pool.`

For raw handles (`OwnedFd`, `TcpStream`), `Clone` won't compile — the compiler catches it. The silent case is always `Arc<T>`.

## `Deref` on non-pointer types causes method collision

**Severity: WARNING**

Implementing `Deref<Target = T>` on a newtype `Wrapper` makes all of `T`'s methods accessible on `Wrapper` via auto-deref. This is correct for smart pointer types (`Box`, `Arc`, `Rc`, `Vec`, `String`). For application newtypes, it creates two hazards:

1. **Method shadowing**: if `Wrapper` defines method `foo()` and `T` later adds a method also named `foo()`, the resolution changes silently. Callers who expected `T::foo()` via deref now get `Wrapper::foo()` — no compile error, different behavior.

2. **Semver breakage**: if `Wrapper` adds a new method matching an existing `T` method, existing callers' behavior changes without them updating their code.

```rust
// BAD: Deref on a non-pointer business type
struct UserId(u64);
impl std::ops::Deref for UserId {
    type Target = u64;
    fn deref(&self) -> &u64 { &self.0 }
}
// Now UserId gets all u64 arithmetic methods via auto-deref — leaks implementation.
```

The Rust API Guidelines explicitly warn against `Deref` on non-pointer types. Prefer explicit accessor methods or `AsRef`/`From` conversions instead.

## `parking_lot`/`tokio` mutexes do not poison on panic

**Severity: WARNING**

`std::sync::Mutex` poisons itself when the thread holding the guard panics, making subsequent `lock()` calls return `Err(PoisonError)`. Both `parking_lot::Mutex` and `tokio::sync::Mutex` explicitly remove poisoning: the lock is simply released on panic, and subsequent locks succeed.

Code that migrates from `std` to `parking_lot` (for performance) or `tokio::sync::Mutex` (for async) often assumes poisoning is preserved. If the code catches panics via `std::panic::catch_unwind` and continues, the formerly-protected data may be in an inconsistent state with no signal from the mutex.

Rule: when using `parking_lot` or `tokio` mutexes, do not rely on poison detection. If the guarded data can be left inconsistent by a panicking writer, add explicit invariant validation on the reader side, or use `std::sync::Mutex` and handle the `PoisonError`.

## Integer overflow: panics in debug, wraps silently in release

**Severity: WARNING**

Rust panics on integer overflow in debug builds but wraps silently (two's complement) in release mode. This is not UB (unlike C), but the behavioral divergence between build modes is a production trap: code that panics during development alerts engineers; the same overflow in production silently corrupts values.

Common victims: counter increments, byte-length calculations for buffer allocation, index arithmetic in packet parsers.

```rust
let total: u32 = a + b; // panics in debug on overflow; wraps in release

// CORRECT for fallible paths:
let total = a.checked_add(b).ok_or(Error::Overflow)?;

// CORRECT for intentional wrapping (ring buffers, sequence numbers):
let total = a.wrapping_add(b);

// CORRECT for saturation (rate limiters, clamps):
let total = a.saturating_add(b);
```

Clippy lint: `clippy::arithmetic_side_effects` catches unchecked arithmetic. Enable it for packet parsers and any code path that computes lengths from untrusted input.

## `Arc` reference cycles without `Weak` leak permanently

**Severity: WARNING**

Rust's reference counting cannot break cycles. Two `Arc`s pointing to each other (or any cycle of any length) will never be deallocated — their reference counts never reach zero. This is not caught by the borrow checker or any runtime tool in safe code; no panic or error occurs. The allocator simply never frees the nodes, and the process grows indefinitely.

Common structure in async code: connection objects that hold a reference to their parent pool, and the pool holds references to all connections.

```rust
// CYCLE: pool → connection → pool
struct Pool { connections: Vec<Arc<Connection>> }
struct Connection { pool: Arc<Pool> }
// Neither will ever be dropped.

// FIX: use Weak for back-references
struct Connection { pool: Weak<Pool> }
// pool.upgrade() to access the pool when needed
```

Rule: in any parent-child relationship where the child needs a reference back to the parent, always use `Weak<T>` for the child→parent direction. Async supervisor trees, actor systems, and connection pools are the most common victims. Detect via production memory growth metrics or the `arc-swap` pattern audit.

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
11. Any `impl Drop` on a struct where a field needs to be consumed (moved out)? Prefer a dedicated guard type with `ManuallyDrop`.
12. Any `fn(T) -> T` or `fn(T)` taking a large struct (> 4 pointer fields) on a hot path (per-packet/per-connection)?
13. Any custom `PartialEq` without a matching custom `Hash` (or vice versa) on a type used in `HashMap`/`HashSet`?
14. Any `#[derive(Clone)]` on a struct containing `Arc<T>` where callers might expect an isolated copy?
15. Any `Deref` implementation on a non-smart-pointer newtype?
16. Any migration from `std::sync::Mutex` to `parking_lot` or `tokio::sync::Mutex` that relied on poison detection for correctness?
17. Any unchecked `+`/`-`/`*` arithmetic on values derived from external input in packet parsers or length calculations?
18. Any `Arc<T>` that points back to its parent container (pool → connection → pool cycle)?

If the answer to any is yes, the change needs revision before merge.
