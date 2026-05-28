---
name: rust-discipline
description: Rust code discipline for the RIPDPI workspace — API design, anti-patterns, panic policy, errors, RAII, allocation, concurrency, unsafe, and lints.
---

# Rust Discipline -- RIPDPI

## Purpose

Encode API-design discipline and catch high-signal Rust mistakes before they land in the native Rust workspace. Apply these rules when authoring or reviewing public (`pub`) and crate-public (`pub(crate)`) function signatures, struct definitions, and trait bounds anywhere in the workspace, and during code review, pre-merge self-check, and when tightening existing code.

Apply every rule to every changed signature in a diff, not only the first one.

---

## API Design

### Borrowed args over owned references

**Severity: WARNING**

Accept `&str` / `&[T]` / `&Path` instead of `&String` / `&Vec<T>` / `&PathBuf`. The owned-reference shapes force callers to hold an allocation even when they have a slice. The borrowed shapes accept both.

```rust
// BAD: forces caller to have a String
fn log(msg: &String) {}

// GOOD: accepts &str, String, Arc<str>, Cow<str>, etc.
fn log(msg: &str) {}
```

For generic inputs, prefer `impl AsRef<T>` over concrete `&T`:
```rust
fn open(path: impl AsRef<std::path::Path>) {}
```

Grep for violations: `rg 'fn .+\(&String\|&Vec<\|&PathBuf' native/rust/ --type rust -n`

Reference: `crabbook/borrowed_args.md`

### Don't store `&'a mut H` in struct fields

**Severity: CRITICAL**

Storing `&'a mut H` in a struct field infects every use-site with lifetime `'a`, creating lifetime-propagation failures when you try to store the struct in another struct or return it from a function. This is called **lifetime infection**.

```rust
// BAD: lifetime infection
struct Processor<'a> {
    handler: &'a mut dyn Handler,
}
// Every function that takes `Processor<'_>` must now carry the lifetime.
// Storing in Vec<Processor<'_>> is impossible.

// GOOD: generic H, implement Trait for &mut H and Box<H>
struct Processor<H: Handler> {
    handler: H,
}
impl<H: Handler> Handler for &mut H { ... }
impl<H: Handler> Handler for Box<H> { ... }
// Now Processor<&mut MyHandler> and Processor<Box<dyn Handler>> both work.
```

Macro shorthand to implement the delegation impls without boilerplate:
```rust
macro_rules! impl_handler_for_refs {
    ($T:ident) => {
        impl<H: $T + ?Sized> $T for &mut H { /* delegate all methods */ }
        impl<H: $T + ?Sized> $T for Box<H> { /* delegate all methods */ }
    };
}
```

Grep for violations: `rg "struct .+<'.+>\s*\{" native/rust/ --type rust -n` then check for `&'_ mut` fields.

Reference: `crabbook/impl_trait_references.md`

### HRTB-shaped callbacks are a soundness boundary

**Severity: WARNING**

`for<'a> Fn(&'a T) -> R` (where R does not depend on `'a`) is the correct shape for callbacks that are allowed to save the return value without the reference. The HRTB `for<'a>` prevents the callback from storing `&'a T` — it must extract owned data and return it.

If the callback stores a reference and you used a non-HRTB bound, the borrow checker will eventually fail to compile a valid use site. If you widened the lifetime to avoid the error, you likely have unsound code.

Rule: when a callback must not be able to store the argument reference, use `for<'a>` explicitly. When the callback's return type depends on the argument lifetime (e.g., returning a slice of the input), use GATs or a named lifetime.

Reference: `crabbook/borrowing_in_generic_functions.md`

---

## Anti-Patterns

### Panic discipline

- **No `.unwrap()` in non-test code.** Replace with `?` for propagation or `.expect("<documented invariant>")` when the invariant is genuinely unconditional.
- **`.expect` messages must be invariants, not wishes.** `"should never fail"` is not acceptable. Write what must be true: `"JavaVM registered in JNI_OnLoad"`, `"channel never closed: sender held for program lifetime"`.
- **`panic!` / `unreachable!` / `todo!`** are reserved for impossible cases. Each occurrence must carry a reason: `unreachable!("TcpChainStep kind {kind:?} filtered earlier")`.
- **`#[should_panic]`** is test-only. Do not structure library code around expected panics; return `Result` instead.
- **Panics must not cross FFI.** See also: `rust-unsafe`, `rust-debugging`.

