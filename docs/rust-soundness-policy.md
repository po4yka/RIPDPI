# Rust Soundness Policy

> Status: enforced
> Owner: native Rust maintainers
> Enforced by:
> - `[workspace.lints]` in `native/rust/Cargo.toml`
> - `scripts/ci/check_unsafe_boundaries.py` + `ci/unsafe-boundary-allowlist.toml`
> - `scripts/ci/run-rust-lint.sh` (invoked by the `rust-lint` CI job)
> - `cargo doc --no-deps` with `-D warnings` (broken-intra-doc-links denied)
> - `cargo +nightly miri test` for unsafe-heavy crates (see `scripts/ci/run-rust-miri.sh`)

The audits in `docs/rust-audit/` (issues #1 and #2) showed that the most
expensive bugs we have shipped were **safe APIs that smuggled unsafe
contracts to their callers**. This policy exists so that "safe Rust" in
this repo means what `unsafe` says it means in the language.

## The rule

A `pub fn` / `pub(crate) fn` must not require its caller to uphold any
memory-safety obligation in order for the function to be sound. Either the
function is genuinely safe — invariants enforced by types, lifetimes,
visibility, runtime checks, or RAII — or the function is `unsafe fn` with
a `# Safety` section that documents every precondition.

"Sound" here means: there is no way to call the function from safe Rust
in another crate (or another module, for `pub(crate)`) that would cause
undefined behaviour, even if the caller is malicious.

## Mandatory invariants

1. **Safe APIs do not require hidden memory-safety obligations from
   callers.** If you find yourself writing "the caller must…" in a
   doc-comment for a `pub fn`, the function must be `unsafe fn` instead.

2. **Raw pointer dereferences are locally justified.** Every `unsafe`
   block dereferencing a raw pointer must carry a SAFETY comment that
   names the exact precondition (validity, alignment, initialization,
   aliasing) AND identifies who establishes it.

3. **Safe wrappers enforce invariants through one of:**
   - the type system (newtypes, `BorrowedFd<'_>`, `OwnedFd`, typestate);
   - lifetimes (returned references tied to a real owner);
   - module visibility (private constructors + non-`Copy` handles);
   - runtime validation (bounded indices, checked casts);
   - RAII (Drop performs the cleanup once and only once).

   `debug_assert!` does **not** count as enforcement: it is a no-op in
   release builds. Use `assert!`, `Result`, or type-level encoding when
   safety depends on the check.

4. **`unsafe impl Send`/`unsafe impl Sync`** must be paired with a
   written argument (in a SAFETY comment immediately above the impl)
   showing why the type is actually thread-safe — usually because every
   field is either `Send`/`Sync` or its access is gated by a synchronization
   primitive whose ownership transfer is the happens-before edge.

5. **Move-only ownership handles do not implement `Copy` or `Clone`** unless
   there is a real refcount or shared ownership behind them. Handles
   passed across an FFI boundary as integers must be funneled through a
   `HandleRegistry` (or equivalent) with private construction.

6. **Call-order protocols must be expressed as types, not as comments**
   ("create → register → use → unregister → destroy" becomes typestate;
   the compiler refuses to call `destroy` before `register`).

## Compiler enforcement

Set at `[workspace.lints]` in `native/rust/Cargo.toml`:

| Lint | Level | Why |
|---|---|---|
| `unsafe_op_in_unsafe_fn` | `deny` | Force per-operation SAFETY comments even inside `unsafe fn`. |
| `clippy::mut_from_ref` | `deny` | Bans materializing `&mut T` from `&self` without `UnsafeCell` — the bug class that drove the bufpool fix. |
| `clippy::transmute_ptr_to_ptr` | `deny` | Pointer casts must be explicit (`.cast()` / `as`). |
| `clippy::useless_transmute` | `deny` | Catches transmutes that have a safe alternative. |
| `clippy::undocumented_unsafe_blocks` | `allow` (target: `deny`) | Every `unsafe { ... }` should have a `// SAFETY:` line. Currently `allow` while the legacy corpus is being annotated; the custom scan + allowlist enforces the high-level boundary contract regardless. Upgrading to `deny` is tracked as the closing step of the soundness epic. |
| `clippy::multiple_unsafe_ops_per_block` | `allow` (target: `warn`) | Encourages one-unsafe-op-per-block so each SAFETY comment is precise. Same rationale as above. |
| `clippy::as_ptr_cast_mut` | `warn` (escalates to error under CI's `-D warnings`) | Blocks the silent `value.as_ptr() as *mut T` shared-to-mut downgrade — exactly the pattern that produced soundness issue #3 candidates before the audit. Use `UnsafeCell` or a `&mut self`-anchored helper instead. |
| `clippy::ptr_as_ptr` | `warn` | Pointer casts must use `.cast::<U>()` so provenance is preserved and `*const → *mut` cannot happen accidentally inside an `as` expression. |

These coexist with two intentional `allow`s for FFI:
`clippy::missing_safety_doc` and `clippy::not_unsafe_ptr_arg_deref`. Those
remain `allow` because (a) JNI macros generate `unsafe fn`s whose
documentation would be macro-injected and (b) raw-pointer JNI argument
dereferences happen inside small `unsafe` blocks with their own SAFETY
comments. The custom scan script in
`scripts/ci/check_unsafe_boundaries.py` enforces the higher-level boundary
that those allows would otherwise leak past.

## Custom scan

`scripts/ci/check_unsafe_boundaries.py` is run by `run-rust-lint.sh`
on every PR. It looks for the following risky patterns under
`native/rust/crates/*/src/**`:

| Pattern | Concern |
|---|---|
| `slice::from_raw_parts(_mut)?` | Synthesizing slices over raw memory. |
| `Box::from_raw`, `Vec::from_raw_parts`, `String::from_raw_parts` | Ownership reconstitution from a raw pointer. |
| `.assume_init()` / `MaybeUninit::assume_init` | Promoting `MaybeUninit` to `T` without proof. |
| `mem::transmute` / `transmute::<_,_>` | Reinterpretation cast that bypasses the type system. |
| `.get_unchecked(_mut)?()`, `.unwrap_unchecked()` | Bounds/option check elision. |
| `Pin::new_unchecked`, `Pin::get_unchecked_mut` | Pin invariant bypass. |
| `NonNull::as_ref` / `NonNull::as_mut` (qualified form) | Materializing `&T` / `&mut T` from a raw `NonNull`. The unqualified method form is ignored to avoid the noisy `Option::as_ref` / `&str::as_ref` false-positive class — raw-pointer dereferences are covered instead by `unsafe_op_in_unsafe_fn = deny` plus the SAFETY-comment requirement. |
| `unsafe impl (Send|Sync)` | Thread-safety assertion. |
| `extern "C" fn`, `extern "system" fn` | FFI callback / JNI export entry point. |
| `pub fn ... NonNull` | Raw handle in a public signature. |
| `pub fn ...: *const T` / `*mut T` | Raw pointer in a public signature. |
| `pub fn ... handle/token/raw*: u64/i64/usize/isize` | Raw integer handle in a public signature. |
| `Option<NonNull<T>>` (any position) | `Option<NonNull<T>>` is `Copy`; using it as a safe ownership/liveness/registration handle invites duplication → UAF / double-free / stale pointer. See "Option<NonNull<T>> ownership tokens" below. |
| `&mut Option<NonNull<T>>` | The slot-extractor form (`fn take(slot: &mut Option<NonNull<T>>) -> Option<NonNull<T>>`) is the most acute UAF/double-free vector: a function can `take()` while a safe-code caller already holds a duplicate of the original slot. |
| `debug_assert near unsafe` (proximity ≤ 10 lines) | `debug_assert!` is compiled out in release; placing one within 10 source lines of an `unsafe` keyword suggests the debug-only assertion is acting as the safety guard. Per Mandatory Invariant #3, the actual safety check must be a release-mode `assert!` / `Result` / type-level encoding. See "`debug_assert!` as memory-safety guard" below. |
| `CStr::from_ptr` | Materializes a `&CStr` whose bytes are scanned for a NUL terminator starting at a raw pointer. The pointee must be a valid NUL-terminated C string in an allocation that lives at least as long as the returned `&CStr`. See "Creating `&T` from raw pointers" below. |
| `str::from_utf8_unchecked` | Asserts the input bytes are valid UTF-8 without checking. A regression here invalidates the `str` invariant and produces UB on any subsequent UTF-8 operation. Prefer `str::from_utf8` (release-mode validation) unless the bytes come from a checked source documented in the SAFETY comment. |
| `UnsafeCell::get` (deref form `*cell.get()`) | Materialises `&mut T` / `&T` from `*mut T` and bypasses Rust's borrow check. The exclusivity invariant — at most one accessor of the cell at any moment — must be enforced by the surrounding type design. The bare `.get()` method (without the `*` deref) is filtered out so unrelated `.get()` callers (HashMap, Vec, Option, AtomicPtr) are not flagged. See "Creating `&mut T` from raw memory" below. |

When the script flags a `(file, pattern)` pair it requires either a
restructure (preferred) or an entry in
`ci/unsafe-boundary-allowlist.toml`. Each allowlist entry must include:

- `file` — path relative to repo root.
- `pattern` — the exact key reported by the script.
- `reason` — one-line summary.
- `preconditions` — what the unsafe operation actually requires.
- `enforcement` — how the codebase guarantees the preconditions
  (type/lifetime/visibility/runtime/RAII or, in last-resort cases,
  human review).
- `owner` — the team or crate accountable for keeping the entry sound.
- `review_date` — ISO date for the next mandatory re-review.

**Adding a new entry is a code review red flag.** The reviewer should
push back unless the contributor has explained why options (1)
restructure and (2) `unsafe fn` were rejected.

## `Option<NonNull<T>>` ownership tokens

`Option<NonNull<T>>` is `Copy`. The value only represents "a nullable
non-null raw pointer"; it does not prove ownership, uniqueness, liveness,
valid lifetime, allocator provenance, initialization, or exclusive
access. Used as a safe ownership / liveness / registration / exclusive-
access handle, it lets safe callers duplicate the value and cause UAF,
double-free, stale-handle dereference, or aliasing UB.

**Rule.** `Option<NonNull<T>>` must not be used as a safe ownership token.

Concretely:

1. Do not store `Option<NonNull<T>>` in a struct field that is treated as
   an owning slot. Wrap `NonNull<T>` in a private move-only newtype with
   no `Copy`/`Clone` and store `Option<OwnerHandle<T>>` instead:

   ```rust
   use core::marker::PhantomData;
   use core::ptr::NonNull;

   pub(crate) struct OwnerHandle<T> {
       ptr: NonNull<T>,
       _owned: PhantomData<Box<T>>,
   }
   // NB: no #[derive(Copy)] / #[derive(Clone)].
   ```

2. Do not accept `&mut Option<NonNull<T>>` as a public parameter to
   "extract" or "swap out" an ownership slot. Move the handle through
   `slot.take()` on a value of type `Option<OwnerHandle<T>>` instead.

3. Do not return `Option<NonNull<T>>` from a safe public function as a
   handle. Return `Option<&T>`, `Option<&mut T>`, or a private
   `OwnerHandle<T>` whose constructor is `pub(crate)` or `unsafe fn`.

4. If a `NonNull<T>` field has to remain (for example to carry a raw
   pointer through to `Drop`), it must be a **private** field on a
   non-`Copy`, non-`Clone` struct, and the struct itself becomes the
   ownership token. The two production examples are
   `crates/ripdpi-geo/src/mapped_file.rs` and
   `crates/ripdpi-privileged-ops/src/linux/mmap_region.rs`: each wraps
   a single `NonNull<u8>` in a non-`Copy` `struct` whose `Drop` calls
   `munmap` exactly once. Neither type exposes the `NonNull` to
   callers, so safe duplication is impossible.

5. `debug_assert!` does not enforce ownership. A `debug_assert!(slot
   .is_none())` guard around a destroy/free call is compiled out of
   release builds and protects no one.

6. Lifecycle transitions ("created → registered → used → destroyed")
   must be encoded as types or visibility, not as flags
   (`is_alive`, `destroyed`, `disowned`, `owned_by_*`). Prefer typestate
   or consuming methods (`fn destroy(self)`); the compiler refuses
   double-destroy because the value moves.

**Allowlist entry.** If you have a legitimate reason to keep
`Option<NonNull<T>>` (e.g. a non-owning observation pointer used only as
a fast `is_some` flag), add an entry to
`ci/unsafe-boundary-allowlist.toml` whose `reason` and `enforcement`
fields explicitly state:

- whether the value is owning or non-owning,
- who owns the underlying allocation,
- how liveness is guaranteed for every reachable dereference,
- why `Copy` duplication is harmless in this specific case,
- whether the pointer is ever passed to a `destroy`/`free` /
  `unregister` path (and if so, what makes that path single-shot).

`pattern = "Option<NonNull<T>>"` is the key used by the scanner.

**Why not trybuild compile-fail tests?** The repository policy (see
"Compile-fail enforcement" below) is that the Rust type system *itself*
serves as the compile-fail harness. A `pub struct OwnerHandle<T> { ptr:
NonNull<T>, _owned: PhantomData<Box<T>> }` with private fields and no
`Copy`/`Clone` derive is already a compile-fail for `let dup = *slot;`
and `let dup = slot.clone();`. The scanner enforces *recognition* of the
unsafe pattern; the type system enforces *correctness* of the safe
replacement. Adding a `trybuild` harness for the same property would
duplicate enforcement without adding signal.

## `debug_assert!` as memory-safety guard

`debug_assert!`, `debug_assert_eq!`, and `debug_assert_ne!` expand to
no-ops in release builds unless the build was configured with debug
assertions enabled. If unsafe code relies on a `debug_assert!` to
exclude invalid pointers, bad lengths, uninitialized memory, duplicate
ownership, invalid state-machine transitions, or aliasing violations,
the release build will execute that unsafe code with the precondition
unenforced — undefined behaviour.

**Rule.** `debug_assert*!` must never be the *only* guard before an
unsafe operation. This restates Mandatory Invariant #3 above and is
enforced by the `debug_assert near unsafe` scan rule (debug-only
assertion within ±10 source lines of an `unsafe` keyword, after
comment stripping).

Concrete obligations:

1. **Safety preconditions are release-mode checks.** Replace
   `debug_assert!(cond);` with one of:
   - `assert!(cond, …)` if a panic is an acceptable safety boundary
     and the cost is acceptable;
   - `if !cond { return Err(…); }` if the caller is part of a fallible
     API and can recover;
   - a type or visibility change that makes the invalid state
     unrepresentable from safe code (preferred).

2. **Inputs from safe code are validated at the boundary, not inside
   an `unsafe` block.** A `pub fn` that calls `unsafe { … }` must
   either:
   - reject invalid inputs in safe code *before* the unsafe operation
     (`Result`, `Option`, `assert!`), or
   - be `unsafe fn` with a `# Safety` section that names every
     precondition.
   `debug_assert!(valid)` followed by `unsafe { do_thing() }` is not
   an acceptable pattern in either case.

3. **`debug_assert!` is still useful for diagnostic-only checks.**
   When the failure of the asserted condition produces incorrect-but-
   safe behaviour (a stale cache entry, a wrong telemetry tag, a
   logical inconsistency in non-`unsafe` code), `debug_assert!` is the
   right tool. The three production occurrences in this workspace —
   two in `crates/ripdpi-tunnel-core/src/dns_cache/state.rs` and one
   in `crates/ripdpi-monitor-engine/src/execution/lanes/https/`
   `sample_builder/sample_result.rs` — are all of this kind: the
   first pair is fronted by a release-mode `NonZeroUsize::new(max)`
   `.expect(…)`, and the third is a string-tag sanity check on
   telemetry input that can't reach unsafe code.

4. **Lifecycle flags are not safety guards.** Boolean flags such as
   `is_alive`, `destroyed`, `initialized`, `registered`, or
   `disowned`, combined with `debug_assert!(self.is_alive)`, are
   classic recipes for release-mode UAF. The fix is typestate
   (`fn destroy(self)` consumes the handle), RAII (`Drop` runs at
   most once because of move semantics), or `Option<OwnerHandle<T>>`
   (see "Option<NonNull<T>> ownership tokens").

5. **`debug_assert_with_mut_call` divergence.** `debug_assert!(
   self.try_mutate())` calls `try_mutate` in debug builds and silently
   skips it in release. This is a common subtle bug. Either remove
   the mutation or move it outside the assertion. We do not enable
   `clippy::debug_assert_with_mut_call` as a deny lint today because
   the workspace has no current occurrences and the lint is a
   nursery-tier lint with churn risk; the policy here is the
   enforcement of record.

**Why a proximity-based scan.** A precise lexical scan ("`debug_assert`
inside the same `unsafe { … }` block") would need AST-level analysis.
The proximity heuristic is a cheap upper bound that catches the typical
shapes — `debug_assert!(cond); unsafe { … }`, `unsafe fn f() {
debug_assert!(cond); … }`, and the inverse — without dragging a Rust
parser into the CI scripts. New legitimate uses (a `debug_assert!`
near an `unsafe impl Send` block that is unrelated to the assertion)
go through the allowlist; the `reason` and `enforcement` fields must
explain why the release-mode behaviour is sound.

**Allowlist entry requirements.** A `pattern = "debug_assert near
unsafe"` entry in `ci/unsafe-boundary-allowlist.toml` must state:

- which invariant the assertion documents,
- what actually enforces that invariant in release builds (type,
  RAII, separate release-mode `assert!`, FFI caller contract, …),
- why release-mode failure of the asserted condition cannot promote
  to UB,
- the symbol (function/method) whose body contains the assertion,
- an owner and a review date as for every other allowlist entry.

## Creating `&T` from raw pointers

Creating a Rust shared reference `&T` (or `&[T]`, `&str`, `&CStr`) from
a raw pointer is **not** the same as reading a byte through the
pointer. The reference is required to be:

- non-null and properly aligned for `T`,
- pointing into an allocation of at least `size_of::<T>()` bytes (or
  `len * size_of::<T>()` for `&[T]`),
- pointing to a fully initialised value of `T`,
- live for the entire returned lifetime — no `Drop` of the owner can
  run while the reference is held,
- not concurrently mutated through any other path — Rust's aliasing
  rules forbid even an unread write through an aliased `*mut T` while
  a `&T` exists.

If any of these is violated for **even one** byte, the program has UB,
regardless of whether the bad bytes are observed at runtime.

**Rule.** A safe public function must not turn a raw pointer or
`NonNull<T>` into a `&T`/`&[T]`/`&str`/`&CStr` unless every invariant
above is enforced by the function's own preconditions — types,
lifetimes, visibility, runtime validation, or RAII. If the caller has
to uphold any pointer-validity obligation, the function must be
`unsafe fn` with a `# Safety` section.

The repository already enforces this through the following scan
patterns (see "Custom scan" table above): `slice::from_raw_parts`,
`NonNull::as_ref/as_mut`, `CStr::from_ptr`, `str::from_utf8_unchecked`,
`raw pointer in public fn`, `NonNull in public fn`. Any new
occurrence of one of these patterns either restructures away the raw
pointer, becomes `unsafe fn`, or earns an allowlist entry whose
`preconditions` and `enforcement` fields make the validity argument
concrete.

**Preferred shapes.** In order of preference:

1. **No raw pointer at the API.** Accept `&[u8]` / `&str` / a
   borrowed handle. Return owned values (`Vec<u8>`, `String`) or
   references bound to a real owner lifetime (`fn get(&self) -> &T`,
   `fn slice(&self) -> &[u8]`). This is the shape used by
   `MappedFile::as_slice(&self) -> &[u8]` and
   `MmapRegion::as_ptr(&self) -> *const u8` — the former returns a
   reference whose lifetime is `&self`, the latter returns the raw
   pointer only for FFI handoff and never materialises a Rust
   reference from it.

2. **Validate, then convert.** At an FFI boundary, branch on null /
   length / encoding / alignment before producing the reference.
   `str::from_utf8` (release-mode validated) is preferred over
   `str::from_utf8_unchecked` even if the input is "known" valid;
   the cost is negligible and the safety surface shrinks.

3. **`unsafe fn` + `# Safety`.** When step 1 and step 2 are not
   possible (genuine FFI shims, low-level kernel helpers), the
   function becomes `unsafe fn` and documents every precondition. The
   caller must enter `unsafe { … }` with their own SAFETY comment.

**Anti-patterns.**

- A safe `pub fn` whose body contains `unsafe { std::slice::
  from_raw_parts(ptr, len) }` for a `ptr` and `len` derived from
  parameters with no internal validation. The function must either
  validate (option 2) or be `unsafe fn` (option 3).
- A `fn get<'a>(&self) -> &'a T` with an unconstrained `'a` — the
  caller can extend `'a` to `'static` and outlive `&self`. The
  correct signature is `fn get(&self) -> &T` (sugar for `fn get<'a>
  (&'a self) -> &'a T`), tying the returned reference to the owner.
- `debug_assert!(!ptr.is_null()); unsafe { &*ptr }` — covered by the
  proximity rule above. The null check must be release-mode.
- `let s = unsafe { str::from_utf8_unchecked(bytes) };` where
  `bytes` came from an external source. Either validate or accept the
  `Result` from `str::from_utf8`.

**Existing benign uses.** The audit recorded four raw-pointer →
reference sites; each is allowlisted with the validity argument:

| File | Conversion | Validity source |
|---|---|---|
| `crates/ripdpi-geo/src/mapped_file.rs` | `slice::from_raw_parts` → `&[u8]` | RAII `MappedFile` owns the mmap; slice borrows `&self`. |
| `crates/ripdpi-privileged-ops/.../icmp_wrapped_udp.rs` | `slice::from_raw_parts` → `&[u8]` | `recv_from` contract initialises the first `received` bytes of a stack `MaybeUninit` buffer; slice is consumed in-scope. |
| `crates/ripdpi-desync-runtime/src/platform/registry.rs` | `&*pointer` → `&dyn TcpDesyncPlatform` | RAII `Restore` guard scoped to a closure; non-owning observer. |
| `crates/ripdpi-io-uring/src/probe.rs` | `CStr::from_ptr` → `&CStr` | POSIX `uname(2)` NUL-termination contract; lifetime bounded by the local `utsname`. |

## Creating `&mut T` from raw memory

`&mut T` carries the strongest aliasing guarantee in Rust: while it
exists, no other reference (`&T` or `&mut T`) and no other route to
the same memory may observe or mutate it. Producing one from a raw
pointer or `*mut T` (the `&mut *ptr`, `ptr.as_mut()`,
`NonNull::as_mut`, `get_unchecked_mut`, `slice::from_raw_parts_mut`,
and `*cell.get()` paths) skips the borrow check entirely; soundness
depends entirely on the surrounding type design proving exclusivity.

**Rule.** A safe public function must not turn a raw pointer or
`*mut T` into `&mut T` unless the caller's type signature (typically
`&mut self`, plus an upstream uniqueness protocol on the owning
container) guarantees no other accessor exists. If the caller can
violate uniqueness, the function must be `unsafe fn` with a
`# Safety` section, OR the design must be reworked.

Concrete obligations:

1. **`&mut self` is the local exclusivity proof.** A method that
   derefs `*cell.get()` to `&mut T` must take `&mut self`. The
   borrow checker then rules out aliased mutable access for a single
   owner. The `BufferHandle::as_mut_buf(&mut self)` and
   `BufferHandle::deref_mut(&mut self)` patterns in `bufpool.rs`
   are the canonical examples.

2. **Container exclusivity is the upstream proof.** When the cell
   lives in a shared structure (a `Box<[UnsafeCell<T>]>` indexed by
   a handle, a `Mutex<T>`, etc.), the structure must enforce that at
   most one borrower exists per cell. The `BufferHandle` free-list
   discipline is one such protocol; `Mutex<T>` and `RwLock<T>` are
   the std-library equivalents. `Cell<T>` and `RefCell<T>` are
   alternatives for single-threaded use.

3. **Cross-thread synchronisation is a release/acquire edge.** When
   multiple threads access the cell, the protocol that transfers
   ownership of the cell must supply a happens-before relationship
   — typically a `Mutex` unlock/lock pair or an `AtomicUsize::store`/
   `load` with `Release`/`Acquire`. `bufpool.rs::RegisteredBufferPool`
   uses the `Mutex<Vec<u16>>` free list for this.

4. **Move-only handles encode "at most one accessor".** A non-`Copy`,
   non-`Clone` handle whose constructor is gated by an exclusivity
   protocol (acquire from a registry, mutex lock, type-state
   transition) is a compile-time proof that safe code cannot
   duplicate the access right. The runtime checks (free-list
   bookkeeping, mutex contention) are necessary; the
   non-`Copy`/non-`Clone` constraint is what makes them sufficient.

5. **`debug_assert!` is not exclusivity.** A `debug_assert!(self
   .unique())` guard around `(*cell.get()).as_mut()` is compiled out
   of release builds; release-mode UB is the result if the assertion
   would have failed. See § "`debug_assert!` as memory-safety guard".

6. **Unbounded lifetimes leak the borrow.** A `fn as_mut<'a>(&self)
   -> &'a mut T` with an unconstrained `'a` lets the caller widen
   `'a` to `'static` and outlive `&self`. Tie the returned reference
   to `&mut self` (sugar form `fn as_mut(&mut self) -> &mut T`) so
   the borrow checker enforces the lifetime.

**Anti-patterns rejected by review.**

- `(*cell.get()).as_mut()` inside a method that takes `&self` (not
  `&mut self`), unless an enclosing exclusivity protocol is named
  in the SAFETY comment. The default expectation is `&mut self`.
- `fn get_mut(&self) -> &mut T` — taking a shared self yet returning
  exclusive — only sound when `T` is wrapped in interior mutability
  (Mutex, RefCell) and the function returns a guard, not a bare `&mut`.
- A safe `pub fn` that constructs `&mut T` from a `*mut T` parameter
  without internal validation. Either validate (null, alignment,
  exclusivity) before the conversion, or make the function `unsafe fn`
  with a `# Safety` section enumerating every precondition.
- Two methods with `&mut self` that each cache a `*mut T` in struct
  fields and re-deref later, allowing one call to mutate through a
  pointer the other call cached. The fields must be `&mut T` borrows
  bound to `&mut self`, or the cache must be invalidated on every
  mutation.

**Existing benign use.** The only `*cell.get()` → `&mut T` site in
the workspace is `bufpool.rs`. Its exclusivity proof:
- `BufferHandle` is move-only (no `Copy`/`Clone`).
- The `RegisteredBufferPool::acquire()` constructor pops a unique
  index from a `Mutex<Vec<u16>>` free list under a lock; at most one
  `BufferHandle` exists per cell.
- `as_mut_buf(&mut self)` and `deref_mut(&mut self)` are anchored to
  `&mut self`, so two simultaneous `&mut [u8]` borrows from one
  handle cannot compile.
- `Drop` (and `PendingBuffer::complete`) push the index back to the
  free list; the next `acquire` may legitimately reuse the slot
  because the previous handle is gone.
- Runtime regressions in `bufpool::tests` witness this lifecycle.
- The compile-fail half (`!Copy + !Clone`, `&mut self`-anchored
  borrow, no `BufferHandle` constructor outside the crate) is
  enforced by the type system per "Compile-fail enforcement" below.

## `unsafe impl Send` and `unsafe impl Sync`

`Send` says "the whole value can be moved across threads safely."
`Sync` says "`&T` can be shared across threads safely." Both are
opt-in auto-traits: the compiler derives them automatically when
every field implements them. A manual `unsafe impl Send` or
`unsafe impl Sync` overrides the compiler's analysis, usually
because the type contains a raw pointer (`*const T`/`*mut T`),
`NonNull<T>`, `UnsafeCell<T>`, a JNI handle (`JavaVM`, jobject),
or a thread-affine OS resource that the Rust type system can't
reason about.

**Rule.** Every manual `unsafe impl Send | Sync` MUST:

1. carry a SAFETY comment naming the cross-thread invariant and the
   mechanism that enforces it (mutex unlock/lock for happens-before,
   read-only data, JNI spec contract, ownership transfer through a
   move-only handle, etc.);
2. live in an allowlist entry in
   `ci/unsafe-boundary-allowlist.toml` whose `enforcement` field
   reproduces the SAFETY argument in machine-readable form; and
3. include a `const _: fn() = || { fn assert_send<T: Send>() {}
   assert_send::<T>(); … }` block locking the claim — any future
   field change that breaks Send/Sync fails to compile at the
   assertion, before the lefthook clippy hook ever runs.

**Negative (`!Send` / `!Sync`) types** must use the trait-dispatch
ambiguity trick (`AmbiguousIfSend<A>` / `AmbiguousIfSync<A>`
overlapping blanket impls) to lock the absence of Send/Sync. This
is the stable-Rust equivalent of
`static_assertions::assert_not_impl_any!`. The pattern is in-place
on `MmapRegion` in `crates/ripdpi-privileged-ops/src/linux/`
`mmap_region.rs`; copy it verbatim for any future `!Send` type.

**The four manual `unsafe impl Send + Sync` impls in production**:

| Type | File | Cross-thread enforcement |
|---|---|---|
| `MappedFile` | `ripdpi-geo/src/mapped_file.rs` | Read-only mmap; no interior mutability; single owner; Drop munmaps once. |
| `RegisteredBufferPool` | `ripdpi-io-uring/src/bufpool.rs` | `Mutex<Vec<u16>>` free list supplies happens-before; per-cell access via the unique `BufferHandle` whose index is mutex-guarded. |
| `JniProtectCallback` (warp-android) | `ripdpi-warp-android/src/vpn_protect.rs` | JNI spec: `JavaVM` is thread-safe; `Global<JObject>` is GC-pinned across threads; `protect()` uses `attach_current_thread` per invocation. |
| `JniProtectCallback` (vpn-protect-adapter) | `ripdpi-android-vpn-protect-adapter/src/lib.rs` | Same as above (duplicate of the warp-android impl). |

**Anti-patterns rejected by review.**

- `unsafe impl Send for X {}` with no SAFETY comment — fails the
  `clippy::undocumented_unsafe_blocks` aspiration and the policy here.
- `unsafe impl Send` to "make it compile" because the type holds a
  raw pointer that's actually thread-affine (e.g. a `JNIEnv*`, an
  OpenGL context, a `MAP_SHARED` mmap with writeable mappings).
  These types must remain `!Send` and the design must change to use
  `Arc<Mutex<…>>`, a channel-based handoff, or per-thread
  registration.
- A `unsafe impl Send` impl whose SAFETY argument cites
  `debug_assert!` for the thread-affine invariant. The release-mode
  build is the one that ships; debug-only checks don't enforce
  thread safety.

## Documentation contract

Every `unsafe` block in production code must have a `// SAFETY:` comment
within the two source lines immediately above it. The comment must answer:

1. What precondition makes the unsafe operation defined?
2. Who establishes the precondition (type? lifetime? RAII guard? callee
   contract?)
3. Why safe callers in this module cannot violate it.

Every `unsafe fn` must carry a `# Safety` rustdoc section with the same
information. The macro-generated JNI exports are the only documented
exception, justified in
`docs/rust-soundness-policy.md`'s allowlist section.

## CI surface

The `rust-lint` job invokes `scripts/ci/run-rust-lint.sh`, which runs:

```
cargo fmt --check
python3 scripts/ci/check_runtime_crate_boundaries.py
python3 scripts/ci/check_unsafe_boundaries.py
cargo clippy --workspace --all-targets -- -D warnings
cargo doc --workspace --all-features --no-deps
```

The rustdoc step is included to catch new doc-comment compile errors but
runs at default warning level — a small set of pre-existing broken
intra-doc links in legacy crates would otherwise mask the new check. The
soundness epic's closing PR upgrades it to `-D warnings`.

The dedicated `rust-miri` job runs
`scripts/ci/run-rust-miri.sh`, which extends miri coverage opportunistically
to crates with raw-pointer code (see commentary in that script).

`cargo test --all-features` runs in the existing `rust-tests` matrix; no
change there.

## Lint waivers

The two `clippy::*` lints listed under "JNI/FFI allowances" in
`native/rust/Cargo.toml` are the only blanket waivers. They exist because
JNI macros and JNI-exported raw-pointer arguments would otherwise require
per-symbol `#[allow(...)]` annotations that conflict with the no-baseline-
extension policy enforced by `scripts/ci/check_rust_allow_guard.py`.

No additional waivers may be added without:

1. A note in this document explaining why.
2. A specific scan rule in `check_unsafe_boundaries.py` to replace the
   coverage the waiver removes.

## Compile-fail enforcement

We don't ship a separate `trybuild` harness — the Rust type system itself
acts as the compile-fail test for every guarantee. The audit-driven
refactors guarantee that these misuses *do not compile*:

| Misuse | Rejected by |
|---|---|
| `BufferHandle { pool, index, len }` from outside `ripdpi-io-uring` | private fields; only `RegisteredBufferPool::acquire` constructs the handle. |
| `PendingBuffer::complete(self, wrong_pool)` | `PendingBuffer<'pool>` borrows the issuing pool; there is no longer a `pool` argument. |
| `let copy: BufferHandle = handle.clone()` | `BufferHandle` doesn't implement `Clone`. |
| `dup2_fd(some_int, some_int)` from another crate | `pub(crate)`; integers must first be converted via `BorrowedFd::borrow_raw`, which is itself `unsafe`. |
| `swap_replacement_fd(target, replacement)` from safe code | signature is now `pub unsafe fn`; callers must enter `unsafe { … }` with their own SAFETY comment. |
| `alloc_region(len)` from anywhere | helper removed; only the RAII `MmapRegion` newtype is reachable. |
| `MmapRegion::write(&self, ...)` | takes `&mut self`; concurrent writers cannot coexist. |
| `pool.release_by_index(i)` from another crate | now `pub(crate)`. |
| `let dup = *owner_slot;` where `owner_slot: Option<OwnerHandle<T>>` | `OwnerHandle<T>` doesn't implement `Copy`; the move out of `*slot` leaves the slot un-initialized. |
| `let dup = owner_slot.clone();` | `OwnerHandle<T>` doesn't implement `Clone`. |
| Constructing `OwnerHandle<T>` from outside its module | the `ptr` field is private and the constructor is either `pub(crate)` or `unsafe fn`. |

Any new pattern in the same class should be encoded the same way: as a
type or visibility constraint that makes the misuse impossible to
*write*, not just impossible to do at runtime. If you cannot find such an
encoding, propose the API as `unsafe fn` with a `# Safety` section
instead.

## Adding new unsafe code

If you must add new `unsafe`:

1. Prefer to make it inaccessible from safe code (private `unsafe fn`
   helper called only from another `unsafe` block whose preconditions
   already imply the helper's).
2. Otherwise, make the surface `unsafe fn` with a `# Safety` section.
3. Pair the change with at least one regression test that demonstrates
   the precondition the type system or runtime check enforces.
4. If you are introducing a new `(file, pattern)` pair flagged by the
   scan, the PR description must contain a sentence that begins with
   "Restructure was rejected because …" explaining the chosen design.

Reviewers must check that step 4 is present and persuasive before
approving.
