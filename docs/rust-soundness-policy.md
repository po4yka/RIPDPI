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
| `Box::from_raw`, `Vec::from_raw_parts`, `String::from_raw_parts` | Ownership reconstitution from a raw pointer. See "`Box::into_raw` / `Box::from_raw` ownership transfer" and "`Vec::from_raw_parts` ownership transfer" below. |
| `Vec::from_raw_parts_in` | Allocator-API variant of `Vec::from_raw_parts`. Same eight-point checklist plus the allocator-compatibility constraint must hold across the call. The base `Vec::from_raw_parts` `\b` regex anchor does NOT match the `_in` suffix because `_` is a word character, so this is a dedicated pattern. See "`Vec::from_raw_parts` ownership transfer" below. |
| `Box::into_raw` | The matched counterpart of `Box::from_raw`. Scanning only the reclaim side would miss orphaned `into_raw` calls that leak (`mem::forget` equivalent) or that hand the pointer to FFI without a matching `from_raw`. See "`Box::into_raw` / `Box::from_raw` ownership transfer" below. |
| `.assume_init()` / `.assume_init_ref()` / `.assume_init_mut()` / `.assume_init_drop()` / `.assume_init_read()` / `MaybeUninit::assume_init(_*)?` | Promoting `MaybeUninit<T>` to `T` (or `&T`/`&mut T`/Drop-target) without proof every byte of the slot is a valid `T` value. UB on the very next read otherwise. The previous regex matched only the base form because the `\b` anchor stopped at `_`; the broadened regex catches all five std-API variants. See "`MaybeUninit` correctness" below. |
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
| `str::from_utf8_unchecked` | Asserts the input bytes are valid UTF-8 without checking. A regression here invalidates the `str` invariant and produces UB on any subsequent UTF-8 operation. Prefer `str::from_utf8` (release-mode validation) unless the bytes come from a checked source documented in the SAFETY comment. See "Unsafe `String`/`str` construction" below. |
| `String::from_utf8_unchecked` | The owned counterpart of `str::from_utf8_unchecked`: turns a `Vec<u8>` into a `String` without validating UTF-8. Same UB risk as the borrowed variant, separate scanner pattern because the input is owned (so the validity argument must cover the ownership transfer too). Prefer `String::from_utf8` (returns `Result<String, FromUtf8Error>`) or `String::from_utf8_lossy` (substitutes U+FFFD for invalid sequences). See "Unsafe `String`/`str` construction" below. |
| `libc::malloc`, `libc::calloc`, `libc::realloc`, `libc::free` | Direct C-allocator calls. Rust's default global allocator and libc's `malloc`/`free` are NOT contractually the same heap — even when they happen to coincide on a given target, the relationship is implementation-defined and breaks silently on a `#[global_allocator]` switch. New occurrences must either restructure to keep both ends of the lifetime on one side (Rust → `Box`/`Vec`; C → foreign-managed) or earn an allowlist entry per "Allocator mismatch across FFI" below. |
| `CString::from_raw`, `CString::into_raw` | The FFI-string analogue of `Box::into_raw`/`Box::from_raw`. The pair carries the allocator-compatibility constraint (both ends must use the global allocator that `CString::new` used) plus a NUL-termination invariant. Mixing with `libc::malloc`/`libc::free` is UB. See "Allocator mismatch across FFI" below. |
| `unsafe Vec::set_len` (proximity ≤ 1 line) | `unsafe { v.set_len(n) }` shape on a single line — the canonical spelling of `Vec::set_len`, which is `unsafe fn`. Bytes `[0, n)` MUST be initialised valid `T` values BEFORE the call; otherwise Drop runs on uninit memory (UB if `T: Drop`) and `&[..]` borrows expose uninit bytes. The pattern is intentionally narrow (matches only `unsafe { ... .set_len( ... ) }`) so safe inherent methods like `BufferHandle::set_len` and `File::set_len` are not flagged. See "`Vec::set_len` initialisation contract" below. |
| `mem::zeroed`, `MaybeUninit::zeroed`, `ptr::write_bytes`, `libc::memset` | Zero-initialise a `T` (or `n` `T` values). Sound only if every bit pattern of zero is a valid `T`; UB for `T` carrying a validity invariant (references, `NonNull<T>`, `Box<T>`, `NonZero*`, `bool` if asserted as non-false-only, `char` (gap in valid Unicode), enums (must be a declared variant), function pointers (null)). See "Zero-initialisation validity" below. |
| `UnsafeCell::get` (deref form `*cell.get()`) | Materialises `&mut T` / `&T` from `*mut T` and bypasses Rust's borrow check. The exclusivity invariant — at most one accessor of the cell at any moment — must be enforced by the surrounding type design. The bare `.get()` method (without the `*` deref) is filtered out so unrelated `.get()` callers (HashMap, Vec, Option, AtomicPtr) are not flagged. See "Creating `&mut T` from raw memory" below. |
| `Cell<bool>` | `Cell<bool>` is a common cheap way to encode lifecycle state, but the value's mutation has no synchronisation cost and no exclusivity discipline. Use a typestate / RAII guard / Mutex instead. See "Ownership must be types, not flags" below. |
| `ownership flag near drop/unsafe` (proximity ≤ 50 lines) | A `bool` field named `registered`, `is_alive`, `destroyed`, `initialized`, `disowned`, `owned_by_*`, or `freed` that lives within 50 source lines of an `impl Drop` or `unsafe` keyword. Per issue-#11 audit, ownership/liveness must be encoded by the type system, not by flags + comments. See "Ownership must be types, not flags" below. |
| `manual Arc/Rc refcount` | Calls to `Arc::into_raw`/`from_raw`/`increment_strong_count`/`decrement_strong_count` (and the `Rc`/`Weak` equivalents). The standard library handles every sound use of these internally; application code that calls them is almost always reinventing reference counting unsoundly. Round-tripping `Arc<T>` through `*const T` silently shifts the refcount by 0 or 1 depending on whether the caller remembers to call `Arc::from_raw` exactly once. See "Use `Arc<T>` / `Rc<T>` / `Weak<T>`, not manual refcounting" below. |
| `manual atomic refcount field` | A struct field named `refs`/`refcount`/`ref_count`/`strong`/`weak` whose type is `AtomicUsize`/`AtomicU64`/`AtomicIsize`/`AtomicI64`. Indicates a hand-rolled intrusive reference count, which must either restructure to `Arc<T>`/`Rc<T>`/`Weak<T>` or earn an allowlist entry whose `enforcement` field documents the five-model template below. |
| `derive Clone on owner-named type` (proximity ≤ 5 lines) | `#[derive(Clone)]` (alone or with other traits) immediately above a struct/enum whose name ends in `Handle`, `Owner`, `Guard`, `Token`, `Resource`, `Registration`, or `Slot`. Clone on an ownership-named type silently duplicates the resource unless the inner data is genuinely shared (Arc-backed) or copy-trivial (pure metadata). See "`Clone` on owner-named types" below. |
| `derive Copy on owner-named type` (proximity ≤ 5 lines) | `#[derive(Copy)]` (alone or with other traits) immediately above the same owner-named declarations. `Copy` is strictly stronger than `Clone`: every move, parameter pass, and assignment produces an implicit duplicate, so an owner-named `Copy` type cannot encode ownership of any resource. The only sound semantics is "Copy-trivial metadata that owns nothing" (e.g. `&'static str` + `fn` pointer). See "`Copy` on owner-named types" below. |
| `manual impl Copy` | A hand-written `impl Copy for X { }` (with or without leading `unsafe`). `Copy` is normally derived; a manual `impl` block is almost never the right choice and signals that the contributor either knows the field shape doesn't satisfy `derive(Copy)` requirements or is trying to bypass an auto-trait check. The workspace has zero production occurrences. Any new appearance must restructure (use `#[derive(Copy)]` if Copy is genuinely intended and the field shape supports it) or earn an allowlist entry naming the Copy-trivial-data property. |
| `derive Copy with raw-pointer/handle field` (proximity ≤ 25 lines) | `#[derive(Copy)]` immediately followed by a struct body whose fields include `NonNull<T>`, a raw `*const T` / `*mut T` pointer, a `RawFd`, an `OwnedFd`, or a JNI `JavaVM`/`JObject`/`JNIEnv`/`Global<JObject>` handle. Field-shape complement of the name-based detector: even structs with neutral names ("Config", "Slot") that hand out duplicates of a `RawFd` or `NonNull` produce the same UAF / double-close failure mode. The workspace has zero production occurrences; new entries must restructure or document the Copy-trivial-data property in an allowlist entry. |

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

## `Clone` on owner-named types

Types whose names end in `Handle`, `Owner`, `Guard`, `Token`,
`Resource`, `Registration`, or `Slot` advertise ownership of a
resource. `Clone` on such a type MUST mean exactly one of:

1. **Independent safe duplicate** — the inner data is copy-trivial
   (plain integers, `&'static str`, function pointers, `Copy`able
   IDs). Cloning produces a new value that owns nothing the original
   owned because there is nothing to own. Example:
   `StrategyDescriptorRegistration { id: &'static str, describe: fn()
   -> StrategyDescriptor }`.
2. **Refcounted shared owner** — the type is a newtype around
   `Arc<T>` / `Rc<T>` (or holds one as its sole resource-bearing
   field). Cloning delegates to `Arc::clone` / `Rc::clone`, which
   the standard library implements soundly. Example:
   `ServicesStateHandle(pub(crate) Arc<ServicesState>)`.

`Clone` MUST NOT mean:

- "Duplicate a raw pointer". The original's `Drop` will free the
  resource; the duplicate then dangles. Use `Arc<T>` if sharing
  is intended.
- "Duplicate a `RawFd`". The first `Drop` closes the fd; the
  second sees a stale or recycled descriptor.
- "Duplicate an FFI handle". Same problem as RawFd, plus the
  foreign library may assert single-ownership.
- "Duplicate an exclusive-access registration". The registry
  silently has two entries for the same key; cleanup is racy.

**Rule.** A `#[derive(Clone)]` on an owner-named struct must have
either (a) only `Copy`-trivial fields, or (b) `Arc<T>` / `Rc<T>` as
the sole resource-bearing field. Anything else requires either
removing the Clone (the type becomes move-only) or providing a
named `try_clone(&self) -> Result<Self, _>` method whose body
documents the duplication semantics — `File::try_clone(&self) ->
io::Result<File>` is the std model.