### Error propagation

- Prefer `?` over `match Result { Ok(v) => v, Err(e) => return Err(e.into()) }` for pass-through.
- Use `anyhow::Context::context` for static messages and `with_context` only when the message requires allocation or formatting. Calling `with_context(|| format!(...))` on a happy path is an allocation hazard.
- **Library crates never return `Box<dyn std::error::Error>`.** Define a crate-level error enum with `thiserror` and translate at the boundary.
- Push `map_err` adapters to module boundaries (public APIs), not inside leaf functions where they obscure the original error source.

### Drop / RAII

- Prefer `std::os::fd::OwnedFd` / `OwnedSocket` over raw `i32`. Raw file descriptors leak on all error paths that do not explicitly `close()`.
- When implementing `Drop` for cleanup-critical types, document the cleanup order and any ordering dependencies between fields. Struct field declaration order is the drop order.
- Use `scopeguard::defer!` for fallible cleanup that must run on all exit paths (including panics when not `panic = "abort"`).
- Cross-reference the `rust-unsafe` dup-before-own rule for JVM-provided fds.

### Match exhaustiveness

- **No `_ =>` wildcard on internal (crate-private) enums.** Wildcards silently absorb new variants, defeating the compiler's exhaustiveness check. Replace with explicit arms.
- Mark cross-crate public enums `#[non_exhaustive]` so downstream code cannot break when a variant is added.
- For small internal enums (< 8 variants), list every arm explicitly even when the handling is identical — this forces a review when a variant is added.
- `if let` / `let else` / `while let` are fine for single-variant extraction; they do not defeat exhaustiveness.

### Allocation in hot paths

- **No `Vec::new()`, `String::from`, `format!`, `to_owned()`, or `.to_string()` inside:** `io_loop` ticks, packet classifier paths, per-byte parsers, strategy-probe candidate loops, or DNS resolver fast paths.
- Prefer `SmallVec`/`ArrayVec` with a capacity matching the 95th percentile case; fall back to heap only on overflow.
- Reuse buffers via `&mut Vec<u8>` out-parameters instead of returning `Vec<u8>` by value.
- Avoid `.to_string()` in error constructors on hot paths; pass `&'static str` or an enum discriminant instead, then format at the logging boundary.
- See also: `rust-performance` for measuring allocation cost with `cargo-bloat`/`cargo-llvm-lines`.

### Concurrency primitive selection

- **`RwLock` for read-heavy state** (at least 3:1 read:write ratio); `Mutex` otherwise. An `RwLock` under write contention is slower than a `Mutex`.
- Document lock order at the struct level with a `// Lock order: a -> b -> c` comment. Nested lock acquisition must follow this order.
- Prefer `parking_lot` for contended locks (faster, smaller, no poisoning). Stay on `std::sync` only when `Arc<Mutex<T>>` needs to be `Send`-over-a-`!Send` guard pattern that parking_lot changes.
- **Never hold a lock across `.await`.** Acquire, extract what you need, drop the guard explicitly before any `.await`.
- See also: `rust-async-internals`, `memory-model`.

### Atomic memory ordering audits

- Every new `AtomicBool`/`AtomicUsize`/`AtomicPtr` call site must carry a `// Ordering:` comment explaining the happens-before contract.
- Do not copy `Relaxed` from neighbouring code without re-auditing — ordering is per-use, not per-type.
- Publish/subscribe atomics (flag signalling a completed write) require `Release` on the store and `Acquire` on the load. `Relaxed` here is silently wrong on ARM64.
- Add a loom or targeted test for any new publish/subscribe atomic, mirroring the `ripdpi-monitor-engine` scan cancellation pattern.
- See also: `memory-model`.

### `spawn_blocking` vs dedicated thread

- **`spawn_blocking`** for bounded CPU work (< 100ms target) that should share the tokio blocking pool. Good fit: synchronous DNS, single ioctl, short file I/O.
- **`std::thread::spawn`** for long-lived loops or large-ish blocking work that would otherwise starve the blocking pool. The `ripdpi-ws-tunnel` relay thread is the reference pattern.
- Never call blocking syscalls (`std::thread::sleep`, `std::fs::*`, `std::net::*`) directly inside async code without one of these escapes.
- See also: `rust-async-internals`.

### Unsafe boundary encapsulation

- Within a crate, keep `unsafe fn` `pub(crate)` behind a safe `pub` wrapper. External callers should never need to write `unsafe { ... }` to use the crate's API.
- Every `unsafe` block requires a `// Safety:` comment, even in crates where `missing_safety_doc` is allowed workspace-wide.
- The `missing_safety_doc` and `not_unsafe_ptr_arg_deref` workspace-wide `allow`s exist **only for `extern "system"` JNI entry points** in `ripdpi-android` and `ripdpi-tunnel-android`. Internal `unsafe fn` in non-FFI modules must still carry a `# Safety` rustdoc section.
- See also: `rust-unsafe`, `rust-jni-bridge`.

### Lint non-regression

- Never silence `clippy::correctness` or `clippy::suspicious` findings with `#[allow(...)]`. These are workspace-deny; fix the code instead.
- New `ignore` entries in `deny.toml` require a tracking issue and the 90/30/7-day SLA from `rust-security` (severity-scaled).
- Keep `clippy.toml`'s `disallowed-methods` enforced on new code (notably `Iterator::for_each` is banned).
- `#[allow(clippy::pedantic_*)]` is acceptable at the module or block level with a one-line justification; crate-wide pedantic allows are not.

### `impl Drop` blocks partial moves

**Severity: WARNING**

When a struct implements `Drop`, Rust forbids moving any field out of it — even in `Drop::drop` itself. This is a common surprise when you want to consume a `Vec<T>` field after signalling completion.

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

Rule: before adding `impl Drop` to a struct, check whether downstream code (or `Drop::drop` itself) needs to consume a field. If yes, use a dedicated guard type with `ManuallyDrop` + `#[repr(transparent)]`.

Reference: `crabbook/you_dont_want_drop.md`

### Value-passing performance trap

**Severity: WARNING on hot paths**

`fn(T) -> T` for a large struct (> 4 pointer-sized fields) forces a `memcpy` per call — rustc cannot optimize it back to `&mut T` mutation because panic semantics require the original to remain valid until the function returns.

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

Use `fn(T) -> T` only for:
- state-machine transitions where ownership transfer is the semantic (e.g., `Builder::set_foo(mut self) -> Self`)
- small structs (≤ 4 pointer-sized fields)

Profile with `cargo-flamegraph` or Criterion before choosing value-passing on any path that runs per-packet or per-connection.

Reference: `crabbook/consume_and_borrowing.md`

### `Hash` + `PartialEq` contract violation

**Severity: CRITICAL**

The standard library requires: `k1 == k2` implies `hash(k1) == hash(k2)`. If you implement `PartialEq` manually but derive `Hash` (or vice versa), `HashMap` and `HashSet` produce silently incorrect results.

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

Fix: when implementing custom `PartialEq`, always implement a matching custom `Hash` that hashes the same normalized form. Add a test: insert via one form, look up via the other.

### `#[derive(Clone)]` on resource-backed types

**Severity: WARNING**

Deriving `Clone` on a struct that contains a resource-backed type (`Arc<Mutex<Connection>>`, `Arc<Pool>`) does not duplicate the resource — it clones the handle. Document explicitly: `/// Cloning shares the underlying connection pool.`

For raw handles (`OwnedFd`, `TcpStream`), `Clone` won't compile — the compiler catches it. The silent case is always `Arc<T>`.

### `Deref` on non-pointer types causes method collision

**Severity: WARNING**

Implementing `Deref<Target = T>` on a non-pointer newtype makes all of `T`'s methods accessible via auto-deref. This creates silent method shadowing and semver breakage when either type adds a new method.

```rust
// BAD: Deref on a non-pointer business type
struct UserId(u64);
impl std::ops::Deref for UserId {
    type Target = u64;
    fn deref(&self) -> &u64 { &self.0 }
}
// Now UserId gets all u64 arithmetic methods via auto-deref — leaks implementation.
```

Prefer explicit accessor methods or `AsRef`/`From` conversions instead.

### `parking_lot`/`tokio` mutexes do not poison on panic

**Severity: WARNING**

`std::sync::Mutex` poisons itself when the thread holding the guard panics. Both `parking_lot::Mutex` and `tokio::sync::Mutex` explicitly remove poisoning: the lock is simply released on panic. Code migrating from `std` may assume poisoning is preserved.

Rule: when using `parking_lot` or `tokio` mutexes, do not rely on poison detection. If the guarded data can be left inconsistent by a panicking writer, add explicit invariant validation on the reader side, or use `std::sync::Mutex`.