**Allowlist entry requirements.** A `derive Clone on owner-named
type` allowlist entry's `enforcement` field MUST state:

- which of the two sound semantics the type uses (copy-trivial
  metadata or `Arc`/`Rc`-backed shared ownership),
- the specific field that bears the resource (and that it is
  `Copy`-trivial or `Arc<T>`),
- why a Clone of the outer struct does not duplicate any
  underlying allocation, file descriptor, registration, or other
  exclusive resource.

**Workspace inventory.** Three allowlisted occurrences:

| File | Type | Semantics |
|---|---|---|
| `ripdpi-strategy-trait/src/lib.rs` | `StrategyDescriptorRegistration` | Copy-trivial metadata: `&'static str` + function pointer; owns nothing. |
| `ripdpi-proxy-runtime-adapter/src/model/services.rs` | `ReprobeResetHandle` | Arc-backed (wraps `ServicesStateHandle` which wraps `Arc<ServicesState>`). |
| `ripdpi-runtime-services/src/lib.rs` | `ServicesStateHandle` | Arc-backed newtype: `pub(crate) Arc<ServicesState>`. |

The load-bearing move-only owner handles (`BufferHandle`,
`PendingBuffer`, `RootHelperRegistration`, `MmapRegion`,
`MappedFile`, `RegisteredBufferPool`, `JniProtectCallback × 2`,
`OwnedRxToken`, `OwnedTxToken`) correctly do NOT derive `Clone`,
and the canonical owner handles (`BufferHandle`, `PendingBuffer`,
`RootHelperRegistration`) carry explicit compile-fail
`AmbiguousIfCopy`/`AmbiguousIfClone` const blocks that fail to
compile if a future change ever derives `Clone`.

## `Copy` on owner-named types

`Copy` is strictly stronger than `Clone`: a `Copy` value is
duplicated implicitly on every move, every function call by-value,
every pattern bind by-value, and every assignment. There is no
explicit `.clone()` call site at which a reviewer could intercept
the duplication. An owner-named type that is `Copy` therefore
cannot encode any ownership of any resource — by the time the call
stack unwinds, every parameter pass and every `let` binding has
silently produced another bitwise duplicate of the supposed
owner.

The only sound `Copy` semantics on a type whose name ends in
`Handle`, `Owner`, `Guard`, `Token`, `Resource`, `Registration`,
or `Slot` is:

- **Copy-trivial metadata that owns nothing.** Every field is
  itself `Copy` and aliases something that is intrinsically
  duplicable: `&'static str` (rodata reference), function
  pointer (code address), plain integer (numeric value),
  `Copy`-only id newtype. No allocation, no file descriptor, no
  kernel resource, no FFI handle, no arena index whose validity
  depends on the surrounding arena's lifetime, no `Drop` impl.
  The canonical example is
  `StrategyDescriptorRegistration { id: &'static str, describe:
  fn() -> StrategyDescriptor }` — a `linkme::distributed_slice`
  entry that exists only to register a strategy family at link
  time.

`Copy` MUST NOT mean any of the following on an owner-named
type:

- "Duplicate a raw pointer / `NonNull`". Every move duplicates
  the pointer; whichever copy drops first runs the cleanup, and
  every other copy then dangles. (See "Option<NonNull<T>>
  ownership tokens" above for the same failure mode at the
  `Option` level.)
- "Duplicate a `RawFd` / `OwnedFd` / file descriptor". Closing
  the fd on first drop leaves all other copies referring to
  a stale or recycled descriptor.
- "Duplicate an FFI handle / `JavaVM` / `Global<JObject>` /
  `*mut FFI_T`". The foreign library has no idea Rust has
  silently produced more handles; double-free or use-after-free
  on the foreign side is the typical result.
- "Duplicate an arena index whose validity depends on the
  arena". A `Copy` `BufferIndex(u16)` looks innocent until the
  free-list hands the same index to two callers, at which point
  the type system can no longer enforce exclusivity.
- "Duplicate a `Drop`-bearing handle". `Copy` + `Drop` is
  rejected by the compiler outright — Rust enforces this part of
  the rule itself. The scanner catches the failure mode
  immediately upstream: a future contributor who adds
  `#[derive(Copy)]` to a `Drop`-bearing handle gets a CI
  failure before the compile error.

**Rule.** A `#[derive(Copy)]` on an owner-named struct must
demonstrate the Copy-trivial-metadata property: every field is
inherently `Copy` and aliases something whose duplication is
free of ownership. Anything else either removes the `Copy`
derive (the type becomes move-only — the default and preferred
shape) or restructures into a metadata wrapper plus a separate
`!Copy` owner handle.

**Allowlist entry requirements.** A `derive Copy on owner-named
type` allowlist entry's `enforcement` field MUST state:

- that every field is inherently `Copy` and what each field
  aliases (rodata, code address, numeric value, `Copy`-only id),
- that the struct holds no allocation, file descriptor, raw
  pointer, kernel resource, or arena index whose validity is
  bounded by an enclosing object,
- that no `Drop` impl exists and that none could be sensibly
  added (the type is pure metadata).

**Workspace inventory.** Exactly one allowlisted occurrence:

| File | Type | Semantics |
|---|---|---|
| `ripdpi-strategy-trait/src/lib.rs` | `StrategyDescriptorRegistration` | Copy-trivial metadata: `&'static str` + function pointer; owns nothing; no `Drop`. |

**Compile-fail enforcement.** The load-bearing move-only owner
handles are `!Copy` and carry explicit `AmbiguousIfCopy` const
blocks that fail the workspace build if a future change ever
derives `Copy`. The current explicit coverage is:

| File | Type | Block kind |
|---|---|---|
| `ripdpi-io-uring/src/bufpool.rs` | `BufferHandle<'pool>` | `AmbiguousIfCopy` const block |
| `ripdpi-io-uring/src/bufpool.rs` | `PendingBuffer<'pool>` | `AmbiguousIfCopy` const block |
| `ripdpi-io-uring/src/bufpool.rs` | `RegisteredBufferPool` | `AmbiguousIfCopy` const block (added with issue #14) |
| `ripdpi-privileged-ops/src/linux/mmap_region.rs` | `MmapRegion` | `AmbiguousIfCopy` const block (added with issue #14) |
| `ripdpi-geo/src/mapped_file.rs` | `MappedFile` | `AmbiguousIfCopy` const block (added with issue #14) |
| `ripdpi-proxy-runtime/src/runtime/listeners.rs` | `RootHelperRegistration` | `AmbiguousIfCopy` const block |
| `ripdpi-android-vpn-protect-adapter/src/lib.rs` | `JniProtectCallback` | `AmbiguousIfCopy` const block |
| `ripdpi-warp-android/src/vpn_protect.rs` | `JniProtectCallback` | `AmbiguousIfCopy` const block |
| `ripdpi-tunnel-core/src/device.rs` | `OwnedRxToken` | `AmbiguousIfCopy` const block |
| `ripdpi-tunnel-core/src/device.rs` | `OwnedTxToken<'a>` | `AmbiguousIfCopy` const block |

Every load-bearing move-only owner handle in the workspace now
carries an explicit `AmbiguousIfCopy` block. For the four
JNI/smoltcp shims the contained types (`Global<JObject>`,
`Vec<u8>`, `&mut VecDeque<_>`) are themselves `!Copy`, so the
compiler already rejects any future `derive(Copy)`; the explicit
block pins the soundness argument adjacent to the type
declaration so the next reviewer can see it without crossing
crate boundaries.

## Use `Arc<T>` / `Rc<T>` / `Weak<T>`, not manual refcounting

Shared ownership in this workspace MUST use the standard library's
reference-counting types: `std::sync::Arc<T>` for cross-thread sharing,
`std::rc::Rc<T>` for single-threaded sharing, and `std::sync::Weak<T>`
or `std::rc::Weak<T>` for observer pointers that must not extend the
lifetime of the value. The standard library already handles every
soundness requirement the issue-#12 audit names:

| Concern | How std solves it |
|---|---|
| Overflow | `Arc::clone` panics on overflow (above `isize::MAX/2`). |
| Atomic ordering | `Arc::clone` uses `Relaxed` for increment (the count is monotonic between clone and drop), `Release` for decrement, and `Acquire` for the last-drop fence. This is the canonical sound sequence. |
| Clone/drop balance | Auto-derived `Clone` + std-provided `Drop` are guaranteed paired by Rust move semantics. |
| Reentrancy | `Arc::drop` only deallocates at refcount zero; no callback into user code during the decrement. |
| Panic paths | `Arc::drop` is panic-safe; the destructor never reads through the pointer after the last decrement. |
| `Send`/`Sync` | `Arc<T>: Send + Sync` when `T: Send + Sync`, enforced by blanket impl. |
| Object reclamation | The last `Drop` calls the inner `T`'s destructor under an `Acquire` fence. |

**Rule.** Application code MUST NOT call any of the manual-lifecycle
methods on `Arc`/`Rc`/`Weak`:

- `Arc::into_raw` / `Arc::from_raw`
- `Arc::increment_strong_count` / `Arc::decrement_strong_count`
- `Rc::into_raw` / `Rc::from_raw`
- `Weak::into_raw` / `Weak::from_raw`

These exist for `unsafe` library authors implementing custom smart
pointers; calling them in safe-feeling application code re-creates
the bugs `Arc` was designed to prevent. The scanner pattern
`manual Arc/Rc refcount` enforces this rule with zero baseline.

**Allowlist entry requirements (manual Arc/Rc raw round-trip).** If a
genuine FFI shim must pass an `Arc` through a C boundary (e.g. an
opaque pointer registered with a foreign library), the allowlist
entry MUST state:

- which boundary requires the raw pointer,
- which symbol is paired with `into_raw` (every `into_raw` MUST be
  matched by exactly one `from_raw`),
- how the call-site discipline prevents leaks (no `into_raw` without
  a registered cleanup callback that consumes via `from_raw`),
- thread-safety: whether the foreign code may share or send the
  raw pointer, and how the `Arc`'s `Send + Sync` guarantees survive
  the boundary.

**Allowlist entry requirements (intrusive `AtomicUsize` refcount).**
If a hand-rolled refcount survives review (intrusive linked list
node, embedded-target where `Arc` is too large, etc.), the
allowlist entry's `enforcement` field MUST document all five of:

1. **Ownership model** — which type owns the allocation, when it
   reclaims, and what handle shape is exposed to callers (must be
   non-`Copy`, with `Clone` and `Drop` implemented in lockstep).
2. **Atomic ordering proof** — every operation on the counter must
   name its ordering: `Relaxed` for clone (monotonic increment),
   `Release` for drop (publish writes before decrement), `Acquire`
   on the last-drop fence (synchronise with prior `Release`-stores
   from other dropping threads). The proof must cite the exact
   happens-before chain.
3. **Overflow policy** — the counter must `abort` or `panic` on
   overflow before it wraps (`Arc` does this by aborting above
   `isize::MAX/2`). A silently-wrapping counter is a double-free
   waiting to happen.
4. **Reclamation policy** — what runs at refcount zero, in what
   order, and what synchronises the destructor with the last
   `Release` decrement (typically an `Acquire` fence inside Drop).
5. **Owner** — the team or crate accountable for re-reviewing the
   design on schedule.

Required regression tests for every custom-refcount allowlist:

- Clone/drop balance under sequential calls (no leak, no
  double-free).
- Clone/drop balance under multi-threaded contention (loom or
  thread-spawn test).
- Reentrancy: cloning inside the inner `T`'s destructor is either
  forbidden by API design or proven sound.
- Compile-fail: the handle is not `Copy` (use `AmbiguousIfCopy`
  trick) and not `Clone` unless the `Clone` impl maintains the
  refcount invariant.
- Miri run on a single-threaded clone/drop sequence to catch
  obvious provenance/UB issues.

**Anti-patterns reviewers reject.**

- `Arc::into_raw` followed by `mem::forget(arc)` — both increment
  the refcount and forget the original `Arc`, leaking the value.
- A custom `struct ManualRefcount { count: AtomicUsize, data: T }`
  with hand-rolled `inc`/`dec` methods. Replace with `Arc<T>`.
- `unsafe { Arc::from_raw(ptr) }` without a matching prior
  `Arc::into_raw(arc)` from the SAME `Arc` allocation. Producing
  the pointer any other way (cast from a `&T`, `Box::into_raw`,
  pointer arithmetic) is UB.
- A "manual `Weak`" using `Arc::downgrade` + a side channel that
  stores raw pointers. Use `Weak<T>` directly; the std API already
  handles upgrade race conditions.

**Workspace inventory.** Zero manual-refcount sites in production.
All shared ownership uses `Arc<T>` with the standard derive Clone
or explicit `Arc::clone(&...)` calls. Pool-style "release(index)"
methods that the initial grep flagged (e.g. `BufferHandle::release`,
`VirtualPortPool::release`) are **index-based ownership transfer**
into a `Mutex<Vec<u16>>` free list, not refcounting; they were
audited under soundness issues #1, #2, #7, #8, #9, #10 and remain
sound by the move-only handle + mutex protocol.

## `Box::into_raw` / `Box::from_raw` ownership transfer

A `Box::into_raw` / `Box::from_raw` pair encodes a manual
ownership transfer that the type system cannot check end-to-end:
Rust hands a heap allocation to non-Rust code (FFI, a registry,
a callback closure) and trusts that the same allocation comes
back exactly once for reclamation. Every occurrence has to pass
the issue-#15 audit checklist before it can ship:

1. **Same `T` on both sides.** The pointer's runtime type must
   match the type used in `Box::from_raw::<T>(...)`. A
   `Box::into_raw(Box::<Foo>::new(..))` followed by
   `Box::from_raw(ptr as *mut Bar)` is UB even if `Foo` and
   `Bar` have the same layout.
2. **Same allocator.** Both ends of the round-trip must use the
   same allocator. The workspace uses only the default global
   allocator (no `#[global_allocator]` switch, no `Box::new_in`
   call sites), so this is satisfied by default — but a future
   custom allocator would invalidate every existing pair.
3. **Correct alignment.** `Box::from_raw` assumes the pointer
   meets `mem::align_of::<T>()`. Always true if the pointer
   came from `Box::into_raw` and was never offset; UB if it
   came from `libc::malloc` (which only guarantees `MAX_ALIGN`
   in C, not `align_of::<T>()` for `T` with alignment > 16).
4. **Allocation start, not interior.** The pointer must address
   the start of the allocation. Offsetting (e.g. `ptr.add(1)`)
   between `into_raw` and `from_raw` is UB.
5. **Not already freed.** Each `Box::into_raw` is matched by
   **exactly one** `Box::from_raw`. Zero matchings is a memory
   leak; two or more is double-free / UAF.
6. **Exactly one owner.** While the raw pointer is in flight,
   there is exactly one entity entitled to call `Box::from_raw`
   on it. Multiple entities → race for the reclaim; safe Rust
   re-borrow of the pointer while `Box::from_raw` runs → UAF.

**Rule.** Application code SHOULD NOT use `Box::into_raw` /
`Box::from_raw` directly. The preferred shapes, in order:

1. **A typed RAII wrapper** — the
   `ripdpi-vless/src/scoped_handle.rs` `ScopedHandle<T, F:
   FreeFunction<T>>` is the workspace's general-purpose shape
   for any refcount- or malloc-managed FFI handle. Construct
   from an `unsafe fn from_ptr(*mut T) -> Option<Self>`; the
   `Drop` impl calls `F::free` exactly once. Tests in the same
   module assert "frees exactly once on drop", "panic-unwind
   still frees", "null rejected", and "two handles freed
   independently".
2. **An explicit free callback registered with the FFI.** If
   the C side has a destruction hook, register it and let the
   foreign code free the Rust-owned allocation — keeping the
   allocator boundary one-sided.
3. **`unsafe fn` install + RAII guard reclaim.** Used by
   `ripdpi-vless/src/reality_hook.rs`:
   `install_reality_client_hello_hook` (`unsafe fn`,
   `pub(crate)`) leaks one `Box<RealityCallbackState>` via
   `Box::into_raw` into BoringSSL's `SSL_CTX_set_client_hello_cb`
   `arg` slot. The returned `RealityHookGuard` is move-only
   (`!Copy + !Clone`); its `Drop` impl is the unique site that
   calls `Box::from_raw`, after checking `state_ptr` is non-
   null (defence in depth — Rust cannot actually drop the same
   value twice). The module-level doc-comment enforces the
   "guard outlives the SSL object" contract that the type
   system cannot express on its own.

**Anti-patterns.**

- A safe `pub fn` whose body contains a bare `Box::into_raw`
  and hands the pointer to a foreign API without a matching
  `unsafe fn ..._free(*mut T)` or RAII guard exposed by the
  same module. The function must either be `unsafe fn` with a
  documented `# Safety` contract OR ship the matching reclaim
  API in the same module.
- A `from_raw` whose matching `into_raw` is in a different
  crate. The allowlist entry's `enforcement` field must name
  both sites; if they cross a crate boundary, the upstream
  crate must also publish the typed wrapper so the boundary
  is one-sided.
- `mem::forget(boxed)` as a substitute for `Box::into_raw`.
  Both forms leak the allocation; only `Box::into_raw` returns
  a pointer that can be reclaimed. Using `mem::forget` to
  "leak intentionally" then later trying to `Box::from_raw`
  on an external pointer is UB.

**Workspace inventory.** Exactly one production
`Box::into_raw` / `Box::from_raw` pair in the entire workspace,
plus three test-mode `into_raw` calls each paired in the same
function:

| File | Production `into_raw` | Matching `from_raw` | Test pairs |
|---|---|---|---|
| `ripdpi-vless/src/reality_hook.rs` | `install_reality_client_hello_hook` (line 141) | `Drop for RealityHookGuard` (line 111) | 3 (each paired within the same `#[test]` body) |

**Miri validation.** `cargo +nightly miri test -p ripdpi-vless
reality_hook::tests` runs the four reality-hook unit tests
under Miri, including `guard_reclaims_box_on_drop`. All four
pass: Miri detects no double-free, no use-after-free, and no
aliasing violation along the Drop path.

**Allowlist entry requirements.** A `Box::into_raw` or
`Box::from_raw` allowlist entry's `enforcement` field MUST
state all five of these mandatory fields:

1. **Allocation origin.** Where in the Rust source the
   matching `Box::new(...)` runs (file:function). The
   reviewer must be able to follow the chain
   `Box::new -> Box::into_raw -> ... -> Box::from_raw`
   without leaving the policy entry.
2. **Type `T`.** The concrete type whose `Box<T>` is being
   transferred. The reviewer must verify the same `T`
   appears on both sides — a layout-compatible-but-distinct
   `T'` would be UB.
3. **Allocator.** Default global allocator unless the entry
   names a custom `Allocator` (e.g.
   `Box::<T, MyAlloc>::new_in(...)`). The workspace uses
   only the default global allocator today; any future
   `#[global_allocator]` or `Box::new_in` call site
   invalidates every existing pair and requires re-audit.
4. **Ownership transfer path.** Which entity (struct
   field, FFI slot, registry index, closure capture) holds
   the raw pointer between `into_raw` and `from_raw`, and
   why that entity is `!Copy + !Clone` so the pointer
   cannot be duplicated while in flight.
5. **Deallocation proof.** The single site that calls
   `Box::from_raw`, and the structural reason it is
   reached exactly once: RAII `Drop` impl on a move-only
   guard, type-state transition that consumes the holder,
   FFI-side destructor callback registered in the same
   commit, or equivalent. The proof must explain why a
   second `Box::from_raw` on the same pointer cannot occur
   (Rust's move semantics + the `!Copy + !Clone` of the
   holding type are usually sufficient; if not, what other
   discipline supplies the missing guarantee).

### FFI ownership shapes

When the matched `from_raw` is itself called from a non-
Rust context (the most common reason to reach for
`Box::into_raw`), the boundary MUST take one of these
shapes:

**Shape A — paired `rust_alloc` / `rust_free` exports.** The
crate exposes two `extern "C" fn`s: `rust_alloc_FOO() ->
*mut FOO` performs `Box::into_raw(Box::new(...))`, and
`rust_free_FOO(ptr: *mut FOO)` performs
`Box::from_raw(ptr)` after asserting non-null. The foreign
code is contractually required to call exactly one
`rust_free_FOO` for every `rust_alloc_FOO`. The pair lives
in the same module so a reviewer can match the two
without crossing files. Use this shape when the foreign
code manages the lifetime explicitly and Rust has no
say in when reclamation happens.

**Shape B — keep ownership on one side.** Rust hands the
foreign side a borrowed `&T` or `&mut T` (cast to `*mut
T` only for the duration of the call) and the foreign
side never retains the pointer past the call. No
`Box::into_raw` is needed. Use this shape when the
foreign API takes the pointer only for read-back (e.g.
`SSL_set_session`-style "give us your data, we copy it").

**Shape C — `unsafe fn` install + RAII guard reclaim.**
Rust leaks one Box into a foreign slot via
`Box::into_raw` and immediately returns an
`unsafe`-constructed RAII guard that owns the reclaim
side. The guard's `Drop` impl calls `Box::from_raw` and
nulls the holder field. The install function is
`unsafe fn` because the caller must uphold the
"guard outlives the foreign reference" contract that the
type system cannot express. Use this shape when the
foreign API has no destructor callback and the install
function is the natural moment to bind a Rust lifetime
to the registration. This is the shape used by
`install_reality_client_hello_hook` /
`Drop for RealityHookGuard`.

Mixing the shapes (e.g. `rust_alloc_FOO` paired with a
RAII guard on the Rust side) is permitted only if the
guard's `take()` method releases ownership back to the
foreign code by returning the raw pointer and
`mem::forget`-ing the guard so its Drop does not fire.
The `ScopedHandle::take()` method in
`ripdpi-vless/src/scoped_handle.rs` is the canonical
implementation of that escape hatch.

## `Vec::from_raw_parts` ownership transfer

`Vec::from_raw_parts(ptr, len, cap)` and its allocator-API
counterpart `Vec::from_raw_parts_in(ptr, len, cap, alloc)`
reconstitute a `Vec<T>` from three (or four) raw values. The
resulting `Vec` runs its destructor on drop, which deallocates
the buffer using `dealloc(ptr, Layout::array::<T>(cap)?)` on
whichever allocator was supplied. Every soundness precondition
must hold — even a single mismatched field is UB.

The eight-point audit checklist (issue #16):

1. **Allocation origin.** `ptr` must come from a Rust
   allocation produced by a `Vec<T>` (or `String`, for the
   `String` variant) on the same allocator. A pointer from
   `libc::malloc`, `boxed slice`, `Box<[T]>` after
   `Box::into_raw`, an mmap region, or a foreign allocator
   is UB even if alignment and size happen to match.
2. **Element type `T`.** The pointer must address a buffer
   that was allocated for exactly this `T`. A
   layout-compatible-but-distinct `T'` (e.g. `repr(C)` mirror
   structs) is UB.
3. **Alignment.** The pointer must satisfy
   `mem::align_of::<T>()` — automatic if it came from a
   `Vec<T>::into_raw_parts`; not automatic if it came from
   `libc::malloc` (only `MAX_ALIGN` guaranteed in C) or from
   a `Box<[u8]>` cast to `*mut T` (alignment of `u8` is 1).
4. **Initialized length.** Bytes
   `[0, len * size_of::<T>())` must contain valid `T`
   values. `set_len`-style "leave it uninitialized and
   overwrite later" is UB on any read between
   `from_raw_parts` and the overwrite — including the
   `Drop` impl of any element type that runs destructors.
5. **Capacity.** Bytes `[0, cap * size_of::<T>())` must be
   the exact allocation size the allocator was told about.
   Passing a larger `cap` than the original allocation
   over-reads on drop; smaller leaks the tail.
6. **Allocator compatibility.** For
   `Vec::from_raw_parts_in`, the supplied `Allocator` MUST
   be the same instance (or interchangeable instance) that
   allocated the buffer. Workspace policy: only the default
   global allocator is in use; any future
   `#[global_allocator]` or per-Vec `Allocator` instance
   invalidates every existing pair and requires re-audit.
7. **`len <= cap`.** Required by the `Vec` invariant. A
   `from_raw_parts(p, 8, 4)` violates this immediately and
   is UB on the next `Vec` operation.
8. **Unique ownership.** Between
   `Vec::from_raw_parts` and the resulting `Vec` being
   moved or dropped, no other code may hold a `&[T]`,
   `&mut [T]`, second `Vec<T>`, or raw `*mut T` to the
   same buffer. The reconstituted `Vec` owns the
   allocation exclusively; an aliased view is UB on the
   very next mutation.

**Rule.** Application code SHOULD NOT use
`Vec::from_raw_parts(_in)?`. The preferred shapes, in order:

1. **Safe `Vec` ownership.** Pass `Vec<T>` by value across
   internal APIs; accept `&[T]` or `&mut [T]` from FFI
   callers and `Vec::from(slice)` or `.to_vec()` if you
   need to own. Lets the type system prove every checklist
   point trivially.
2. **`Vec::with_capacity` + `spare_capacity_mut` +
   `set_len`.** When initialising a buffer in-place from
   a `recv`/`read`/foreign-fill call, allocate with
   `Vec::with_capacity(N)`, pass
   `spare_capacity_mut()` (returns
   `&mut [MaybeUninit<T>]`), then assert
   `set_len(n)` for the actually-initialised prefix `n`.
   The `Vec` was always Rust-owned; only the
   "initialised-up-to" cursor changed. This is the std-
   library-blessed equivalent of `from_raw_parts` for the
   common "Rust allocates, foreign code writes" pattern.
3. **A typed buffer wrapper.** When the buffer's lifecycle
   is more complex than a single `recv` (e.g. io_uring
   `IORING_REGISTER_BUFFERS`, page-aligned ring buffers,
   `MAP_PRIVATE` mmap), wrap the allocation in an owner
   type whose API is `&[u8] / &mut [u8]` and whose `Drop`
   handles the matching cleanup. The workspace has two
   reference implementations: `BufferHandle` in
   `ripdpi-io-uring/src/bufpool.rs` (move-only handle into
   a `Box<[UnsafeCell<Box<[u8]>>]>` pool) and `MappedFile`
   in `ripdpi-geo/src/mapped_file.rs` (mmap-backed
   read-only `&[u8]`).
4. **`unsafe fn` boundary + caller contract.** Only when
   the buffer genuinely originates from a foreign
   allocator and Rust must take ownership. The function
   becomes `unsafe fn` with a `# Safety` section that
   enumerates all eight checklist points; the caller
   enters `unsafe { … }` with their own SAFETY comment
   per the documentation contract above. The workspace
   has zero functions of this shape today.

**Anti-patterns.**

- `Vec::from_raw_parts(libc::malloc(n) as *mut T, n /
  size_of::<T>(), n / size_of::<T>())` — allocator
  mismatch (UB on drop), and alignment is unspecified.
  Use `Vec::with_capacity` instead and have the C code
  fill the Rust-allocated buffer.
- `let mut v = Vec::with_capacity(N); recv(v.as_mut_ptr(),
  N); unsafe { v.set_len(N); }` — bypasses
  `spare_capacity_mut`'s `MaybeUninit` typing and is hard
  to audit. The correct shape is
  `recv(v.spare_capacity_mut().as_mut_ptr() as *mut u8,
  N); unsafe { v.set_len(N); }` — the `set_len` line is
  still `unsafe`, but the SAFETY comment can reference the
  initialisation contract of `recv` instead of
  hand-waving about the buffer.
- `String::from_raw_parts(ptr, len, cap)` where bytes are
  not validated UTF-8. `String` carries the UTF-8
  invariant; reconstituting from raw without validating is
  UB on any subsequent string operation. Use
  `String::from_utf8(vec)` (release-mode validation) on a
  Rust-owned `Vec<u8>` instead.

**Workspace inventory.** As of issue #16: **zero**
production occurrences of `Vec::from_raw_parts`,
`Vec::from_raw_parts_in`, `String::from_raw_parts`, or
`Vec::set_len` (verified via
`rg '\bVec(::<[^>]*>)?::(from_raw_parts(_in)?|set_len)\b'`
and `rg '\bString::from_raw_parts\b'` across all crates).
The "Rust allocates, foreign code writes" pattern is
handled by `BufferHandle` and `Vec::with_capacity +
spare_capacity_mut`; the io_uring fixed buffers are
`Box<[u8]>` allocated by `Vec::new(...).into_boxed_slice()`
and never round-trip through raw parts. The two `set_len`
hits in the workspace are
`BufferHandle::set_len(&mut self, usize)` (a safe inherent
method on a typed wrapper that clamps to the underlying
buffer capacity) and `std::fs::File::set_len` (truncation
syscall); neither is `Vec::set_len`.

**Allowlist entry requirements.** A `Vec::from_raw_parts`,
`Vec::from_raw_parts_in`, or `String::from_raw_parts`
allowlist entry's `enforcement` field MUST address every
point of the eight-point checklist above (the same
five-field rubric as `Box::from_raw` is insufficient
because `Vec` carries `len` and `cap` separately and
because `String` adds the UTF-8 invariant):

1. Allocation origin (which Rust `Vec<T>::into_raw_parts`
   or equivalent produced the pointer).
2. Element type `T` (matching on both sides).
3. Alignment proof (allocator guarantee or explicit check).
4. Initialised length (exactly which bytes are valid `T`
   values, and the validity argument).
5. Capacity (matches the original allocation size).
6. Allocator (default global unless named; for
   `from_raw_parts_in`, the allocator instance must be
   the same one that allocated the buffer).
7. `len <= cap` (structural argument).
8. Unique ownership (which holder type carries the parts
   between `into_raw_parts` and `from_raw_parts`, and why
   it is `!Copy + !Clone`).

## Unsafe `String`/`str` construction

`String` and `&str` carry an additional invariant beyond
`Vec<u8>`: the byte contents MUST be valid UTF-8 in the
Unicode standard's strict sense (well-formed UTF-8, no
overlong encodings, no surrogate code points, no invalid
continuation bytes). The library and the language both
assume this invariant for every operation: `chars()`
iteration, `.len()`/`.is_char_boundary()`/`.split_at()`,
formatting, slicing with `&s[a..b]`, and all higher-level
APIs (regex, parser combinators, JSON). Violating it
produces UB on the very next read, even if the bad bytes
are never directly observed.

Four unsafe constructors can violate this invariant:

| API | Skipped check | Owned? |
|---|---|---|
| `String::from_raw_parts(ptr, len, cap)` | UTF-8 validity AND every `Vec::from_raw_parts` precondition | Yes |
| `String::from_utf8_unchecked(bytes)` | UTF-8 validity (allocation already Rust-owned) | Yes |
| `str::from_utf8_unchecked(&bytes)` | UTF-8 validity (borrowed) | No |
| `str::from_boxed_utf8_unchecked` | UTF-8 validity (boxed) | Yes |

The audit checklist for each occurrence:

1. **UTF-8 validity proof.** Where do the bytes come from?
   The proof MUST be either:
   - Bytes copied verbatim from another `&str` / `String`
     (already valid by the source's invariant).
   - Output of a known-UTF-8-correct producer (Rust's
     `format!`, `serde_json`'s output writer, etc.)
     with the producer named in the SAFETY comment.
   - A previously-validated slice; the validation site MUST
     be in the same function or a same-crate helper with
     a documented type-state transition.
   - Trivially-UTF-8 bytes by construction (ASCII-only
     output, hex-digit alphabet, base64 alphabet, etc.)
     with the construction step named.

   Network / file / FFI / parser input is **never** a
   sound source — there's always a hostile path that
   plants malformed bytes. Use `String::from_utf8`,
   `str::from_utf8`, or `String::from_utf8_lossy`
   instead.
2. **Initialised.** Same checklist point as
   `Vec::from_raw_parts`: bytes `[0, len)` of the
   allocation must be initialised. UB if any byte in
   that range is `MaybeUninit`-uninitialised.
3. **Live.** The pointee must outlive the returned
   reference's lifetime. For `str::from_utf8_unchecked`
   this is bounded by the input slice; for the owned
   variants the new `String` takes ownership and the
   liveness chain transfers to it.
4. **Unique ownership (owned variants only).** Same
   checklist point as `Vec::from_raw_parts`: no aliased
   `Vec<u8>`/`&[u8]`/`&str` to the same buffer may exist
   while the new `String` is live.
5. **`len`/`cap` correctness (`String::from_raw_parts`
   only).** Inherits every `Vec::from_raw_parts`
   precondition above, plus the UTF-8 invariant. The
   compound contract is the strictest in std.

**Rule.** Application code SHOULD NOT use any of the
four unsafe `String`/`str` constructors. The preferred
shapes, in order:

1. **`String::from_utf8(bytes)` (returns `Result`).** The
   release-mode validated alternative; one linear scan
   over the bytes. This is the workspace's default and
   appears at every parser/network boundary
   (`ripdpi-warp-core/src/socks.rs`,
   `ripdpi-tuic/src/protocol.rs`,
   `ripdpi-relay-core/src/socks/auth.rs`,
   `ripdpi-diagnostics-tls/src/tls/certs.rs`,
   `ripdpi-geo/src/lib.rs`).
2. **`String::from_utf8_lossy(&bytes)` (returns
   `Cow<str>`).** Use when the input is best-effort
   logging/classification and invalid sequences should
   be substituted with U+FFFD rather than rejected.
   Used by the failure-classifier crates
   (`ripdpi-failure-classifier`) and packet introspection
   (`ripdpi-packets/src/classify.rs`).
3. **`str::from_utf8(&bytes)` (returns `Result`).** The
   borrowed variant; same one-scan cost. Used at the
   parser boundaries (`ripdpi-vless/src/wire.rs`,
   `ripdpi-naiveproxy/src/connect_tunnel.rs`,
   `ripdpi-relay-core/src/socks/udp_frame.rs`,
   `ripdpi-shared-priors/src/lib.rs`, the DoH chunk
   reader).
4. **Bytes-only API.** If the consumer doesn't need a
   `str`/`String`, keep the data as `&[u8]` / `Vec<u8>` /
   `bstr::BStr` and skip the validation entirely. The
   `ripdpi-packets` HTTP host-extraction path stays
   `&[u8]` until the final `from_utf8_lossy` at the
   classifier surface.

**Anti-patterns.**

- `String::from_utf8_unchecked(network_response)` —
  hostile input is **never** guaranteed UTF-8. Always
  use the validated `String::from_utf8`.
- `str::from_utf8_unchecked(&buf[..n])` where `buf` is a
  recv buffer — same problem; use `str::from_utf8` and
  propagate the `Result`.
- `String::from_raw_parts(ptr, len, cap)` — combines
  every `Vec::from_raw_parts` failure mode with the
  UTF-8 invariant. There is no situation in this
  workspace where this is the right tool.
- `String::from_utf8(bytes).unwrap()` on a non-trusted
  input — moves the panic from validation to the
  unwrap site without fixing the underlying issue. Use
  `String::from_utf8(bytes).map_err(...)` or
  `String::from_utf8_lossy(&bytes).into_owned()`.

**Workspace inventory.** As of issue #17: **zero**
production occurrences of `String::from_raw_parts`,
`String::from_utf8_unchecked`,
`str::from_utf8_unchecked`, or
`str::from_boxed_utf8_unchecked`. Every byte-to-string
conversion in the workspace uses one of the four
preferred shapes above. The scanner enforces zero
baseline going forward.

**Allowlist entry requirements.** A `String::from_raw_parts`,
`String::from_utf8_unchecked`, or
`str::from_utf8_unchecked` allowlist entry's
`enforcement` field MUST address every point of the
checklist above as six NAMED mandatory fields:

1. **UTF-8 validity proof.** Which producer / validator
   guarantees the input is valid UTF-8, and why that
   guarantee survives every reachable code path.
2. **Input trust boundary.** Where do the bytes
   physically enter Rust ownership? Acceptable origins:
   `'static` rodata, the output of `format!` / `write!`,
   a previously-validated `&str` / `String`, an ASCII /
   hex / base64 alphabet enforced at the parser layer,
   or a Rust-allocated and Rust-filled buffer whose
   producer is named and checked. **Forbidden origins:**
   network reads, file reads, FFI inputs, unbounded
   parser output, any external API surface. Untrusted
   bytes MUST use `String::from_utf8` / `str::from_utf8`
   / `String::from_utf8_lossy` instead, propagating the
   `Result` to the caller.
3. **Initialised.** Matching `Vec::from_raw_parts`
   discipline for the owned variants.
4. **Live.** Lifetime argument for the borrowed
   variant; ownership-transfer argument for the owned
   variants.
5. **Unique ownership** (owned variants only). Which
   `!Copy + !Clone` holder carries the bytes between
   the validation site and the unchecked constructor.
6. **`len`/`cap` correctness** (`from_raw_parts` only).

Every allowlisted occurrence MUST also be preceded by
an inline `// SAFETY:` comment in the source enumerating
the same six fields locally — the allowlist entry is
the auditor-facing summary; the SAFETY comment is the
reviewer-facing proof at the call site. Per
`docs/rust-soundness-policy.md` § "Documentation
contract", every unsafe block in production code
already requires a SAFETY comment; this rule restates
the requirement for the unchecked-string case where
the consequence (UTF-8-invariant break → UB on the
next `chars()` iteration) is particularly easy to
overlook.

For `String::from_raw_parts` specifically, the
allowlist entry must address ALL eight
`Vec::from_raw_parts` checklist points PLUS the six
fields above — the strictest single-API contract in
std.

## Allocator mismatch across FFI

When an allocation crosses an FFI boundary, the
**same** allocator that produced the pointer MUST be
the one that frees it. The Rust default global
allocator (`std::alloc::System` on Unix targets) and
libc's `malloc` / `free` may or may not be the same
heap — the relationship is target- and toolchain-
defined and changes silently on a `#[global_allocator]`
switch. Mixing them is undefined behaviour.

The four classic allocator-mismatch patterns:

1. **C allocates, Rust frees.**
   `Box::from_raw(libc::malloc(n) as *mut T)` — the
   `Box::drop` calls the Rust global allocator's
   `dealloc`, which may not be `libc::free`. Even when
   it is, the layout that `dealloc` reconstructs
   (`Layout::for_value(&*ptr)`) might differ from what
   `malloc` actually saw, and `dealloc` is contractually
   not allowed to handle that mismatch.
2. **Rust allocates, C frees.** `let p =
   Box::into_raw(Box::new(t)); foreign_free(p);` — the
   foreign code calls `libc::free` (or another C
   deallocator) on a pointer the Rust global allocator
   owns. Same UB as above, mirrored.
3. **Wrong-allocator `CString::from_raw`.**
   `CString::from_raw(libc::malloc(n) as *mut c_char)`
   — `CString::drop` runs the Rust deallocator on a
   `libc::malloc`-allocated buffer. UB.
4. **Allocator-mismatched `Vec::from_raw_parts_in`.**
   Already covered in
   "`Vec::from_raw_parts` ownership transfer" point 6
   (allocator compatibility).

**Rule.** Each allocation that crosses an FFI boundary
MUST take one of these forms:

1. **Foreign-managed lifetime.** The foreign library
   allocates AND frees; Rust receives a `*mut T` /
   `*const T` and either:
   - never frees it (non-owning observer pattern; the
     foreign side guarantees the pointer outlives
     every Rust use), OR
   - explicitly calls the foreign deallocator (e.g.
     `SSL_CTX_free`, `EVP_PKEY_free`) inside an RAII
     wrapper. The workspace's `ScopedHandle<T, F:
     FreeFunction<T>>` in
     `ripdpi-vless/src/scoped_handle.rs` is the
     canonical implementation.
2. **Rust-managed lifetime.** Rust allocates AND
   frees; the foreign side receives a borrowed `*const
   T` / `*mut T` for the duration of a call and never
   retains it past the call. No `Box::into_raw`
   needed.
3. **Paired `rust_alloc` / `rust_free` exports** (also
   documented in
   "`Box::into_raw` / `Box::from_raw` ownership
   transfer" § "FFI ownership shapes"). The crate
   exposes two `extern "C" fn`s: `rust_alloc_FOO() ->
   *mut FOO` (Box::into_raw) and `rust_free_FOO(*mut
   FOO)` (Box::from_raw). Foreign code is contractually
   required to call exactly one `rust_free_FOO` for
   every `rust_alloc_FOO`.
4. **Unsafe-fn install + RAII reclaim** (also
   documented in
   "`Box::into_raw` / `Box::from_raw` ownership
   transfer" § "FFI ownership shapes"). Rust leaks one
   Box via `Box::into_raw` and reclaims it in the
   guard's `Drop`.

**Anti-patterns.**

- `Box::from_raw(libc::malloc(n) as *mut T)` — see
  pattern 1 above.
- `unsafe { libc::free(b.as_ptr() as *mut _) }` for
  any `Box<T>` / `Vec<T>` / `String` `b` — see pattern
  2 above. The `free` runs on a Rust allocation.
- `CString::from_raw(c_string_returned_by_strdup)` —
  `strdup` uses `malloc`, but `CString::drop` runs the
  Rust deallocator.
- A scanner allowlist entry that names the matching
  `into_raw` but the partner lives in a different
  crate. The two must live in the same module so a
  reviewer can match them without crossing files.

**Workspace inventory.** As of issue #18: **zero**
production occurrences of any allocator-crossing
pattern.

- `rg '\blibc::(malloc|calloc|realloc|free)\b'` — zero
- `rg '\bCString::(from_raw|into_raw)\b'` — zero
- `rg '#\[global_allocator\]'` — zero (workspace uses
  the default `std::alloc::System`)
- `rg 'extern "C" \{'` — exactly one
  `extern "C" {}` block in
  `ripdpi-vless/src/reality_hook.rs` (BoringSSL Reality
  client_hello hook). The three imported BoringSSL
  functions are
  `SSL_handshake_get_x25519_private_key`
  (fills a caller-owned 32-byte stack buffer; no
  allocation crosses the boundary),
  `SSL_CTX_set_client_hello_cb` (installs a Rust
  callback + Rust-owned `Box::into_raw` `arg` — the
  Rust-managed lifetime reclaimed by
  `RealityHookGuard::Drop` per issue #15), and
  `SSL_get_SSL_CTX` (returns a BoringSSL-managed
  pointer that Rust never frees — non-owning observer
  per shape 1). All three are sound.

The only Rust→C heap transfer in the workspace is the
already-audited `Box::into_raw(Box<RealityCallbackState>)`
/ `Drop for RealityHookGuard` pair (issue #15,
Miri-validated).

**Allowlist entry requirements.** A `libc::malloc`,
`CString::from_raw`, or `CString::into_raw` allowlist
entry's `enforcement` field MUST address every point
below:

1. **C-allocator provenance.** Which foreign function
   produced the pointer (`libc::malloc`, `strdup`,
   `EVP_PKEY_new`, etc.). The reviewer must be able to
   follow the chain `foreign_alloc -> ... -> matching
   free` without leaving the policy entry.
2. **Matching deallocator.** The C function that frees
   the allocation. Must be the documented dual of the
   producer; `libc::malloc` is paired with
   `libc::free`, not with `Box::drop`.
3. **Type and layout.** Which `T` the pointer
   addresses and how the alignment is guaranteed
   (`malloc` only guarantees `MAX_ALIGN`; if `T` has
   higher alignment requirements use `posix_memalign`
   or `aligned_alloc`).
4. **Pair locality.** Both ends of the
   allocation/deallocation must live in the same
   module or be exposed as a documented `rust_alloc_FOO`
   / `rust_free_FOO` pair.
5. **No allocator switch.** Whether the entry remains
   sound if a future `#[global_allocator]` is added to
   the workspace. If not, the entry must say so
   explicitly so a future contributor can re-evaluate.

## `Vec::set_len` initialisation contract

`Vec::set_len(new_len)` is an `unsafe fn` that adjusts
the length field of a `Vec<T>` without touching the
buffer. After the call, the `Vec` claims that bytes
`[0, new_len * size_of::<T>())` of its allocation
contain valid `T` values. Every read, drop, and
`&[..]` / `&mut [..]` borrow assumes that claim is
true. Failures:

| Failure mode | Consequence |
|---|---|
| `new_len` past the initialised prefix | Drop runs on uninit memory (UB if `T: Drop`); `&[..]` exposes uninit bytes (UB on any subsequent read). |
| `new_len > capacity` | UB on the next push / resize / drop — `Vec` assumes its length-cap invariant. |
| Panic between `with_capacity(N)` and `set_len(n)` while the spare region is partly written | The Vec's len is still 0 (set_len hasn't run), so Drop runs on no elements. Safe for `T: !Drop` (e.g. `u8`); for `T: Drop` the partially-initialised tail is leaked but not unsoundly used. |
| `&mut [u8]` borrow of the spare region before `set_len` | Sound because the spare region is typed `MaybeUninit<T>`. Reading without writing is the failure mode. |

The audit checklist for every `Vec::set_len(n)` site:

1. **Initialised prefix.** A producer wrote valid `T`
   values to every slot in `[0, n)` before
   `set_len(n)` runs. The producer is named explicitly
   in the SAFETY comment (e.g. "`recv(2)` returned
   `n` and is documented to write `n` bytes",
   "`MaybeUninit::write` was called for each slot in
   the loop above").
2. **`n <= capacity`.** Asserted on the line(s)
   immediately above the `set_len` call. `Vec`'s
   internal invariant breaks otherwise.
3. **Panic-path soundness.** Either:
   - `T: !Drop` (e.g. `u8`, `u32`, `bool`,
     `MaybeUninit<U>`), in which case the
     half-initialised tail doesn't matter on
     unwind — `len` stays 0 and Drop is a no-op, OR
   - a scope-bound RAII guard reduces `len` to the
     last-known-good prefix on unwind. The
     `std::vec::Drain` and
     `Vec::extend_from_slice` implementations are
     the std reference for this pattern.
4. **No re-entrant reads.** Between the
   `with_capacity` / `reserve` / `spare_capacity_mut`
   site and the matching `set_len`, no code path may
   re-borrow the Vec as `&[T]` / `&mut [T]` —
   the spare region's typing is `MaybeUninit<T>`, not
   `T`, and accessing it as `T` is UB regardless of
   the buffer's runtime contents.

**Rule.** Application code SHOULD NOT call
`Vec::set_len` directly. The preferred shapes, in
order:

1. **Safe `Vec::push` / `Vec::extend` /
   `Vec::extend_from_slice`.** The bytes are typed
   `T` on the way in; no `MaybeUninit` exists; no
   `set_len` needed.
2. **`Vec::with_capacity` + `spare_capacity_mut` +
   guarded `set_len`.** Use when a foreign filler
   (`recv`, `read`, FFI buffer fill) writes into a
   Rust-allocated buffer. The
   `spare_capacity_mut()` typing
   (`&mut [MaybeUninit<T>]`) keeps the
   uninitialised state visible to the type system;
   the filler writes through `MaybeUninit::write`;
   the matching `set_len(n)` runs only after the
   filler reports `n`. This is the workspace's
   recommended idiom for the "Rust allocates,
   foreign code writes" pattern, demonstrated end-
   to-end by
   `vec_with_capacity_spare_capacity_round_trip_models_recv_fill`
   in `ripdpi-vless/src/scoped_handle.rs`.
3. **A typed buffer wrapper.** When the lifecycle
   spans multiple operations (e.g. io_uring fixed
   buffers), encapsulate the spare-region writing in
   a safe `&mut [u8]`-handing-out wrapper. The
   workspace's `BufferHandle` in
   `ripdpi-io-uring/src/bufpool.rs` is the reference:
   `BufferHandle::set_len(&mut self, len: usize)` is
   a SAFE inherent method that clamps to
   `buffer_size`; the caller never sees
   `MaybeUninit<u8>` or the bare `Vec::set_len`.

**Anti-patterns.**

- `let mut v = Vec::with_capacity(N); foreign_fill(v.as_mut_ptr(), N); unsafe { v.set_len(N); }`
  — bypasses `MaybeUninit` typing, hard to audit,
  and the SAFETY comment must hand-wave about the
  foreign contract. The correct shape is
  `foreign_fill(v.spare_capacity_mut().as_mut_ptr().cast(), N); unsafe { v.set_len(n) };`
  with `n <= N`.
- `unsafe { v.set_len(n) }` where the loop above
  wrote `n` elements via index assignment
  (`v[i] = …`) instead of `MaybeUninit::write` —
  `v[i]` is `&mut T` and assigns through, but the
  Vec's `len` was 0 at the time, so `v[i]` is itself
  UB. Use `spare_capacity_mut()[i].write(value)`
  instead.
- `unsafe { v.set_len(n) }` immediately followed by
  `&v[..]` when only some of `[0, n)` was written —
  the borrow exposes uninit bytes. Set `len` to the
  initialised count, not the buffer capacity.

**Workspace inventory.** As of issue #19: **zero**
production `Vec::set_len` calls. The single
occurrence in the workspace is the regression test
`vec_with_capacity_spare_capacity_round_trip_models_recv_fill`
in `ripdpi-vless/src/scoped_handle.rs:331`, which
demonstrates the recommended idiom (per shape 2
above) end-to-end. The other three `.set_len(`
matches in the workspace are NOT `Vec::set_len`:

| File | Method | Allowlisted? |
|---|---|---|
| `ripdpi-io-uring/src/tun.rs:95` | `BufferHandle::set_len(&mut self, usize)` | No — safe inherent method on the io_uring buffer wrapper; clamps to `buffer_size`. |
| `ripdpi-proxy-runtime/src/runtime/relay/stream_copy_uring/inbound_zc.rs:43` | `BufferHandle::set_len` (same method) | No — same as above. |
| `ripdpi-proxy-runtime-adapter/src/platform.rs:363` | `std::fs::File::set_len(0)` | No — truncate syscall. |

**Allowlist entry requirements.** A `unsafe Vec::set_len`
allowlist entry's `enforcement` field MUST address every
point as FIVE NAMED mandatory fields:

1. **Initialisation proof.** Which code wrote valid
   `T` values to slots `[0, n)` before the `set_len`
   ran. Name the producer explicitly (e.g.
   "`simulated_recv_fill` wrote each slot via
   `MaybeUninit::write` in the loop above",
   "`libc::recv` returned `n` and is documented to
   write `n` bytes"). "The buffer is filled" is not a
   proof; the writer function must be named.
2. **`n <= capacity` proof.** Where the assertion
   lives (typically an `assert!` on the line above
   the `set_len`). If the guarantee is structural
   (e.g. `n` is the return value of a function whose
   contract is `0 <= ret <= capacity`), name the
   function and the contract.
3. **Element type and Drop semantics.** Name `T`
   explicitly and whether `T: Drop`. `T: !Drop`
   (`u8`, `u32`, `bool`, `MaybeUninit<U>`) makes
   panic-path soundness trivial; `T: Drop` requires a
   scope-bound RAII guard that reduces `len` to the
   last-known-good prefix on unwind.
4. **Panic-path safety.** The argument that an unwind
   between `with_capacity` and `set_len` cannot run
   destructors on uninitialised memory. Either
   field 3's `T: !Drop` is sufficient, OR the entry
   names the unwind guard.
5. **Owner.** Crate/team responsible for keeping the
   entry sound. Matches the `owner` TOML field but
   restated in the `enforcement` summary so the
   reviewer can see the responsible party without
   scrolling.

**CI Miri coverage.** Every `unsafe Vec::set_len`
allowlist entry SHOULD also be exercised under Miri
in `scripts/ci/run-rust-miri.sh` (the workspace's
"targeted Miri smoke" CI gate). The existing
`scoped_handle::tests` Miri coverage already includes
the workspace's only `Vec::set_len` site (the
`with_capacity` + `spare_capacity_mut` + `set_len`
round-trip test); future allowlisted occurrences in
production code must add their own Miri coverage in
the same script so the strict-provenance borrow-
stacked machine validates them at every PR.

## `MaybeUninit` correctness

`MaybeUninit<T>` is the std-library escape hatch for
"I have a slot the size and alignment of `T` but I
have not initialised it yet". The type carries no
runtime tag; the compiler trusts the programmer to
prove `T`-validity before any of the five
`assume_init`-family methods runs. The five methods
and their failure modes:

| API | Failure mode if slot is uninit |
|---|---|
| `MaybeUninit<T>::assume_init(self) -> T` | UB on Drop and on every subsequent read. |
| `assume_init_ref(&self) -> &T` | UB on every read through the `&T`. |
| `assume_init_mut(&mut self) -> &mut T` | UB on every read and on the write of a non-trivial `T`. |
| `assume_init_drop(&mut self)` | UB if Drop reads any uninit field. |
| `assume_init_read(&self) -> T` | UB on every read of the returned `T`, and the original slot is logically duplicated (`T: Copy`-style) so Drop must not later run on the same allocation. |

The audit checklist for every `assume_init*` call:

1. **Every byte of `T` written.** A producer wrote
   valid bytes for every field of `T` BEFORE
   `assume_init` ran. The producer is named in the
   SAFETY comment (e.g. "the C call `getsockopt`
   filled all `size_of::<T>()` bytes",
   "`MaybeUninit::write` was called for each field
   in the block above").
2. **Padding handled.** If `T` has padding bytes
   (e.g. `#[repr(C)] struct { a: u8, b: u32 }` has
   3 bytes of padding between `a` and `b`), those
   padding bytes are EITHER zeroed at allocation
   (e.g. via `mem::zeroed`) OR proven to be
   irrelevant (the consumer reads only the named
   fields, never `as_bytes` / `transmute` of the
   whole struct).
3. **No `&T` / `&mut T` to uninit memory.** The
   only sound way to read uninit slots is through
   `MaybeUninit<T>` (or `&[MaybeUninit<T>]`); even
   creating a `&T` to uninit memory and immediately
   discarding it is UB. `MaybeUninit::as_ptr()` is
   sound (it returns `*const T`, not `&T`).
4. **Drop semantics.** If `T: Drop` and the slot is
   only partially initialised on a panic-unwind
   path, the partial state must not reach Drop. The
   std reference pattern is `MaybeUninit<T>` slots
   inside an array with a scope-bound RAII guard
   that calls `assume_init_drop` only on indices
   that have been written.
5. **Reference creation timing.** Between the slot
   allocation (`MaybeUninit::uninit()`) and the
   `assume_init`, no code path may borrow the
   underlying memory as `&T` / `&mut T` — only
   `&mut [MaybeUninit<T>]` is sound for uninit
   buffers.

**Rule.** Application code SHOULD NOT use
`assume_init` family methods. The preferred shapes,
in order:

1. **Safe constructors.** `T::default()`, struct
   literals with all fields named, `Vec::new()` +
   `push`, `String::new()` + `push_str`, etc.
2. **`array::from_fn(|i| init(i))`** for arrays
   that can be initialised by a closure. The
   closure runs in element order; if it panics
   mid-build, std's drop guard correctly drops the
   prefix it built.
3. **`Vec::with_capacity` + `spare_capacity_mut` +
   guarded `set_len`** (per
   "`Vec::set_len` initialisation contract"). The
   `spare_capacity_mut()` typing keeps `MaybeUninit`
   visible; writes go through
   `MaybeUninit::write`; `set_len` runs only after
   the producer reports `n`.
4. **`unsafe fn` recv-style API directly accepting
   `&mut [MaybeUninit<T>]`.** Std's
   `UdpSocket::recv_from` / `TcpStream::read` /
   `read_buf` accept `&mut [MaybeUninit<u8>]`
   natively (Rust 1.85+); no `assume_init` needed
   because the bytes go through
   `slice::from_raw_parts(..., received)` to
   produce a `&[u8]` of exactly the initialised
   prefix. This is the pattern used at the only
   `MaybeUninit` production site in the workspace
   (`ripdpi-privileged-ops/src/linux/experimental_tier3/icmp_wrapped_udp.rs`).

**Anti-patterns.**

- `let mut a: [MaybeUninit<T>; N] = unsafe { MaybeUninit::uninit().assume_init() };` —
  the famous "uninit assume_init" trick. Sound only
  because `MaybeUninit<T>` has no validity
  invariant. Use
  `[const { MaybeUninit::uninit() }; N]` (Rust
  1.79+) or
  `MaybeUninit::<[MaybeUninit<T>; N]>::uninit().assume_init()`
  with a SAFETY comment naming the
  "MaybeUninit<MaybeUninit<T>> always valid"
  argument.
- `let r: &T = unsafe { uninit.assume_init_ref() }`
  followed by `r.field` when the slot's bytes are
  partially uninit — UB on the field access.
- `let v: T = unsafe { uninit.assume_init() }` for
  `T: Drop` when the slot is only partially
  initialised — Drop runs on uninit memory.
- `mem::uninitialized::<T>()` — soft-deprecated;
  use `MaybeUninit::<T>::uninit()` instead. (The
  workspace has zero occurrences.)

**Workspace inventory.** As of issue #20:

| Site | Shape | Audit |
|---|---|---|
| `ripdpi-privileged-ops/.../icmp_wrapped_udp.rs:27` | `[MaybeUninit<u8>; 8192]` recv buffer, consumed via `slice::from_raw_parts(buf.as_ptr().cast::<u8>(), received)` | Sound. `UdpSocket::recv_from` natively accepts `&mut [MaybeUninit<u8>]` and is documented to initialise the first `received` bytes. The follow-on `slice::from_raw_parts` is allowlisted under issue #6. No `assume_init*` is used. |
| `ripdpi-vless/.../scoped_handle.rs:304` | Test-mode `&mut [MaybeUninit<u8>]` parameter in `simulated_recv_fill` | Sound. Issue #16 regression test demonstrating the workspace's recommended `with_capacity + spare_capacity_mut + set_len` idiom. Miri-validated. |

**ZERO production `assume_init` / `assume_init_ref` /
`assume_init_mut` / `assume_init_drop` /
`assume_init_read` calls** in the entire workspace.
Every byte-fill operation goes through either
`recv_from(&mut [MaybeUninit<u8>])` followed by
`slice::from_raw_parts` (issue-#6-audited) or
`Vec::with_capacity + spare_capacity_mut +
MaybeUninit::write + set_len` (issue-#16-audited).
The scanner enforces zero baseline going forward.

**Allowlist entry requirements.** An
`MaybeUninit::assume_init` allowlist entry's
`enforcement` field MUST address every point as
FIVE NAMED mandatory fields:

1. **Initialisation proof** (which producer wrote
   every byte of `T`).
2. **Padding argument** (padding bytes zeroed or
   proven irrelevant).
3. **Reference safety** (no `&T`/`&mut T` to
   uninit memory created before `assume_init`).
4. **Drop safety** (panic-path guard, or `T: !Drop`
   stated explicitly).
5. **Owner** (crate/team, restated in the
   enforcement summary).

**CI Miri coverage.** Per
"`Vec::set_len` initialisation contract", any new
allowlisted `assume_init*` site in production code
SHOULD also be exercised under Miri in
`scripts/ci/run-rust-miri.sh`. The existing
`scoped_handle::tests` Miri coverage validates the
recommended `with_capacity + spare_capacity_mut +
set_len` round-trip (which writes via
`MaybeUninit::write` and would catch a regression
that introduced unsound `assume_init` usage in the
same crate).

## Zero-initialisation validity

`mem::zeroed::<T>()` and its variants
(`MaybeUninit::<T>::zeroed`, `ptr::write_bytes(ptr, 0, n)`,
`libc::memset(ptr, 0, n)`) produce a `T` (or `n` `T` values)
whose bytes are all zero. The runtime cost is one `memset`;
the soundness cost depends entirely on whether the all-zero
bit pattern is a valid `T`.

**Types where zero IS a valid bit pattern:** integers,
`f32`/`f64`, `[u8; N]` and other arrays of zero-valid
types, `#[repr(C)]` POD structs whose every field is
zero-valid, `Option<&T>` / `Option<Box<T>>` /
`Option<NonNull<T>>` / `Option<NonZeroU32>` (the niche
optimisation makes zero represent `None`),
`MaybeUninit<T>`, and raw pointers `*mut T` / `*const T`
(null bit pattern is fine; dereferencing it is the UB).

**Types where zero is NOT a valid bit pattern (UB to
construct via `mem::zeroed`):** `&T` / `&mut T` (never
null), `Box<T>` / `Rc<T>` / `Arc<T>` (never null),
`NonNull<T>`, `NonZeroU*` / `NonZeroI*`, `bool` byte
values outside `{0, 1}`, `char` surrogates and
out-of-range code points, enums whose `0` discriminant
is not declared (e.g. `#[repr(u8)] enum { A = 1, B = 2
}`), function pointers (`fn()`, `extern "C" fn(...)`),
and any `#[repr(transparent)]` newtype around the above.

The audit checklist for each zero-init site:

1. **Identify `T`** (or the element type for
   `ptr::write_bytes` / `libc::memset`).
2. **Field-by-field zero-validity.** If `T` is a
   struct/enum, every field's all-zero bit pattern must
   be in the field's validity domain. Recurse into
   nested types.
3. **Reference/pointer/function-pointer check.** Does
   `T` transitively contain any `&T` / `&mut T` /
   `Box<T>` / `NonNull<T>` / `NonZero*` / function
   pointer / non-zero-variant enum? If yes,
   `mem::zeroed::<T>` is UB.
4. **`#[repr(C)]`.** FFI structs MUST be `#[repr(C)]` so
   the layout is stable and field offsets are
   knowable. Zero-init across versions of a
   `#[repr(Rust)]` struct is fragile because the
   compiler is free to reorder fields and change
   padding.
5. **Padding bytes.** With `mem::zeroed`, padding bytes
   are guaranteed zero; with `MaybeUninit` they're
   tracked as uninit. This matters when the consumer
   reads the struct as `&[u8]` or passes it across FFI
   as a byte block.

**Rule.** Application code SHOULD NOT use `mem::zeroed`
or its variants. The preferred shapes, in order:

1. **Safe constructors:** `T::default()`, struct
   literals with every field named, `Vec::new()`,
   `String::new()`, `[const { … }; N]`.
2. **`MaybeUninit` staged init:** `let mut u =
   MaybeUninit::<T>::uninit(); /* fill */ unsafe {
   u.assume_init() }`. Forces field-by-field
   accountability — no "memset and pray".
3. **Field-by-field zero, not whole-struct zero:**
   `let s = MyStruct { a: 0, b: 0, c: false };` —
   the compiler chooses the byte representation; you
   don't pretend zero bytes are a valid `MyStruct`.

**Workspace inventory.** As of issue #21: **two**
sound production sites, both audited and allowlisted.
**Zero `MaybeUninit::zeroed`**, **zero `libc::memset`**.

| File | API | Element type | Sound because |
|---|---|---|---|
| `ripdpi-io-uring/src/probe.rs:85` | `mem::zeroed::<libc::utsname>()` | `libc::utsname` | `#[repr(C)]` with every field `[c_char; N]` (= `[i8; N]`). `i8` has no validity invariant; zero bytes also represent the empty NUL-terminated C string each field is contractually allowed to start as (kernel `uname(2)` fills every field). |
| `ripdpi-privileged-ops/src/linux/mmap_region.rs:65` | `ptr::write_bytes(*mut u8, 0, len)` | `u8` | Element type is `u8`; every bit pattern is a valid `u8`. Destination is exclusive (`&mut self` on the owning `MmapRegion: !Copy`); no aliased reader can observe a mid-write state. Bounds (`len`) come from the region's own owned `NonZeroUsize`. |

**Anti-patterns.**

- `let s: MyStruct = unsafe { mem::zeroed() };` where
  `MyStruct` contains a `Box<u8>` field — UB; zero is
  a null Box.
- `let f: fn() = unsafe { mem::zeroed() };` — UB;
  zero is not a valid function pointer.
- `unsafe { ptr::write_bytes(buf.cast::<MyEnum>(), 0,
  n) };` for an enum whose `0` variant is not
  declared — UB on every subsequent read.
- `let mut x = MaybeUninit::<&T>::zeroed(); unsafe {
  x.assume_init() };` — UB; references cannot be
  null.

**Allowlist entry requirements.** A `mem::zeroed`,
`MaybeUninit::zeroed`, `ptr::write_bytes`, or
`libc::memset` allowlist entry's `enforcement` field
MUST address all FIVE NAMED mandatory fields:

1. **Element type and layout** (concrete `T`, its
   `#[repr]`, the field list if relevant).
2. **Field-by-field zero-validity** (every field's
   validity domain; recursive if a field is itself a
   struct).
3. **No invariant-bearing fields** (no references,
   `NonNull`, `NonZero*`, `Box`, function pointer,
   non-zero-variant enum).
4. **Padding-byte semantics** (if the consumer reads
   the struct as `&[u8]`, that the padding-zero claim
   is documented; otherwise that the consumer reads
   only named fields).
5. **Owner.**

## Ownership must be types, not flags

A boolean field named `registered`, `is_alive`, `destroyed`,
`initialized`, `disowned`, `owned_by_*`, or `freed` does not encode
ownership — it only records a *belief* about a separate resource's
state. If the resource is owned, the owning struct is the
truth-bearing handle; the flag is at best a diagnostic check. If safe
code can duplicate the flag, or set it to `true` without actually
acquiring the underlying resource, or to `false` without releasing
it, the flag silently becomes a lie and every downstream branch that
depends on it is unsound.

**Rule.** Ownership and liveness MUST be represented by:

1. A **move-only handle** (no `Copy`/`Clone`) whose existence proves
   the resource is held. Drop releases. The compiler enforces
   "at most one owner".
2. An **RAII guard** that performs cleanup in `Drop`. A `bool` field
   inside the guard is acceptable **only** when used as a
   conditional-cleanup gate (`if self.registered { unregister(); }`)
   and the struct itself is move-only with a private field. The
   flag is then diagnostic; the move-only struct is the ownership
   token.
3. **Typestate** — distinct types per phase of the lifecycle, with
   transitions implemented as consuming methods (`fn destroy(self)`).
   Invalid transitions don't compile.
4. A **real reference count** (`Arc<T>`, `Rc<T>`, custom refcount
   with atomic increment/decrement under a release/acquire fence).
5. A **validated state machine** (enum + match) where every
   transition returns `Result` and unreachable states are
   `unreachable!()`.

**Anti-patterns reviewers reject.**

- A `pub struct` with a `pub registered: bool` field. Anyone can
  set the flag; the ownership semantics collapse.
- `Cell<bool>` for lifecycle: interior mutability with no
  synchronisation, no exclusivity, no auditable transitions.
- `if self.is_alive { unsafe { use_resource() } }` where the flag is
  the only safety guard. `debug_assert!(self.is_alive)` alongside is
  the release-mode trap (see § "`debug_assert!` as memory-safety
  guard").
- Multiple flags acting as a manual state machine (e.g.
  `initialized + registered + destroyed`) — replace with an enum.
- A "comment promise" — `// safety: the caller must ensure this
  flag is true` next to an `unsafe { ... }` block. Promises don't
  compile.

**The workspace's one allowlisted use** is
`RootHelperRegistration::registered` in
`crates/ripdpi-proxy-runtime/src/runtime/listeners.rs`. It fits
shape #2 above: the struct is move-only (no `Copy`/`Clone` —
enforced by compile-fail `AmbiguousIfCopy`/`AmbiguousIfClone`
blocks), the field is private (default visibility), the
constructor `for_config` sets it deterministically from config,
and Drop branches on it for conditional cleanup. Runtime
regression tests cover sequential lifecycle, no-op drop on
unregistered guards, and the `mem::forget` leak documented
limitation.

## `UnsafeCell<T>` discipline

`UnsafeCell<T>` is the **only** way Rust allows mutation through a
shared reference (`&UnsafeCell<T>`). It is also the only primitive
that defeats the compiler's aliasing rules without an `unsafe`
block at the type level — the unsafety is moved to the
`unsafe { *cell.get() }` deref instead.

**Rule.** `UnsafeCell<T>` permits interior mutability **but does
not by itself make aliasing or threading sound.** Every `*cell.get()`
deref must be guarded by an exclusivity protocol that the type
system can enforce. The protocol must specify:

1. **The aliasing model.** Who is allowed to hold `&T` and `&mut T`
   simultaneously, and what makes simultaneous mutation impossible?
   Standard answers: move-only handle + free list (the
   `BufferHandle` design), `Mutex<T>`/`RwLock<T>` (locks),
   `Cell<T>`/`RefCell<T>` (single-threaded runtime check),
   atomics (lock-free primitive types).

2. **The synchronisation model.** When the cell is shared across
   threads, what supplies the release/acquire happens-before edge?
   Standard answers: `Mutex` unlock/lock, atomic operation, channel
   send/receive, thread spawn/join.

3. **The reentrancy behaviour.** If user-supplied code can re-enter
   the cell while a borrow is live, what prevents the second access
   from producing aliasing UB? Standard answer: don't expose
   user-supplied callbacks while a borrow is live; otherwise use
   `RefCell` (which panics on reentrancy) or restructure.

**Anti-patterns that the scanner + review reject.**

- A `pub struct` with a public `UnsafeCell<T>` field. The field
  must be private; the wrapper's API is the only valid access path.
- `unsafe impl Send for X {}` or `unsafe impl Sync for X {}` for a
  type whose `UnsafeCell<T>`'s contents are NOT protected by a
  release/acquire-class synchronisation primitive.
- A safe public method `fn get(&self) -> &mut T` (without `Mutex`-
  style guard wrapping) that derefs `*cell.get()`. The signature
  promises shared-to-exclusive without a runtime check; the type
  system can't see the exclusivity protocol and neither can
  callers.
- Returning the raw pointer from `cell.get()` to safe callers. The
  pointer is fine inside `unsafe { }`; surfacing it to safe code
  gives the caller a tool that bypasses the borrow check.

**Workspace inventory.** The only production `UnsafeCell` use is
`Box<[UnsafeCell<Box<[u8]>>]>` in `crates/ripdpi-io-uring/src/`
`bufpool.rs`. Its exclusivity protocol is documented in the next
section and exercised by runtime tests in `bufpool::tests`. The
scanner's `UnsafeCell::get` pattern (see "Custom scan" table) gates
any new occurrence through the allowlist with the three-model
template above.

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