### Integer overflow: panics in debug, wraps silently in release

**Severity: WARNING**

Rust panics on integer overflow in debug builds but wraps silently in release mode. Common victims: counter increments, byte-length calculations, index arithmetic in packet parsers.

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

### `Arc` reference cycles without `Weak` leak permanently

**Severity: WARNING**

Rust's reference counting cannot break cycles. Two `Arc`s pointing to each other will never be deallocated. No panic or error occurs; the process simply grows indefinitely.

```rust
// CYCLE: pool → connection → pool
struct Pool { connections: Vec<Arc<Connection>> }
struct Connection { pool: Arc<Pool> }
// Neither will ever be dropped.

// FIX: use Weak for back-references
struct Connection { pool: Weak<Pool> }
```

Rule: in any parent-child relationship where the child needs a reference back to the parent, always use `Weak<T>` for the child→parent direction.

### Lifetime laundering across input/storage

**Severity: CRITICAL**

A function that takes `&'a T` and writes derived references into a long-lived `HashMap<_, &'a U>` looks elegant — but the shared `'a` forces every call site to choose a single lifetime spanning both the input borrow and the cache. In real code the intersection collapses to empty almost immediately.

```rust
// BAD: 'a binds the input slice AND the cache values together.
fn first_word<'a>(s: &'a str, cache: &mut HashMap<String, &'a str>) -> &'a str {
    if let Some(cached) = cache.get(s) { return cached; }
    let word = s.split_whitespace().next().unwrap_or("");
    cache.insert(s.to_string(), word);
    word
}
// The cache outlives any single `s`. First call locks 'a to the first input,
// second call from a different scope fails to compile.

// GOOD: split lifetimes with documented contract
fn first_word<'cache, 'input: 'cache>(
    s: &'input str,
    cache: &mut HashMap<String, &'cache str>,
) -> &'cache str { /* ... */ }

// BETTER: store owned data, decouple lifetimes entirely
fn first_word(s: &str, cache: &mut HashMap<String, String>) -> &str { /* ... */ }
```

Rule: when an `&'a` parameter appears in both an input position and a storage parameter, either split lifetimes with a documented `'input: 'cache` bound, or store owned data. The single-lifetime form is almost always a trap planted for the next caller.

Grep for violations: `rg "fn .+<'[a-z]>.*HashMap.*&'[a-z]" native/rust/ --type rust -n`

### Blanket impl in public API is a semver hazard

**Severity: CRITICAL**

`impl<T: Foo> Bar for T` on a `pub trait Bar` lets downstream crates write `impl Bar for MyType` only as long as `MyType: !Foo`. When a future minor release of your crate adds another blanket impl, narrows a bound, or implements `Foo` for a type the downstream crate uses, downstream compilation breaks — the error surfaces only at the consumer's CI, months after the change ships.

```rust
// HAZARD: any downstream `impl Bar for MyType where MyType: Display` may
// conflict with this on a future bump.
pub trait Bar { fn bar(&self) -> String; }
impl<T: Display> Bar for T {
    fn bar(&self) -> String { format!("{}", self) }
}

// SAFE: trait is sealed; only this crate can implement it. Blanket impl is fine
// because no downstream impl can ever exist.
mod private { pub trait Sealed {} }
pub trait Bar: private::Sealed { fn bar(&self) -> String; }
impl<T: Display + private::Sealed> Bar for T { /* ... */ }
```

Rule: a blanket `impl<T: ...> PubTrait for T` is allowed only if `PubTrait` is sealed (extends a private trait). Otherwise, write explicit per-type impls. The cost of going from sealed → unsealed later is free; the cost of going from blanket → explicit later is a downstream-breaking semver bump.

Grep for violations: `rg "^impl<T(:\s*\w[\w:+ ]*)?>\s+\w+\s+for\s+T\b" native/rust/ --type rust -n`

### Large stack arrays and the `Box::new([0u8; N])` pitfall

**Severity: WARNING**

`Box::new([0u8; N])` does NOT allocate `N` bytes directly on the heap. The expression first constructs `[0u8; N]` on the caller's stack, then `Box::new` copies it into a heap allocation. In debug builds (no placement-new optimization), this overflows the default ~1 MiB Android thread stack for `N ≥ ~256 KiB`. Release builds sometimes optimize via NRVO but the optimization is fragile — any intermediate `let buf = Box::new([0u8; N]);` may materialize the stack copy.

```rust
// BAD: stack-overflows in debug, brittle NRVO in release.
let buf: Box<[u8; 1024 * 1024]> = Box::new([0u8; 1024 * 1024]);

// BAD: returning large arrays by value forces memcpy via stack.
fn make_buf() -> [u8; 1024 * 1024] { [0u8; 1024 * 1024] }

// GOOD: allocate on the heap from the start.
let buf: Box<[u8]> = vec![0u8; 1024 * 1024].into_boxed_slice();

// GOOD (nightly): direct heap allocation without zeroing the stack.
let buf: Box<[u8]> = unsafe {
    let mut b = Box::<[u8]>::new_uninit_slice(1024 * 1024);
    std::ptr::write_bytes(b.as_mut_ptr() as *mut u8, 0, 1024 * 1024);
    b.assume_init()
};
```

Rule: any array larger than 16 KiB constructed for heap residence must use `Vec::into_boxed_slice` or `Box::new_uninit_slice`. Never write `Box::new([T; N])` for large `N`, and never return `[T; N]` by value for large `N`. Hot-path code paths additionally fall under the "no allocation" rule above.

Grep for violations:
```bash
rg "Box::new\(\s*\[0?[a-z0-9_]+\s*;\s*[0-9]{4,}" native/rust/ --type rust -n
rg "fn .* -> \[[a-z0-9_]+\s*;\s*[0-9]{4,}\]" native/rust/ --type rust -n
```

---

## Quick review checklist

Apply to every changed public or `pub(crate)` API and every Rust PR:

**API design**
1. Any `&String`, `&Vec<T>`, or `&PathBuf` parameter? → use `&str`, `&[T]`, `&Path`, or `impl AsRef<...>`.
2. Any `&'a mut Trait` stored in a struct field? → use generic `H: Trait` instead.
3. Any callback without `for<'a>` where the caller must not store the reference? → add HRTB.

**Anti-patterns**
4. Any new `.unwrap()` or bare `.expect()` (no invariant in the message) outside tests?
5. Any `Box<dyn std::error::Error>` returned from a library crate?
6. Any raw `i32` file descriptor held across error paths? Any `Drop` impl without documented ordering?
7. Any `_ =>` arm in a match over an internal enum?
8. Any allocation inside an `io_loop` tick / packet path / parser hot path?
9. Any lock held across `.await`? Any `RwLock` protecting a write-heavy field?
10. Any new atomic without a `// Ordering:` comment? Any `Relaxed` on a publish/subscribe flag?
11. Any blocking syscall inside async without `spawn_blocking` or a dedicated thread?
12. Any internal `unsafe fn` without a `# Safety` rustdoc section?
13. Any new `#[allow(clippy::correctness | suspicious)]`? Any new `deny.toml` ignore without an SLA?
14. Any `impl Drop` on a struct where a field needs to be consumed? → dedicated guard type with `ManuallyDrop`.
15. Any `fn(T) -> T` taking a large struct (> 4 pointer fields) on a hot path (per-packet/per-connection)?
16. Any custom `PartialEq` without a matching custom `Hash` (or vice versa) on a `HashMap`/`HashSet` key?
17. Any `#[derive(Clone)]` on a struct containing `Arc<T>` where callers might expect an isolated copy?
18. Any `Deref` implementation on a non-smart-pointer newtype?
19. Any migration from `std::sync::Mutex` to `parking_lot` or `tokio::sync::Mutex` that relied on poison detection?
20. Any unchecked arithmetic on values derived from external input in packet parsers or length calculations?
21. Any `Arc<T>` that points back to its parent container (pool → connection → pool cycle)?
22. Any function taking `&'a T` that also writes references into a storage parameter sharing the same `'a`? → split lifetimes with `'input: 'cache` or store owned data.
23. Any `impl<T: ...> PubTrait for T` on a public trait that is NOT sealed (does not extend a private `Sealed` trait)? → seal the trait or write explicit per-type impls.
24. Any `Box::new([T; N])` or return-by-value of `[T; N]` for `N` > 16 KiB? → use `Vec::into_boxed_slice` or `Box::new_uninit_slice`.
25. Any blanket impl added without a tracking comment confirming the trait is sealed?

If the answer to any is yes, the change needs revision before merge.
