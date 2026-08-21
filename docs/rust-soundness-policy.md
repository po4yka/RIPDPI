# Rust Soundness Policy

> Status: enforced Owner: native Rust maintainers Enforced by:
> - `[workspace.lints]` in `native/rust/Cargo.toml`
> - `scripts/ci/check_unsafe_boundaries.py` + `ci/unsafe-boundary-allowlist.toml`
> - `scripts/ci/run-rust-lint.sh` (invoked by the `rust-lint` CI job)
> - `cargo doc --no-deps` with `-D warnings` (broken-intra-doc-links denied)
> - `cargo +nightly miri test` for unsafe-heavy crates (see `scripts/ci/run-rust-miri.sh`)

The soundness audits recorded in this policy showed that the most expensive bugs we have shipped were **safe APIs that smuggled unsafe contracts to their callers**. This policy exists so that "safe Rust" in this repo means what `unsafe` says it means in the language.

Inventory tables in this policy are audit snapshots, not permanent counts. The
current allowlists and `scripts/ci/check_*unsafe*` scanners are authoritative;
re-run them before relying on a count or site list during review.

## The rule

A `pub fn` / `pub(crate) fn` must not require its caller to uphold any memory-safety obligation in order for the function to be sound. Either the function is genuinely safe — invariants enforced by types, lifetimes, visibility, runtime checks, or RAII — or the function is `unsafe fn` with a `# Safety` section that documents every precondition.

"Sound" here means: there is no way to call the function from safe Rust in another crate (or another module, for `pub(crate)`) that would cause undefined behaviour, even if the caller is malicious.

## Mandatory invariants

1. **Safe APIs do not require hidden memory-safety obligations from callers.** If you find yourself writing "the caller must…" in a doc-comment for a `pub fn`, the function must be `unsafe fn` instead.

2. **Raw pointer dereferences are locally justified.** Every `unsafe` block dereferencing a raw pointer must carry a SAFETY comment that names the exact precondition (validity, alignment, initialization, aliasing) AND identifies who establishes it.

3. **Safe wrappers enforce invariants through one of:** - the type system (newtypes, `BorrowedFd<'_>`, `OwnedFd`, typestate); - lifetimes (returned references tied to a real owner); - module visibility (private constructors + non-`Copy` handles); - runtime validation (bounded indices, checked casts); - RAII (Drop performs the cleanup once and only once).

   `debug_assert!` does **not** count as enforcement: it is a no-op in release builds. Use `assert!`, `Result`, or type-level encoding when safety depends on the check.

4. **`unsafe impl Send`/`unsafe impl Sync`** must be paired with a written argument (in a SAFETY comment immediately above the impl) showing why the type is actually thread-safe — usually because every field is either `Send`/`Sync` or its access is gated by a synchronization primitive whose ownership transfer is the happens-before edge.

5. **Move-only ownership handles do not implement `Copy` or `Clone`** unless there is a real refcount or shared ownership behind them. Handles passed across an FFI boundary as integers must be funneled through a `HandleRegistry` (or equivalent) with private construction.

6. **Call-order protocols must be expressed as types, not as comments** ("create → register → use → unregister → destroy" becomes typestate; the compiler refuses to call `destroy` before `register`).

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

These coexist with two intentional `allow`s for FFI: `clippy::missing_safety_doc` and `clippy::not_unsafe_ptr_arg_deref`. Those remain `allow` because (a) JNI macros generate `unsafe fn`s whose documentation would be macro-injected and (b) raw-pointer JNI argument dereferences happen inside small `unsafe` blocks with their own SAFETY comments. The custom scan script in `scripts/ci/check_unsafe_boundaries.py` enforces the higher-level boundary that those allows would otherwise leak past.

## Custom scan

`scripts/ci/check_unsafe_boundaries.py` is run by `run-rust-lint.sh` on every PR. It looks for the following risky patterns under `native/rust/crates/*/src/**`:

| Pattern | Concern |
|---|---|
| `slice::from_raw_parts(_mut)?` | Synthesizing slices over raw memory. |
| `Box::from_raw`, `Vec::from_raw_parts`, `String::from_raw_parts` | Ownership reconstitution from a raw pointer. See "`Box::into_raw` / `Box::from_raw` ownership transfer" and "`Vec::from_raw_parts` ownership transfer" below. |
| `Vec::from_raw_parts_in` | Allocator-API variant of `Vec::from_raw_parts`. Same eight-point checklist plus the allocator-compatibility constraint must hold across the call. The base `Vec::from_raw_parts` `\b` regex anchor does NOT match the `_in` suffix because `_` is a word character, so this is a dedicated pattern. See "`Vec::from_raw_parts` ownership transfer" below. |
| `Box::into_raw` | The matched counterpart of `Box::from_raw`. Scanning only the reclaim side would miss orphaned `into_raw` calls that leak (`mem::forget` equivalent) or that hand the pointer to FFI without a matching `from_raw`. See "`Box::into_raw` / `Box::from_raw` ownership transfer" below. |
| `.assume_init()` / `.assume_init_ref()` / `.assume_init_mut()` / `.assume_init_drop()` / `.assume_init_read()` / `MaybeUninit::assume_init(_*)?` | Promoting `MaybeUninit<T>` to `T` (or `&T`/`&mut T`/Drop-target) without proof every byte of the slot is a valid `T` value. UB on the very next read otherwise. The previous regex matched only the base form because the `\b` anchor stopped at `_`; the broadened regex catches all five std-API variants. See "`MaybeUninit` correctness" below. |
| `mem::transmute` / `transmute::<_,_>` | Reinterpretation cast that bypasses the type system. |
| `mem::transmute_copy` | Cousin of `transmute` that does NOT enforce `size_of::<T>() == size_of::<U>()` at compile time; any size mismatch silently reads past the source allocation. The base `transmute` regex's `\b` anchor missed this; the dedicated pattern is the issue-#22 fix. See "Lifetime extension" below. |
| `Box::leak` / `Vec::leak` / `String::leak` | Promote a heap allocation to `&'static mut T` / `&'static mut [T]` / `&'static mut str`. Sound by language definition but the leaked memory is unreachable for the rest of the process lifetime. Workspace has zero production occurrences. See "Lifetime extension" below. |
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
| `union declaration` | `union T { ... }` declarations. The only Rust construct that lets safe code read bytes interpreted as one type when they were written as another. The validity invariants (size, alignment, padding, initialised bytes, target-type validity) must hold for every field, and only one variant is "live" at a time. Workspace has zero production occurrences. See "Type punning and layout reinterpretation" below. |
| `bytemuck::cast` | `bytemuck::cast`, `bytemuck::cast_ref`, `bytemuck::cast_mut`, `bytemuck::cast_slice`, `bytemuck::pod_read_unaligned`, `bytemuck::from_bytes`, and `try_*` variants. Sound only if the `Pod` / `Zeroable` trait bounds on every involved type are actually correct (no padding, no non-pod fields, `#[repr(C)]` layout). The workspace does NOT depend on bytemuck today; the pattern is forward-defense for future adoption. See "Type punning and layout reinterpretation" below. |
| `zerocopy::transmute`, `zerocopy::IntoBytes::as_bytes` | `zerocopy::transmute!`, `zerocopy::transmute_ref!`, `zerocopy::transmute_mut!`, `zerocopy::Ref::new[_unaligned]`, and the qualified-path `IntoBytes::as_bytes` / `as_mut_bytes`. Same trait-bound soundness chain as bytemuck (`FromBytes`/`IntoBytes`/`Unaligned`). The workspace does NOT depend on zerocopy today; forward-defense. The `IntoBytes::as_bytes` regex is the qualified-path spelling to avoid colliding with the ubiquitous bare-method `String::as_bytes` / `&str::as_bytes`. See "Type punning and layout reinterpretation" below. |
| `cast then deref (type pun)` (single-line proximity) | `.cast::<T>()` paired with a `&*` or `&mut *` deref on the same source line — the canonical type-pun spelling that materialises `&T`/`&mut T` from a pointer whose original pointee was a different type. The workspace has three production occurrences: `sockaddr_storage` → `sockaddr_in`/`sockaddr_in6` reinterpretation in `fd.rs` (family-tag-checked + compile-time alignment assert) and the BoringSSL `c_void` → `RealityCallbackState` callback round-trip in `reality_hook.rs` (provenance from `Box::into_raw`; Miri-validated). Any new occurrence must satisfy the five-point validity checklist in "Type punning and layout reinterpretation" below. |
| `repr packed` | `#[repr(packed)]`, `#[repr(packed(N))]`, `#[repr(C, packed)]`. Eliminates padding, producing underaligned field accesses — `&packed.field` materialises an underaligned reference (UB on ARM64 strict-alignment configurations). Workspace has zero occurrences. Any new use must restructure to `#[repr(C)]` with explicit padding fields or earn an allowlist entry naming the `addr_of!` + `ptr::read_unaligned` discipline. See "FFI layout and ABI" below. |
| `extern fn non-FFI type` | `extern "C" fn` / `extern "system" fn` DEFINITION signatures containing any Rust-only layout type: `bool` (implementation-defined at FFI level), `&str` / `String` / `Vec<T>` / `Box<dyn ...>` (fat pointers / Drop-bearing types), `&[T]` (fat pointer), `Option<T>` for `T` other than `NonNull`/`NonZero*`/references/function pointers (general Option layout is unspecified), `Result<T, E>` (Rust enum without `#[repr]`), `(T, U)` Rust tuples (no layout guarantee), `&dyn Trait` / `&mut dyn Trait` (trait-object fat pointer with vtable), `impl Trait` (compiler-internal opaque type), or `Fn` / `FnMut` / `FnOnce` (unsized closure trait types — must be carried as `Box<dyn Fn...>` or function pointer instead). The regex disambiguates extern fn DEFINITIONS (`extern "C" fn name(...)` — has identifier between `fn` and `(`) from extern fn POINTER TYPES (`handler: extern "C" fn(c_int)` — `fn` immediately followed by `(`) so a non-extern function that takes a callback parameter is not falsely flagged. Workspace has zero occurrences in extern blocks or definitions. The companion clippy lints `improper_ctypes` and `improper_ctypes_definitions` catch a superset (including non-`#[repr]` enums); this scanner pattern is the belt-and-suspenders cross-check that survives module-level `#[allow(improper_ctypes_definitions)]` waivers. See "FFI layout and ABI" below. |
| `no_mangle without extern ABI` (proximity ≤ 3 lines) | `#[no_mangle]` not paired with `extern "C"` / `extern "system"` / similar within 3 source lines below. A `#[no_mangle]` on a default-Rust-ABI function exports an unstable-ABI symbol under a fixed name — guaranteed ABI mismatch at every C/Java/other-language call site. Workspace has three correctly-paired occurrences (`reality_hook.rs:90/109/123` — all paired with `extern "C"`); the proximity check locks the workspace at that baseline. See "FFI layout and ABI" below. |
| `bindgen invocation` | `bindgen::Builder` / `bindgen::generate` / `cbindgen::Builder` / `cbindgen::generate` / `cbindgen::Config` / `cbindgen::Language` calls in build scripts or production code. Both crates generate FFI binding code that the rest of the workspace's `#[repr]` discipline cannot automatically verify. The workspace does NOT depend on either crate today; the pattern is forward-defense. Any future adoption must include either a checked-in snapshot of the generated bindings (committed for PR review) or a CI step that diffs generated output against a snapshot. See "FFI layout and ABI" below. |
| `raw back-pointer field` | A struct field whose name matches a back-pointer convention (`parent`, `owner`, `container`, `list`, `prev`, `next`, `head`, `tail`, `back`, `back_ptr`, `backptr`, `registry`) and whose type is a raw pointer (`*const T` / `*mut T`) or `NonNull<T>`. The pattern is the canonical intrusive parent/owner/container pointer — by definition it does NOT own the pointee, so any `Drop` impl that dereferences it has no Rust-level guarantee the pointee is still alive (or not partially dropped). Workspace has zero production occurrences: every Drop that notifies a parent uses a lifetime-bound `&'a Parent`, an `Arc<Parent>`, or a `Weak<Parent>`. See "Drop and raw back-pointers" below. |
| `MaybeUninit::write`, `ptr::write`, `addr_of_mut!` | Upstream-write sites of the partial-init class. `MaybeUninit::write` (qualified-path), bare `ptr::write` (NOT `_bytes`/`_volatile`/`_unaligned`), and `addr_of_mut!` are the canonical entry points for staged initialisation. If the surrounding function returns `Result` or can panic between the first write and the commit point (`set_len` / `assume_init` / struct literal), every already-written `T: Drop` value leaks. Workspace has zero production occurrences. See "Partial initialisation and panic safety" below. |

When the script flags a `(file, pattern)` pair it requires either a restructure (preferred) or an entry in `ci/unsafe-boundary-allowlist.toml`. Each allowlist entry must include:

- `file` — path relative to repo root.
- `pattern` — the exact key reported by the script.
- `reason` — one-line summary.
- `preconditions` — what the unsafe operation actually requires.
- `enforcement` — how the codebase guarantees the preconditions (type/lifetime/visibility/runtime/RAII or, in last-resort cases, human review).
- `owner` — the team or crate accountable for keeping the entry sound.
- `review_date` — ISO date for the next mandatory re-review.

**Adding a new entry is a code review red flag.** The reviewer should push back unless the contributor has explained why options (1) restructure and (2) `unsafe fn` were rejected.

## `Option<NonNull<T>>` ownership tokens

`Option<NonNull<T>>` is `Copy`. The value only represents "a nullable non-null raw pointer"; it does not prove ownership, uniqueness, liveness, valid lifetime, allocator provenance, initialization, or exclusive access. Used as a safe ownership / liveness / registration / exclusive- access handle, it lets safe callers duplicate the value and cause UAF, double-free, stale-handle dereference, or aliasing UB.

**Rule.** `Option<NonNull<T>>` must not be used as a safe ownership token.

Concretely:

1. Do not store `Option<NonNull<T>>` in a struct field that is treated as an owning slot. Wrap `NonNull<T>` in a private move-only newtype with no `Copy`/`Clone` and store `Option<OwnerHandle<T>>` instead:

   ```rust
   use core::marker::PhantomData;
   use core::ptr::NonNull;

   pub(crate) struct OwnerHandle<T> {
       ptr: NonNull<T>,
       _owned: PhantomData<Box<T>>,
   }
   // NB: no #[derive(Copy)] / #[derive(Clone)].
   ```

2. Do not accept `&mut Option<NonNull<T>>` as a public parameter to "extract" or "swap out" an ownership slot. Move the handle through `slot.take()` on a value of type `Option<OwnerHandle<T>>` instead.

3. Do not return `Option<NonNull<T>>` from a safe public function as a handle. Return `Option<&T>`, `Option<&mut T>`, or a private `OwnerHandle<T>` whose constructor is `pub(crate)` or `unsafe fn`.

4. If a `NonNull<T>` field has to remain (for example to carry a raw pointer through to `Drop`), it must be a **private** field on a non-`Copy`, non-`Clone` struct, and the struct itself becomes the ownership token. The two production examples are `crates/ripdpi-geo/src/mapped_file.rs` and `crates/ripdpi-privileged-ops/src/linux/mmap_region.rs`: each wraps a single `NonNull<u8>` in a non-`Copy` `struct` whose `Drop` calls `munmap` exactly once. Neither type exposes the `NonNull` to callers, so safe duplication is impossible.

5. `debug_assert!` does not enforce ownership. A `debug_assert!(slot .is_none())` guard around a destroy/free call is compiled out of release builds and protects no one.

6. Lifecycle transitions ("created → registered → used → destroyed") must be encoded as types or visibility, not as flags (`is_alive`, `destroyed`, `disowned`, `owned_by_*`). Prefer typestate or consuming methods (`fn destroy(self)`); the compiler refuses double-destroy because the value moves.

**Allowlist entry.** If you have a legitimate reason to keep `Option<NonNull<T>>` (e.g. a non-owning observation pointer used only as a fast `is_some` flag), add an entry to `ci/unsafe-boundary-allowlist.toml` whose `reason` and `enforcement` fields explicitly state:

- whether the value is owning or non-owning,
- who owns the underlying allocation,
- how liveness is guaranteed for every reachable dereference,
- why `Copy` duplication is harmless in this specific case,
- whether the pointer is ever passed to a `destroy`/`free` / `unregister` path (and if so, what makes that path single-shot).

`pattern = "Option<NonNull<T>>"` is the key used by the scanner.

**Why not trybuild compile-fail tests?** The repository policy (see "Compile-fail enforcement" below) is that the Rust type system *itself* serves as the compile-fail harness. A `pub struct OwnerHandle<T> { ptr: NonNull<T>, _owned: PhantomData<Box<T>> }` with private fields and no `Copy`/`Clone` derive is already a compile-fail for `let dup = *slot;` and `let dup = slot.clone();`. The scanner enforces *recognition* of the unsafe pattern; the type system enforces *correctness* of the safe replacement. Adding a `trybuild` harness for the same property would duplicate enforcement without adding signal.

## `debug_assert!` as memory-safety guard

`debug_assert!`, `debug_assert_eq!`, and `debug_assert_ne!` expand to no-ops in release builds unless the build was configured with debug assertions enabled. If unsafe code relies on a `debug_assert!` to exclude invalid pointers, bad lengths, uninitialized memory, duplicate ownership, invalid state-machine transitions, or aliasing violations, the release build will execute that unsafe code with the precondition unenforced — undefined behaviour.

**Rule.** `debug_assert*!` must never be the *only* guard before an unsafe operation. This restates Mandatory Invariant #3 above and is enforced by the `debug_assert near unsafe` scan rule (debug-only assertion within ±10 source lines of an `unsafe` keyword, after comment stripping).

Concrete obligations:

1. **Safety preconditions are release-mode checks.** Replace `debug_assert!(cond);` with one of: - `assert!(cond, …)` if a panic is an acceptable safety boundary and the cost is acceptable; - `if !cond { return Err(…); }` if the caller is part of a fallible API and can recover; - a type or visibility change that makes the invalid state unrepresentable from safe code (preferred).

2. **Inputs from safe code are validated at the boundary, not inside an `unsafe` block.** A `pub fn` that calls `unsafe { … }` must either: - reject invalid inputs in safe code *before* the unsafe operation (`Result`, `Option`, `assert!`), or - be `unsafe fn` with a `# Safety` section that names every precondition. `debug_assert!(valid)` followed by `unsafe { do_thing() }` is not an acceptable pattern in either case.

3. **`debug_assert!` is still useful for diagnostic-only checks.** When the failure of the asserted condition produces incorrect-but- safe behaviour (a stale cache entry, a wrong telemetry tag, a logical inconsistency in non-`unsafe` code), `debug_assert!` is the right tool. The three production occurrences in this workspace — two in `crates/ripdpi-tunnel-core/src/dns_cache/state.rs` and one in `crates/ripdpi-monitor-engine/src/execution/lanes/https/` `sample_builder/sample_result.rs` — are all of this kind: the first pair is fronted by a release-mode `NonZeroUsize::new(max)` `.expect(…)`, and the third is a string-tag sanity check on telemetry input that can't reach unsafe code.

4. **Lifecycle flags are not safety guards.** Boolean flags such as `is_alive`, `destroyed`, `initialized`, `registered`, or `disowned`, combined with `debug_assert!(self.is_alive)`, are classic recipes for release-mode UAF. The fix is typestate (`fn destroy(self)` consumes the handle), RAII (`Drop` runs at most once because of move semantics), or `Option<OwnerHandle<T>>` (see "Option<NonNull<T>> ownership tokens").

5. **`debug_assert_with_mut_call` divergence.** `debug_assert!( self.try_mutate())` calls `try_mutate` in debug builds and silently skips it in release. This is a common subtle bug. Either remove the mutation or move it outside the assertion. We do not enable `clippy::debug_assert_with_mut_call` as a deny lint today because the workspace has no current occurrences and the lint is a nursery-tier lint with churn risk; the policy here is the enforcement of record.

**Why a proximity-based scan.** A precise lexical scan ("`debug_assert` inside the same `unsafe { … }` block") would need AST-level analysis. The proximity heuristic is a cheap upper bound that catches the typical shapes — `debug_assert!(cond); unsafe { … }`, `unsafe fn f() { debug_assert!(cond); … }`, and the inverse — without dragging a Rust parser into the CI scripts. New legitimate uses (a `debug_assert!` near an `unsafe impl Send` block that is unrelated to the assertion) go through the allowlist; the `reason` and `enforcement` fields must explain why the release-mode behaviour is sound.

**Allowlist entry requirements.** A `pattern = "debug_assert near unsafe"` entry in `ci/unsafe-boundary-allowlist.toml` must state:

- which invariant the assertion documents,
- what actually enforces that invariant in release builds (type, RAII, separate release-mode `assert!`, FFI caller contract, …),
- why release-mode failure of the asserted condition cannot promote to UB,
- the symbol (function/method) whose body contains the assertion,
- an owner and a review date as for every other allowlist entry.

## Creating `&T` from raw pointers

Creating a Rust shared reference `&T` (or `&[T]`, `&str`, `&CStr`) from a raw pointer is **not** the same as reading a byte through the pointer. The reference is required to be:

- non-null and properly aligned for `T`,
- pointing into an allocation of at least `size_of::<T>()` bytes (or `len * size_of::<T>()` for `&[T]`),
- pointing to a fully initialised value of `T`,
- live for the entire returned lifetime — no `Drop` of the owner can run while the reference is held,
- not concurrently mutated through any other path — Rust's aliasing rules forbid even an unread write through an aliased `*mut T` while a `&T` exists.

If any of these is violated for **even one** byte, the program has UB, regardless of whether the bad bytes are observed at runtime.

**Rule.** A safe public function must not turn a raw pointer or `NonNull<T>` into a `&T`/`&[T]`/`&str`/`&CStr` unless every invariant above is enforced by the function's own preconditions — types, lifetimes, visibility, runtime validation, or RAII. If the caller has to uphold any pointer-validity obligation, the function must be `unsafe fn` with a `# Safety` section.

The repository already enforces this through the following scan patterns (see "Custom scan" table above): `slice::from_raw_parts`, `NonNull::as_ref/as_mut`, `CStr::from_ptr`, `str::from_utf8_unchecked`, `raw pointer in public fn`, `NonNull in public fn`. Any new occurrence of one of these patterns either restructures away the raw pointer, becomes `unsafe fn`, or earns an allowlist entry whose `preconditions` and `enforcement` fields make the validity argument concrete.

**Preferred shapes.** In order of preference:

1. **No raw pointer at the API.** Accept `&[u8]` / `&str` / a borrowed handle. Return owned values (`Vec<u8>`, `String`) or references bound to a real owner lifetime (`fn get(&self) -> &T`, `fn slice(&self) -> &[u8]`). This is the shape used by `MappedFile::as_slice(&self) -> &[u8]` and `MmapRegion::as_ptr(&self) -> *const u8` — the former returns a reference whose lifetime is `&self`, the latter returns the raw pointer only for FFI handoff and never materialises a Rust reference from it.

2. **Validate, then convert.** At an FFI boundary, branch on null / length / encoding / alignment before producing the reference. `str::from_utf8` (release-mode validated) is preferred over `str::from_utf8_unchecked` even if the input is "known" valid; the cost is negligible and the safety surface shrinks.

3. **`unsafe fn` + `# Safety`.** When step 1 and step 2 are not possible (genuine FFI shims, low-level kernel helpers), the function becomes `unsafe fn` and documents every precondition. The caller must enter `unsafe { … }` with their own SAFETY comment.

**Anti-patterns.**

- A safe `pub fn` whose body contains `unsafe { std::slice:: from_raw_parts(ptr, len) }` for a `ptr` and `len` derived from parameters with no internal validation. The function must either validate (option 2) or be `unsafe fn` (option 3).
- A `fn get<'a>(&self) -> &'a T` with an unconstrained `'a` — the caller can extend `'a` to `'static` and outlive `&self`. The correct signature is `fn get(&self) -> &T` (sugar for `fn get<'a> (&'a self) -> &'a T`), tying the returned reference to the owner.
- `debug_assert!(!ptr.is_null()); unsafe { &*ptr }` — covered by the proximity rule above. The null check must be release-mode.
- `let s = unsafe { str::from_utf8_unchecked(bytes) };` where `bytes` came from an external source. Either validate or accept the `Result` from `str::from_utf8`.

**Existing benign uses.** The audit recorded four raw-pointer → reference sites; each is allowlisted with the validity argument:

| File | Conversion | Validity source |
|---|---|---|
| `crates/ripdpi-geo/src/mapped_file.rs` | `slice::from_raw_parts` → `&[u8]` | RAII `MappedFile` owns the mmap; slice borrows `&self`. |
| `crates/ripdpi-privileged-ops/.../icmp_wrapped_udp.rs` | `slice::from_raw_parts` → `&[u8]` | `recv_from` contract initialises the first `received` bytes of a stack `MaybeUninit` buffer; slice is consumed in-scope. |
| `crates/ripdpi-desync-runtime/src/platform/registry.rs` | `&*pointer` → `&dyn TcpDesyncPlatform` | RAII `Restore` guard scoped to a closure; non-owning observer. |
| `crates/ripdpi-io-uring/src/probe.rs` | `CStr::from_ptr` → `&CStr` | POSIX `uname(2)` NUL-termination contract; lifetime bounded by the local `utsname`. |

## `Clone` on owner-named types

Types whose names end in `Handle`, `Owner`, `Guard`, `Token`, `Resource`, `Registration`, or `Slot` advertise ownership of a resource. `Clone` on such a type MUST mean exactly one of:

1. **Independent safe duplicate** — the inner data is copy-trivial (plain integers, `&'static str`, function pointers, `Copy`able IDs). Cloning produces a new value that owns nothing the original owned because there is nothing to own. Example: `StrategyDescriptorRegistration { id: &'static str, describe: fn() -> StrategyDescriptor }`.
2. **Refcounted shared owner** — the type is a newtype around `Arc<T>` / `Rc<T>` (or holds one as its sole resource-bearing field). Cloning delegates to `Arc::clone` / `Rc::clone`, which the standard library implements soundly. Example: `ServicesStateHandle(pub(crate) Arc<ServicesState>)`.

`Clone` MUST NOT mean:

- "Duplicate a raw pointer". The original's `Drop` will free the resource; the duplicate then dangles. Use `Arc<T>` if sharing is intended.
- "Duplicate a `RawFd`". The first `Drop` closes the fd; the second sees a stale or recycled descriptor.
- "Duplicate an FFI handle". Same problem as RawFd, plus the foreign library may assert single-ownership.
- "Duplicate an exclusive-access registration". The registry silently has two entries for the same key; cleanup is racy.

**Rule.** A `#[derive(Clone)]` on an owner-named struct must have either (a) only `Copy`-trivial fields, or (b) `Arc<T>` / `Rc<T>` as the sole resource-bearing field. Anything else requires either removing the Clone (the type becomes move-only) or providing a named `try_clone(&self) -> Result<Self, _>` method whose body documents the duplication semantics — `File::try_clone(&self) -> io::Result<File>` is the std model.

**Allowlist entry requirements.** A `derive Clone on owner-named type` allowlist entry's `enforcement` field MUST state:

- which of the two sound semantics the type uses (copy-trivial metadata or `Arc`/`Rc`-backed shared ownership),
- the specific field that bears the resource (and that it is `Copy`-trivial or `Arc<T>`),
- why a Clone of the outer struct does not duplicate any underlying allocation, file descriptor, registration, or other exclusive resource.

**Workspace inventory.** Three allowlisted occurrences:

| File | Type | Semantics |
|---|---|---|
| `ripdpi-strategy-trait/src/lib.rs` | `StrategyDescriptorRegistration` | Copy-trivial metadata: `&'static str` + function pointer; owns nothing. |
| `ripdpi-proxy-runtime-adapter/src/model/services.rs` | `ReprobeResetHandle` | Arc-backed (wraps `ServicesStateHandle` which wraps `Arc<ServicesState>`). |
| `ripdpi-runtime-services/src/lib.rs` | `ServicesStateHandle` | Arc-backed newtype: `pub(crate) Arc<ServicesState>`. |

The load-bearing move-only owner handles (`BufferHandle`, `PendingBuffer`, `RootHelperRegistration`, `MmapRegion`, `MappedFile`, `RegisteredBufferPool`, `JniProtectCallback × 2`, `OwnedRxToken`, `OwnedTxToken`) correctly do NOT derive `Clone`, and the canonical owner handles (`BufferHandle`, `PendingBuffer`, `RootHelperRegistration`) carry explicit compile-fail `AmbiguousIfCopy`/`AmbiguousIfClone` const blocks that fail to compile if a future change ever derives `Clone`.

## `Copy` on owner-named types

`Copy` is strictly stronger than `Clone`: a `Copy` value is duplicated implicitly on every move, every function call by-value, every pattern bind by-value, and every assignment. There is no explicit `.clone()` call site at which a reviewer could intercept the duplication. An owner-named type that is `Copy` therefore cannot encode any ownership of any resource — by the time the call stack unwinds, every parameter pass and every `let` binding has silently produced another bitwise duplicate of the supposed owner.

The only sound `Copy` semantics on a type whose name ends in `Handle`, `Owner`, `Guard`, `Token`, `Resource`, `Registration`, or `Slot` is:

- **Copy-trivial metadata that owns nothing.** Every field is itself `Copy` and aliases something that is intrinsically duplicable: `&'static str` (rodata reference), function pointer (code address), plain integer (numeric value), `Copy`-only id newtype. No allocation, no file descriptor, no kernel resource, no FFI handle, no arena index whose validity depends on the surrounding arena's lifetime, no `Drop` impl. The canonical example is `StrategyDescriptorRegistration { id: &'static str, describe: fn() -> StrategyDescriptor }` — a `linkme::distributed_slice` entry that exists only to register a strategy family at link time.

`Copy` MUST NOT mean any of the following on an owner-named type:

- "Duplicate a raw pointer / `NonNull`". Every move duplicates the pointer; whichever copy drops first runs the cleanup, and every other copy then dangles. (See "Option<NonNull<T>> ownership tokens" above for the same failure mode at the `Option` level.)
- "Duplicate a `RawFd` / `OwnedFd` / file descriptor". Closing the fd on first drop leaves all other copies referring to a stale or recycled descriptor.
- "Duplicate an FFI handle / `JavaVM` / `Global<JObject>` / `*mut FFI_T`". The foreign library has no idea Rust has silently produced more handles; double-free or use-after-free on the foreign side is the typical result.
- "Duplicate an arena index whose validity depends on the arena". A `Copy` `BufferIndex(u16)` looks innocent until the free-list hands the same index to two callers, at which point the type system can no longer enforce exclusivity.
- "Duplicate a `Drop`-bearing handle". `Copy` + `Drop` is rejected by the compiler outright — Rust enforces this part of the rule itself. The scanner catches the failure mode immediately upstream: a future contributor who adds `#[derive(Copy)]` to a `Drop`-bearing handle gets a CI failure before the compile error.

**Rule.** A `#[derive(Copy)]` on an owner-named struct must demonstrate the Copy-trivial-metadata property: every field is inherently `Copy` and aliases something whose duplication is free of ownership. Anything else either removes the `Copy` derive (the type becomes move-only — the default and preferred shape) or restructures into a metadata wrapper plus a separate `!Copy` owner handle.

**Allowlist entry requirements.** A `derive Copy on owner-named type` allowlist entry's `enforcement` field MUST state:

- that every field is inherently `Copy` and what each field aliases (rodata, code address, numeric value, `Copy`-only id),
- that the struct holds no allocation, file descriptor, raw pointer, kernel resource, or arena index whose validity is bounded by an enclosing object,
- that no `Drop` impl exists and that none could be sensibly added (the type is pure metadata).

**Workspace inventory.** Exactly one allowlisted occurrence:

| File | Type | Semantics |
|---|---|---|
| `ripdpi-strategy-trait/src/lib.rs` | `StrategyDescriptorRegistration` | Copy-trivial metadata: `&'static str` + function pointer; owns nothing; no `Drop`. |

**Compile-fail enforcement.** The load-bearing move-only owner handles are `!Copy` and carry explicit `AmbiguousIfCopy` const blocks that fail the workspace build if a future change ever derives `Copy`. The current explicit coverage is:

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

Every load-bearing move-only owner handle in the workspace now carries an explicit `AmbiguousIfCopy` block. For the four JNI/smoltcp shims the contained types (`Global<JObject>`, `Vec<u8>`, `&mut VecDeque<_>`) are themselves `!Copy`, so the compiler already rejects any future `derive(Copy)`; the explicit block pins the soundness argument adjacent to the type declaration so the next reviewer can see it without crossing crate boundaries.

## Use `Arc<T>` / `Rc<T>` / `Weak<T>`, not manual refcounting

Shared ownership in this workspace MUST use the standard library's reference-counting types: `std::sync::Arc<T>` for cross-thread sharing, `std::rc::Rc<T>` for single-threaded sharing, and `std::sync::Weak<T>` or `std::rc::Weak<T>` for observer pointers that must not extend the lifetime of the value. The standard library already handles every soundness requirement the issue-#12 audit names:

| Concern | How std solves it |
|---|---|
| Overflow | `Arc::clone` panics on overflow (above `isize::MAX/2`). |
| Atomic ordering | `Arc::clone` uses `Relaxed` for increment (the count is monotonic between clone and drop), `Release` for decrement, and `Acquire` for the last-drop fence. This is the canonical sound sequence. |
| Clone/drop balance | Auto-derived `Clone` + std-provided `Drop` are guaranteed paired by Rust move semantics. |
| Reentrancy | `Arc::drop` only deallocates at refcount zero; no callback into user code during the decrement. |
| Panic paths | `Arc::drop` is panic-safe; the destructor never reads through the pointer after the last decrement. |
| `Send`/`Sync` | `Arc<T>: Send + Sync` when `T: Send + Sync`, enforced by blanket impl. |
| Object reclamation | The last `Drop` calls the inner `T`'s destructor under an `Acquire` fence. |

**Rule.** Application code MUST NOT call any of the manual-lifecycle methods on `Arc`/`Rc`/`Weak`:

- `Arc::into_raw` / `Arc::from_raw`
- `Arc::increment_strong_count` / `Arc::decrement_strong_count`
- `Rc::into_raw` / `Rc::from_raw`
- `Weak::into_raw` / `Weak::from_raw`

These exist for `unsafe` library authors implementing custom smart pointers; calling them in safe-feeling application code re-creates the bugs `Arc` was designed to prevent. The scanner pattern `manual Arc/Rc refcount` enforces this rule with zero baseline.

**Allowlist entry requirements (manual Arc/Rc raw round-trip).** If a genuine FFI shim must pass an `Arc` through a C boundary (e.g. an opaque pointer registered with a foreign library), the allowlist entry MUST state:

- which boundary requires the raw pointer,
- which symbol is paired with `into_raw` (every `into_raw` MUST be matched by exactly one `from_raw`),
- how the call-site discipline prevents leaks (no `into_raw` without a registered cleanup callback that consumes via `from_raw`),
- thread-safety: whether the foreign code may share or send the raw pointer, and how the `Arc`'s `Send + Sync` guarantees survive the boundary.

**Allowlist entry requirements (intrusive `AtomicUsize` refcount).** If a hand-rolled refcount survives review (intrusive linked list node, embedded-target where `Arc` is too large, etc.), the allowlist entry's `enforcement` field MUST document all five of:

1. **Ownership model** — which type owns the allocation, when it reclaims, and what handle shape is exposed to callers (must be non-`Copy`, with `Clone` and `Drop` implemented in lockstep).
2. **Atomic ordering proof** — every operation on the counter must name its ordering: `Relaxed` for clone (monotonic increment), `Release` for drop (publish writes before decrement), `Acquire` on the last-drop fence (synchronise with prior `Release`-stores from other dropping threads). The proof must cite the exact happens-before chain.
3. **Overflow policy** — the counter must `abort` or `panic` on overflow before it wraps (`Arc` does this by aborting above `isize::MAX/2`). A silently-wrapping counter is a double-free waiting to happen.
4. **Reclamation policy** — what runs at refcount zero, in what order, and what synchronises the destructor with the last `Release` decrement (typically an `Acquire` fence inside Drop).
5. **Owner** — the team or crate accountable for re-reviewing the design on schedule.

Required regression tests for every custom-refcount allowlist:

- Clone/drop balance under sequential calls (no leak, no double-free).
- Clone/drop balance under multi-threaded contention (loom or thread-spawn test).
- Reentrancy: cloning inside the inner `T`'s destructor is either forbidden by API design or proven sound.
- Compile-fail: the handle is not `Copy` (use `AmbiguousIfCopy` trick) and not `Clone` unless the `Clone` impl maintains the refcount invariant.
- Miri run on a single-threaded clone/drop sequence to catch obvious provenance/UB issues.

**Anti-patterns reviewers reject.**

- `Arc::into_raw` followed by `mem::forget(arc)` — both increment the refcount and forget the original `Arc`, leaking the value.
- A custom `struct ManualRefcount { count: AtomicUsize, data: T }` with hand-rolled `inc`/`dec` methods. Replace with `Arc<T>`.
- `unsafe { Arc::from_raw(ptr) }` without a matching prior `Arc::into_raw(arc)` from the SAME `Arc` allocation. Producing the pointer any other way (cast from a `&T`, `Box::into_raw`, pointer arithmetic) is UB.
- A "manual `Weak`" using `Arc::downgrade` + a side channel that stores raw pointers. Use `Weak<T>` directly; the std API already handles upgrade race conditions.

**Workspace inventory.** Zero manual-refcount sites in production. All shared ownership uses `Arc<T>` with the standard derive Clone or explicit `Arc::clone(&...)` calls. Pool-style "release(index)" methods that the initial grep flagged (e.g. `BufferHandle::release`, `VirtualPortPool::release`) are **index-based ownership transfer** into a `Mutex<Vec<u16>>` free list, not refcounting; they were audited under soundness issues #1, #2, #7, #8, #9, #10 and remain sound by the move-only handle + mutex protocol.

## `Box::into_raw` / `Box::from_raw` ownership transfer

A `Box::into_raw` / `Box::from_raw` pair encodes a manual ownership transfer that the type system cannot check end-to-end: Rust hands a heap allocation to non-Rust code (FFI, a registry, a callback closure) and trusts that the same allocation comes back exactly once for reclamation. Every occurrence has to pass the issue-#15 audit checklist before it can ship:

1. **Same `T` on both sides.** The pointer's runtime type must match the type used in `Box::from_raw::<T>(...)`. A `Box::into_raw(Box::<Foo>::new(..))` followed by `Box::from_raw(ptr as *mut Bar)` is UB even if `Foo` and `Bar` have the same layout.
2. **Same allocator.** Both ends of the round-trip must use the same allocator. The workspace uses only the default global allocator (no `#[global_allocator]` switch, no `Box::new_in` call sites), so this is satisfied by default — but a future custom allocator would invalidate every existing pair.
3. **Correct alignment.** `Box::from_raw` assumes the pointer meets `mem::align_of::<T>()`. Always true if the pointer came from `Box::into_raw` and was never offset; UB if it came from `libc::malloc` (which only guarantees `MAX_ALIGN` in C, not `align_of::<T>()` for `T` with alignment > 16).
4. **Allocation start, not interior.** The pointer must address the start of the allocation. Offsetting (e.g. `ptr.add(1)`) between `into_raw` and `from_raw` is UB.
5. **Not already freed.** Each `Box::into_raw` is matched by **exactly one** `Box::from_raw`. Zero matchings is a memory leak; two or more is double-free / UAF.
6. **Exactly one owner.** While the raw pointer is in flight, there is exactly one entity entitled to call `Box::from_raw` on it. Multiple entities → race for the reclaim; safe Rust re-borrow of the pointer while `Box::from_raw` runs → UAF.

**Rule.** Application code SHOULD NOT use `Box::into_raw` / `Box::from_raw` directly. The preferred shapes, in order:

1. **A typed RAII wrapper** — the `soundness-canaries/src/lib.rs` `ScopedHandle<T, F: FreeFunction<T>>` is the workspace's general-purpose shape for any refcount- or malloc-managed FFI handle. Construct from an `unsafe fn from_ptr(*mut T) -> Option<Self>`; the `Drop` impl calls `F::free` exactly once. Tests in the same module assert "frees exactly once on drop", "panic-unwind still frees", "null rejected", and "two handles freed independently".
2. **An explicit free callback registered with the FFI.** If the C side has a destruction hook, register it and let the foreign code free the Rust-owned allocation — keeping the allocator boundary one-sided.
3. **`unsafe fn` install + RAII guard reclaim.** Used by `ripdpi-vless/src/reality_hook.rs`: `install_reality_client_hello_hook` (`unsafe fn`, `pub(crate)`) leaks one `Box<RealityCallbackState>` via `Box::into_raw` into BoringSSL's `SSL_CTX_set_client_hello_cb` `arg` slot. The returned `RealityHookGuard` is move-only (`!Copy + !Clone`); its `Drop` impl is the unique site that calls `Box::from_raw`, after checking `state_ptr` is non- null (defence in depth — Rust cannot actually drop the same value twice). The module-level doc-comment enforces the "guard outlives the SSL object" contract that the type system cannot express on its own.

**Anti-patterns.**

- A safe `pub fn` whose body contains a bare `Box::into_raw` and hands the pointer to a foreign API without a matching `unsafe fn ..._free(*mut T)` or RAII guard exposed by the same module. The function must either be `unsafe fn` with a documented `# Safety` contract OR ship the matching reclaim API in the same module.
- A `from_raw` whose matching `into_raw` is in a different crate. The allowlist entry's `enforcement` field must name both sites; if they cross a crate boundary, the upstream crate must also publish the typed wrapper so the boundary is one-sided.
- `mem::forget(boxed)` as a substitute for `Box::into_raw`. Both forms leak the allocation; only `Box::into_raw` returns a pointer that can be reclaimed. Using `mem::forget` to "leak intentionally" then later trying to `Box::from_raw` on an external pointer is UB.

**Audited production shape.** The Reality callback is the production
`Box::into_raw` / `Box::from_raw` ownership transfer. Test-only occurrences vary
as regression coverage evolves and must be obtained from the current scanner.

| File | Production `into_raw` | Matching `from_raw` | Test pairs |
|---|---|---|---|
| `ripdpi-vless/src/reality_hook.rs` | `install_reality_client_hello_hook` | `Drop for RealityHookGuard` | covered by current unit and Miri tests |

**Miri validation.** `cargo +nightly miri test -p ripdpi-vless reality_hook::tests` runs the four reality-hook unit tests under Miri, including `guard_reclaims_box_on_drop`. All four pass: Miri detects no double-free, no use-after-free, and no aliasing violation along the Drop path.

**Allowlist entry requirements.** A `Box::into_raw` or `Box::from_raw` allowlist entry's `enforcement` field MUST state all five of these mandatory fields:

1. **Allocation origin.** Where in the Rust source the matching `Box::new(...)` runs (file:function). The reviewer must be able to follow the chain `Box::new -> Box::into_raw -> ... -> Box::from_raw` without leaving the policy entry.
2. **Type `T`.** The concrete type whose `Box<T>` is being transferred. The reviewer must verify the same `T` appears on both sides — a layout-compatible-but-distinct `T'` would be UB.
3. **Allocator.** Default global allocator unless the entry names a custom `Allocator` (e.g. `Box::<T, MyAlloc>::new_in(...)`). The workspace uses only the default global allocator today; any future `#[global_allocator]` or `Box::new_in` call site invalidates every existing pair and requires re-audit.
4. **Ownership transfer path.** Which entity (struct field, FFI slot, registry index, closure capture) holds the raw pointer between `into_raw` and `from_raw`, and why that entity is `!Copy + !Clone` so the pointer cannot be duplicated while in flight.
5. **Deallocation proof.** The single site that calls `Box::from_raw`, and the structural reason it is reached exactly once: RAII `Drop` impl on a move-only guard, type-state transition that consumes the holder, FFI-side destructor callback registered in the same commit, or equivalent. The proof must explain why a second `Box::from_raw` on the same pointer cannot occur (Rust's move semantics + the `!Copy + !Clone` of the holding type are usually sufficient; if not, what other discipline supplies the missing guarantee).

### FFI ownership shapes

When the matched `from_raw` is itself called from a non- Rust context (the most common reason to reach for `Box::into_raw`), the boundary MUST take one of these shapes:

**Shape A — paired `rust_alloc` / `rust_free` exports.** The crate exposes two `extern "C" fn`s: `rust_alloc_FOO() -> *mut FOO` performs `Box::into_raw(Box::new(...))`, and `rust_free_FOO(ptr: *mut FOO)` performs `Box::from_raw(ptr)` after asserting non-null. The foreign code is contractually required to call exactly one `rust_free_FOO` for every `rust_alloc_FOO`. The pair lives in the same module so a reviewer can match the two without crossing files. Use this shape when the foreign code manages the lifetime explicitly and Rust has no say in when reclamation happens.

**Shape B — keep ownership on one side.** Rust hands the foreign side a borrowed `&T` or `&mut T` (cast to `*mut T` only for the duration of the call) and the foreign side never retains the pointer past the call. No `Box::into_raw` is needed. Use this shape when the foreign API takes the pointer only for read-back (e.g. `SSL_set_session`-style "give us your data, we copy it").

**Shape C — `unsafe fn` install + RAII guard reclaim.** Rust leaks one Box into a foreign slot via `Box::into_raw` and immediately returns an `unsafe`-constructed RAII guard that owns the reclaim side. The guard's `Drop` impl calls `Box::from_raw` and nulls the holder field. The install function is `unsafe fn` because the caller must uphold the "guard outlives the foreign reference" contract that the type system cannot express. Use this shape when the foreign API has no destructor callback and the install function is the natural moment to bind a Rust lifetime to the registration. This is the shape used by `install_reality_client_hello_hook` / `Drop for RealityHookGuard`.

Mixing the shapes (e.g. `rust_alloc_FOO` paired with a RAII guard on the Rust side) is permitted only if the guard's `take()` method releases ownership back to the foreign code by returning the raw pointer and `mem::forget`-ing the guard so its Drop does not fire. The `ScopedHandle::take()` method in `soundness-canaries/src/lib.rs` is the canonical implementation of that escape hatch.

## `Vec::from_raw_parts` ownership transfer

`Vec::from_raw_parts(ptr, len, cap)` and its allocator-API counterpart `Vec::from_raw_parts_in(ptr, len, cap, alloc)` reconstitute a `Vec<T>` from three (or four) raw values. The resulting `Vec` runs its destructor on drop, which deallocates the buffer using `dealloc(ptr, Layout::array::<T>(cap)?)` on whichever allocator was supplied. Every soundness precondition must hold — even a single mismatched field is UB.

The eight-point audit checklist (issue #16):

1. **Allocation origin.** `ptr` must come from a Rust allocation produced by a `Vec<T>` (or `String`, for the `String` variant) on the same allocator. A pointer from `libc::malloc`, `boxed slice`, `Box<[T]>` after `Box::into_raw`, an mmap region, or a foreign allocator is UB even if alignment and size happen to match.
2. **Element type `T`.** The pointer must address a buffer that was allocated for exactly this `T`. A layout-compatible-but-distinct `T'` (e.g. `repr(C)` mirror structs) is UB.
3. **Alignment.** The pointer must satisfy `mem::align_of::<T>()` — automatic if it came from a `Vec<T>::into_raw_parts`; not automatic if it came from `libc::malloc` (only `MAX_ALIGN` guaranteed in C) or from a `Box<[u8]>` cast to `*mut T` (alignment of `u8` is 1).
4. **Initialized length.** Bytes `[0, len * size_of::<T>())` must contain valid `T` values. `set_len`-style "leave it uninitialized and overwrite later" is UB on any read between `from_raw_parts` and the overwrite — including the `Drop` impl of any element type that runs destructors.
5. **Capacity.** Bytes `[0, cap * size_of::<T>())` must be the exact allocation size the allocator was told about. Passing a larger `cap` than the original allocation over-reads on drop; smaller leaks the tail.
6. **Allocator compatibility.** For `Vec::from_raw_parts_in`, the supplied `Allocator` MUST be the same instance (or interchangeable instance) that allocated the buffer. Workspace policy: only the default global allocator is in use; any future `#[global_allocator]` or per-Vec `Allocator` instance invalidates every existing pair and requires re-audit.
7. **`len <= cap`.** Required by the `Vec` invariant. A `from_raw_parts(p, 8, 4)` violates this immediately and is UB on the next `Vec` operation.
8. **Unique ownership.** Between `Vec::from_raw_parts` and the resulting `Vec` being moved or dropped, no other code may hold a `&[T]`, `&mut [T]`, second `Vec<T>`, or raw `*mut T` to the same buffer. The reconstituted `Vec` owns the allocation exclusively; an aliased view is UB on the very next mutation.

**Rule.** Application code SHOULD NOT use `Vec::from_raw_parts(_in)?`. The preferred shapes, in order:

1. **Safe `Vec` ownership.** Pass `Vec<T>` by value across internal APIs; accept `&[T]` or `&mut [T]` from FFI callers and `Vec::from(slice)` or `.to_vec()` if you need to own. Lets the type system prove every checklist point trivially.
2. **`Vec::with_capacity` + `spare_capacity_mut` + `set_len`.** When initialising a buffer in-place from a `recv`/`read`/foreign-fill call, allocate with `Vec::with_capacity(N)`, pass `spare_capacity_mut()` (returns `&mut [MaybeUninit<T>]`), then assert `set_len(n)` for the actually-initialised prefix `n`. The `Vec` was always Rust-owned; only the "initialised-up-to" cursor changed. This is the std- library-blessed equivalent of `from_raw_parts` for the common "Rust allocates, foreign code writes" pattern.
3. **A typed buffer wrapper.** When the buffer's lifecycle is more complex than a single `recv` (e.g. io_uring `IORING_REGISTER_BUFFERS`, page-aligned ring buffers, `MAP_PRIVATE` mmap), wrap the allocation in an owner type whose API is `&[u8] / &mut [u8]` and whose `Drop` handles the matching cleanup. The workspace has two reference implementations: `BufferHandle` in `ripdpi-io-uring/src/bufpool.rs` (move-only handle into a `Box<[UnsafeCell<Box<[u8]>>]>` pool) and `MappedFile` in `ripdpi-geo/src/mapped_file.rs` (mmap-backed read-only `&[u8]`).
4. **`unsafe fn` boundary + caller contract.** Only when the buffer genuinely originates from a foreign allocator and Rust must take ownership. The function becomes `unsafe fn` with a `# Safety` section that enumerates all eight checklist points; the caller enters `unsafe { … }` with their own SAFETY comment per the documentation contract above. The workspace has zero functions of this shape today.

**Anti-patterns.**

- `Vec::from_raw_parts(libc::malloc(n) as *mut T, n / size_of::<T>(), n / size_of::<T>())` — allocator mismatch (UB on drop), and alignment is unspecified. Use `Vec::with_capacity` instead and have the C code fill the Rust-allocated buffer.
- `let mut v = Vec::with_capacity(N); recv(v.as_mut_ptr(), N); unsafe { v.set_len(N); }` — bypasses `spare_capacity_mut`'s `MaybeUninit` typing and is hard to audit. The correct shape is `recv(v.spare_capacity_mut().as_mut_ptr() as *mut u8, N); unsafe { v.set_len(N); }` — the `set_len` line is still `unsafe`, but the SAFETY comment can reference the initialisation contract of `recv` instead of hand-waving about the buffer.
- `String::from_raw_parts(ptr, len, cap)` where bytes are not validated UTF-8. `String` carries the UTF-8 invariant; reconstituting from raw without validating is UB on any subsequent string operation. Use `String::from_utf8(vec)` (release-mode validation) on a Rust-owned `Vec<u8>` instead.

**Workspace inventory.** As of issue #16: **zero** production occurrences of `Vec::from_raw_parts`, `Vec::from_raw_parts_in`, `String::from_raw_parts`, or `Vec::set_len` (verified via `rg '\bVec(::<[^>]*>)?::(from_raw_parts(_in)?|set_len)\b'` and `rg '\bString::from_raw_parts\b'` across all crates). The "Rust allocates, foreign code writes" pattern is handled by `BufferHandle` and `Vec::with_capacity + spare_capacity_mut`; the io_uring fixed buffers are `Box<[u8]>` allocated by `Vec::new(...).into_boxed_slice()` and never round-trip through raw parts. The two `set_len` hits in the workspace are `BufferHandle::set_len(&mut self, usize)` (a safe inherent method on a typed wrapper that clamps to the underlying buffer capacity) and `std::fs::File::set_len` (truncation syscall); neither is `Vec::set_len`.

**Allowlist entry requirements.** A `Vec::from_raw_parts`, `Vec::from_raw_parts_in`, or `String::from_raw_parts` allowlist entry's `enforcement` field MUST address every point of the eight-point checklist above (the same five-field rubric as `Box::from_raw` is insufficient because `Vec` carries `len` and `cap` separately and because `String` adds the UTF-8 invariant):

1. Allocation origin (which Rust `Vec<T>::into_raw_parts` or equivalent produced the pointer).
2. Element type `T` (matching on both sides).
3. Alignment proof (allocator guarantee or explicit check).
4. Initialised length (exactly which bytes are valid `T` values, and the validity argument).
5. Capacity (matches the original allocation size).
6. Allocator (default global unless named; for `from_raw_parts_in`, the allocator instance must be the same one that allocated the buffer).
7. `len <= cap` (structural argument).
8. Unique ownership (which holder type carries the parts between `into_raw_parts` and `from_raw_parts`, and why it is `!Copy + !Clone`).

## Unsafe `String`/`str` construction

`String` and `&str` carry an additional invariant beyond `Vec<u8>`: the byte contents MUST be valid UTF-8 in the Unicode standard's strict sense (well-formed UTF-8, no overlong encodings, no surrogate code points, no invalid continuation bytes). The library and the language both assume this invariant for every operation: `chars()` iteration, `.len()`/`.is_char_boundary()`/`.split_at()`, formatting, slicing with `&s[a..b]`, and all higher-level APIs (regex, parser combinators, JSON). Violating it produces UB on the very next read, even if the bad bytes are never directly observed.

Four unsafe constructors can violate this invariant:

| API | Skipped check | Owned? |
|---|---|---|
| `String::from_raw_parts(ptr, len, cap)` | UTF-8 validity AND every `Vec::from_raw_parts` precondition | Yes |
| `String::from_utf8_unchecked(bytes)` | UTF-8 validity (allocation already Rust-owned) | Yes |
| `str::from_utf8_unchecked(&bytes)` | UTF-8 validity (borrowed) | No |
| `str::from_boxed_utf8_unchecked` | UTF-8 validity (boxed) | Yes |

The audit checklist for each occurrence:

1. **UTF-8 validity proof.** Where do the bytes come from? The proof MUST be either: - Bytes copied verbatim from another `&str` / `String` (already valid by the source's invariant). - Output of a known-UTF-8-correct producer (Rust's `format!`, `serde_json`'s output writer, etc.) with the producer named in the SAFETY comment. - A previously-validated slice; the validation site MUST be in the same function or a same-crate helper with a documented type-state transition. - Trivially-UTF-8 bytes by construction (ASCII-only output, hex-digit alphabet, base64 alphabet, etc.) with the construction step named.

   Network / file / FFI / parser input is **never** a sound source — there's always a hostile path that plants malformed bytes. Use `String::from_utf8`, `str::from_utf8`, or `String::from_utf8_lossy` instead.
2. **Initialised.** Same checklist point as `Vec::from_raw_parts`: bytes `[0, len)` of the allocation must be initialised. UB if any byte in that range is `MaybeUninit`-uninitialised.
3. **Live.** The pointee must outlive the returned reference's lifetime. For `str::from_utf8_unchecked` this is bounded by the input slice; for the owned variants the new `String` takes ownership and the liveness chain transfers to it.
4. **Unique ownership (owned variants only).** Same checklist point as `Vec::from_raw_parts`: no aliased `Vec<u8>`/`&[u8]`/`&str` to the same buffer may exist while the new `String` is live.
5. **`len`/`cap` correctness (`String::from_raw_parts` only).** Inherits every `Vec::from_raw_parts` precondition above, plus the UTF-8 invariant. The compound contract is the strictest in std.

**Rule.** Application code SHOULD NOT use any of the four unsafe `String`/`str` constructors. The preferred shapes, in order:

1. **`String::from_utf8(bytes)` (returns `Result`).** The release-mode validated alternative; one linear scan over the bytes. This is the workspace's default and appears at every parser/network boundary (`ripdpi-warp-core/src/socks.rs`, `ripdpi-tuic/src/protocol.rs`, `ripdpi-relay-core/src/socks/auth.rs`, `ripdpi-diagnostics-tls/src/tls/certs.rs`, `ripdpi-geo/src/lib.rs`).
2. **`String::from_utf8_lossy(&bytes)` (returns `Cow<str>`).** Use when the input is best-effort logging/classification and invalid sequences should be substituted with U+FFFD rather than rejected. Used by the failure-classifier crates (`ripdpi-failure-classifier`) and packet introspection (`ripdpi-packets/src/classify.rs`).
3. **`str::from_utf8(&bytes)` (returns `Result`).** The borrowed variant; same one-scan cost. Used at the parser boundaries (`ripdpi-vless/src/wire.rs`, `ripdpi-naiveproxy/src/connect_tunnel.rs`, `ripdpi-relay-core/src/socks/udp_frame.rs`, `ripdpi-shared-priors/src/lib.rs`, the DoH chunk reader).
4. **Bytes-only API.** If the consumer doesn't need a `str`/`String`, keep the data as `&[u8]` / `Vec<u8>` / `bstr::BStr` and skip the validation entirely. The `ripdpi-packets` HTTP host-extraction path stays `&[u8]` until the final `from_utf8_lossy` at the classifier surface.

**Anti-patterns.**

- `String::from_utf8_unchecked(network_response)` — hostile input is **never** guaranteed UTF-8. Always use the validated `String::from_utf8`.
- `str::from_utf8_unchecked(&buf[..n])` where `buf` is a recv buffer — same problem; use `str::from_utf8` and propagate the `Result`.
- `String::from_raw_parts(ptr, len, cap)` — combines every `Vec::from_raw_parts` failure mode with the UTF-8 invariant. There is no situation in this workspace where this is the right tool.
- `String::from_utf8(bytes).unwrap()` on a non-trusted input — moves the panic from validation to the unwrap site without fixing the underlying issue. Use `String::from_utf8(bytes).map_err(...)` or `String::from_utf8_lossy(&bytes).into_owned()`.

**Workspace inventory.** As of issue #17: **zero** production occurrences of `String::from_raw_parts`, `String::from_utf8_unchecked`, `str::from_utf8_unchecked`, or `str::from_boxed_utf8_unchecked`. Every byte-to-string conversion in the workspace uses one of the four preferred shapes above. The scanner enforces zero baseline going forward.

**Allowlist entry requirements.** A `String::from_raw_parts`, `String::from_utf8_unchecked`, or `str::from_utf8_unchecked` allowlist entry's `enforcement` field MUST address every point of the checklist above as six NAMED mandatory fields:

1. **UTF-8 validity proof.** Which producer / validator guarantees the input is valid UTF-8, and why that guarantee survives every reachable code path.
2. **Input trust boundary.** Where do the bytes physically enter Rust ownership? Acceptable origins: `'static` rodata, the output of `format!` / `write!`, a previously-validated `&str` / `String`, an ASCII / hex / base64 alphabet enforced at the parser layer, or a Rust-allocated and Rust-filled buffer whose producer is named and checked. **Forbidden origins:** network reads, file reads, FFI inputs, unbounded parser output, any external API surface. Untrusted bytes MUST use `String::from_utf8` / `str::from_utf8` / `String::from_utf8_lossy` instead, propagating the `Result` to the caller.
3. **Initialised.** Matching `Vec::from_raw_parts` discipline for the owned variants.
4. **Live.** Lifetime argument for the borrowed variant; ownership-transfer argument for the owned variants.
5. **Unique ownership** (owned variants only). Which `!Copy + !Clone` holder carries the bytes between the validation site and the unchecked constructor.
6. **`len`/`cap` correctness** (`from_raw_parts` only).

Every allowlisted occurrence MUST also be preceded by an inline `// SAFETY:` comment in the source enumerating the same six fields locally — the allowlist entry is the auditor-facing summary; the SAFETY comment is the reviewer-facing proof at the call site. Per `docs/rust-soundness-policy.md` § "Documentation contract", every unsafe block in production code already requires a SAFETY comment; this rule restates the requirement for the unchecked-string case where the consequence (UTF-8-invariant break → UB on the next `chars()` iteration) is particularly easy to overlook.

For `String::from_raw_parts` specifically, the allowlist entry must address ALL eight `Vec::from_raw_parts` checklist points PLUS the six fields above — the strictest single-API contract in std.

## Allocator mismatch across FFI

When an allocation crosses an FFI boundary, the **same** allocator that produced the pointer MUST be the one that frees it. The Rust default global allocator (`std::alloc::System` on Unix targets) and libc's `malloc` / `free` may or may not be the same heap — the relationship is target- and toolchain- defined and changes silently on a `#[global_allocator]` switch. Mixing them is undefined behaviour.

The four classic allocator-mismatch patterns:

1. **C allocates, Rust frees.** `Box::from_raw(libc::malloc(n) as *mut T)` — the `Box::drop` calls the Rust global allocator's `dealloc`, which may not be `libc::free`. Even when it is, the layout that `dealloc` reconstructs (`Layout::for_value(&*ptr)`) might differ from what `malloc` actually saw, and `dealloc` is contractually not allowed to handle that mismatch.
2. **Rust allocates, C frees.** `let p = Box::into_raw(Box::new(t)); foreign_free(p);` — the foreign code calls `libc::free` (or another C deallocator) on a pointer the Rust global allocator owns. Same UB as above, mirrored.
3. **Wrong-allocator `CString::from_raw`.** `CString::from_raw(libc::malloc(n) as *mut c_char)` — `CString::drop` runs the Rust deallocator on a `libc::malloc`-allocated buffer. UB.
4. **Allocator-mismatched `Vec::from_raw_parts_in`.** Already covered in "`Vec::from_raw_parts` ownership transfer" point 6 (allocator compatibility).

**Rule.** Each allocation that crosses an FFI boundary MUST take one of these forms:

1. **Foreign-managed lifetime.** The foreign library allocates AND frees; Rust receives a `*mut T` / `*const T` and either: - never frees it (non-owning observer pattern; the foreign side guarantees the pointer outlives every Rust use), OR - explicitly calls the foreign deallocator (e.g. `SSL_CTX_free`, `EVP_PKEY_free`) inside an RAII wrapper. The workspace's `ScopedHandle<T, F: FreeFunction<T>>` in `soundness-canaries/src/lib.rs` is the canonical implementation.
2. **Rust-managed lifetime.** Rust allocates AND frees; the foreign side receives a borrowed `*const T` / `*mut T` for the duration of a call and never retains it past the call. No `Box::into_raw` needed.
3. **Paired `rust_alloc` / `rust_free` exports** (also documented in "`Box::into_raw` / `Box::from_raw` ownership transfer" § "FFI ownership shapes"). The crate exposes two `extern "C" fn`s: `rust_alloc_FOO() -> *mut FOO` (Box::into_raw) and `rust_free_FOO(*mut FOO)` (Box::from_raw). Foreign code is contractually required to call exactly one `rust_free_FOO` for every `rust_alloc_FOO`.
4. **Unsafe-fn install + RAII reclaim** (also documented in "`Box::into_raw` / `Box::from_raw` ownership transfer" § "FFI ownership shapes"). Rust leaks one Box via `Box::into_raw` and reclaims it in the guard's `Drop`.

**Anti-patterns.**

- `Box::from_raw(libc::malloc(n) as *mut T)` — see pattern 1 above.
- `unsafe { libc::free(b.as_ptr() as *mut _) }` for any `Box<T>` / `Vec<T>` / `String` `b` — see pattern 2 above. The `free` runs on a Rust allocation.
- `CString::from_raw(c_string_returned_by_strdup)` — `strdup` uses `malloc`, but `CString::drop` runs the Rust deallocator.
- A scanner allowlist entry that names the matching `into_raw` but the partner lives in a different crate. The two must live in the same module so a reviewer can match them without crossing files.

**Workspace inventory.** As of issue #18: **zero** production occurrences of any allocator-crossing pattern.

- `rg '\blibc::(malloc|calloc|realloc|free)\b'` — zero
- `rg '\bCString::(from_raw|into_raw)\b'` — zero
- `rg '#\[global_allocator\]'` — zero (workspace uses the default `std::alloc::System`)
- `rg 'extern "C" \{'` — exactly one `extern "C" {}` block in `ripdpi-vless/src/reality_hook.rs` (BoringSSL Reality client_hello hook). The three imported BoringSSL functions are `SSL_handshake_get_x25519_private_key` (fills a caller-owned 32-byte stack buffer; no allocation crosses the boundary), `SSL_CTX_set_client_hello_cb` (installs a Rust callback + Rust-owned `Box::into_raw` `arg` — the Rust-managed lifetime reclaimed by `RealityHookGuard::Drop` per issue #15), and `SSL_get_SSL_CTX` (returns a BoringSSL-managed pointer that Rust never frees — non-owning observer per shape 1). All three are sound.

The only Rust→C heap transfer in the workspace is the already-audited `Box::into_raw(Box<RealityCallbackState>)` / `Drop for RealityHookGuard` pair (issue #15, Miri-validated).

**Allowlist entry requirements.** A `libc::malloc`, `CString::from_raw`, or `CString::into_raw` allowlist entry's `enforcement` field MUST address every point below:

1. **C-allocator provenance.** Which foreign function produced the pointer (`libc::malloc`, `strdup`, `EVP_PKEY_new`, etc.). The reviewer must be able to follow the chain `foreign_alloc -> ... -> matching free` without leaving the policy entry.
2. **Matching deallocator.** The C function that frees the allocation. Must be the documented dual of the producer; `libc::malloc` is paired with `libc::free`, not with `Box::drop`.
3. **Type and layout.** Which `T` the pointer addresses and how the alignment is guaranteed (`malloc` only guarantees `MAX_ALIGN`; if `T` has higher alignment requirements use `posix_memalign` or `aligned_alloc`).
4. **Pair locality.** Both ends of the allocation/deallocation must live in the same module or be exposed as a documented `rust_alloc_FOO` / `rust_free_FOO` pair.
5. **No allocator switch.** Whether the entry remains sound if a future `#[global_allocator]` is added to the workspace. If not, the entry must say so explicitly so a future contributor can re-evaluate.

## `Vec::set_len` initialisation contract

`Vec::set_len(new_len)` is an `unsafe fn` that adjusts the length field of a `Vec<T>` without touching the buffer. After the call, the `Vec` claims that bytes `[0, new_len * size_of::<T>())` of its allocation contain valid `T` values. Every read, drop, and `&[..]` / `&mut [..]` borrow assumes that claim is true. Failures:

| Failure mode | Consequence |
|---|---|
| `new_len` past the initialised prefix | Drop runs on uninit memory (UB if `T: Drop`); `&[..]` exposes uninit bytes (UB on any subsequent read). |
| `new_len > capacity` | UB on the next push / resize / drop — `Vec` assumes its length-cap invariant. |
| Panic between `with_capacity(N)` and `set_len(n)` while the spare region is partly written | The Vec's len is still 0 (set_len hasn't run), so Drop runs on no elements. Safe for `T: !Drop` (e.g. `u8`); for `T: Drop` the partially-initialised tail is leaked but not unsoundly used. |
| `&mut [u8]` borrow of the spare region before `set_len` | Sound because the spare region is typed `MaybeUninit<T>`. Reading without writing is the failure mode. |

The audit checklist for every `Vec::set_len(n)` site:

1. **Initialised prefix.** A producer wrote valid `T` values to every slot in `[0, n)` before `set_len(n)` runs. The producer is named explicitly in the SAFETY comment (e.g. "`recv(2)` returned `n` and is documented to write `n` bytes", "`MaybeUninit::write` was called for each slot in the loop above").
2. **`n <= capacity`.** Asserted on the line(s) immediately above the `set_len` call. `Vec`'s internal invariant breaks otherwise.
3. **Panic-path soundness.** Either: - `T: !Drop` (e.g. `u8`, `u32`, `bool`, `MaybeUninit<U>`), in which case the half-initialised tail doesn't matter on unwind — `len` stays 0 and Drop is a no-op, OR - a scope-bound RAII guard reduces `len` to the last-known-good prefix on unwind. The `std::vec::Drain` and `Vec::extend_from_slice` implementations are the std reference for this pattern.
4. **No re-entrant reads.** Between the `with_capacity` / `reserve` / `spare_capacity_mut` site and the matching `set_len`, no code path may re-borrow the Vec as `&[T]` / `&mut [T]` — the spare region's typing is `MaybeUninit<T>`, not `T`, and accessing it as `T` is UB regardless of the buffer's runtime contents.

**Rule.** Application code SHOULD NOT call `Vec::set_len` directly. The preferred shapes, in order:

1. **Safe `Vec::push` / `Vec::extend` / `Vec::extend_from_slice`.** The bytes are typed `T` on the way in; no `MaybeUninit` exists; no `set_len` needed.
2. **`Vec::with_capacity` + `spare_capacity_mut` + guarded `set_len`.** Use when a foreign filler (`recv`, `read`, FFI buffer fill) writes into a Rust-allocated buffer. The `spare_capacity_mut()` typing (`&mut [MaybeUninit<T>]`) keeps the uninitialised state visible to the type system; the filler writes through `MaybeUninit::write`; the matching `set_len(n)` runs only after the filler reports `n`. This is the workspace's recommended idiom for the "Rust allocates, foreign code writes" pattern, demonstrated end- to-end by `vec_with_capacity_spare_capacity_round_trip_models_recv_fill` in `soundness-canaries/src/lib.rs`.
3. **A typed buffer wrapper.** When the lifecycle spans multiple operations (e.g. io_uring fixed buffers), encapsulate the spare-region writing in a safe `&mut [u8]`-handing-out wrapper. The workspace's `BufferHandle` in `ripdpi-io-uring/src/bufpool.rs` is the reference: `BufferHandle::set_len(&mut self, len: usize)` is a SAFE inherent method that clamps to `buffer_size`; the caller never sees `MaybeUninit<u8>` or the bare `Vec::set_len`.

**Anti-patterns.**

- `let mut v = Vec::with_capacity(N); foreign_fill(v.as_mut_ptr(), N); unsafe { v.set_len(N); }` — bypasses `MaybeUninit` typing, hard to audit, and the SAFETY comment must hand-wave about the foreign contract. The correct shape is `foreign_fill(v.spare_capacity_mut().as_mut_ptr().cast(), N); unsafe { v.set_len(n) };` with `n <= N`.
- `unsafe { v.set_len(n) }` where the loop above wrote `n` elements via index assignment (`v[i] = …`) instead of `MaybeUninit::write` — `v[i]` is `&mut T` and assigns through, but the Vec's `len` was 0 at the time, so `v[i]` is itself UB. Use `spare_capacity_mut()[i].write(value)` instead.
- `unsafe { v.set_len(n) }` immediately followed by `&v[..]` when only some of `[0, n)` was written — the borrow exposes uninit bytes. Set `len` to the initialised count, not the buffer capacity.

**Workspace inventory.** The policy baseline permits no unaudited production
`Vec::set_len` calls. Use the current unsafe-boundary scanner to distinguish
that API from unrelated methods such as `std::fs::File::set_len`.

**Allowlist entry requirements.** A `unsafe Vec::set_len` allowlist entry's `enforcement` field MUST address every point as FIVE NAMED mandatory fields:

1. **Initialisation proof.** Which code wrote valid `T` values to slots `[0, n)` before the `set_len` ran. Name the producer explicitly (e.g. "`simulated_recv_fill` wrote each slot via `MaybeUninit::write` in the loop above", "`libc::recv` returned `n` and is documented to write `n` bytes"). "The buffer is filled" is not a proof; the writer function must be named.
2. **`n <= capacity` proof.** Where the assertion lives (typically an `assert!` on the line above the `set_len`). If the guarantee is structural (e.g. `n` is the return value of a function whose contract is `0 <= ret <= capacity`), name the function and the contract.
3. **Element type and Drop semantics.** Name `T` explicitly and whether `T: Drop`. `T: !Drop` (`u8`, `u32`, `bool`, `MaybeUninit<U>`) makes panic-path soundness trivial; `T: Drop` requires a scope-bound RAII guard that reduces `len` to the last-known-good prefix on unwind.
4. **Panic-path safety.** The argument that an unwind between `with_capacity` and `set_len` cannot run destructors on uninitialised memory. Either field 3's `T: !Drop` is sufficient, OR the entry names the unwind guard.
5. **Owner.** Crate/team responsible for keeping the entry sound. Matches the `owner` TOML field but restated in the `enforcement` summary so the reviewer can see the responsible party without scrolling.

**CI Miri coverage.** Every `unsafe Vec::set_len` allowlist entry SHOULD also be exercised under Miri in `scripts/ci/run-rust-miri.sh` (the workspace's "targeted Miri smoke" CI gate). The existing `soundness-canaries` Miri coverage already includes the workspace's only `Vec::set_len` site (the `with_capacity` + `spare_capacity_mut` + `set_len` round-trip test); future allowlisted occurrences in production code must add their own Miri coverage in the same script so the strict-provenance borrow- stacked machine validates them at every PR.

## `MaybeUninit` correctness

`MaybeUninit<T>` is the std-library escape hatch for "I have a slot the size and alignment of `T` but I have not initialised it yet". The type carries no runtime tag; the compiler trusts the programmer to prove `T`-validity before any of the five `assume_init`-family methods runs. The five methods and their failure modes:

| API | Failure mode if slot is uninit |
|---|---|
| `MaybeUninit<T>::assume_init(self) -> T` | UB on Drop and on every subsequent read. |
| `assume_init_ref(&self) -> &T` | UB on every read through the `&T`. |
| `assume_init_mut(&mut self) -> &mut T` | UB on every read and on the write of a non-trivial `T`. |
| `assume_init_drop(&mut self)` | UB if Drop reads any uninit field. |
| `assume_init_read(&self) -> T` | UB on every read of the returned `T`, and the original slot is logically duplicated (`T: Copy`-style) so Drop must not later run on the same allocation. |

The audit checklist for every `assume_init*` call:

1. **Every byte of `T` written.** A producer wrote valid bytes for every field of `T` BEFORE `assume_init` ran. The producer is named in the SAFETY comment (e.g. "the C call `getsockopt` filled all `size_of::<T>()` bytes", "`MaybeUninit::write` was called for each field in the block above").
2. **Padding handled.** If `T` has padding bytes (e.g. `#[repr(C)] struct { a: u8, b: u32 }` has 3 bytes of padding between `a` and `b`), those padding bytes are EITHER zeroed at allocation (e.g. via `mem::zeroed`) OR proven to be irrelevant (the consumer reads only the named fields, never `as_bytes` / `transmute` of the whole struct).
3. **No `&T` / `&mut T` to uninit memory.** The only sound way to read uninit slots is through `MaybeUninit<T>` (or `&[MaybeUninit<T>]`); even creating a `&T` to uninit memory and immediately discarding it is UB. `MaybeUninit::as_ptr()` is sound (it returns `*const T`, not `&T`).
4. **Drop semantics.** If `T: Drop` and the slot is only partially initialised on a panic-unwind path, the partial state must not reach Drop. The std reference pattern is `MaybeUninit<T>` slots inside an array with a scope-bound RAII guard that calls `assume_init_drop` only on indices that have been written.
5. **Reference creation timing.** Between the slot allocation (`MaybeUninit::uninit()`) and the `assume_init`, no code path may borrow the underlying memory as `&T` / `&mut T` — only `&mut [MaybeUninit<T>]` is sound for uninit buffers.

**Rule.** Application code SHOULD NOT use `assume_init` family methods. The preferred shapes, in order:

1. **Safe constructors.** `T::default()`, struct literals with all fields named, `Vec::new()` + `push`, `String::new()` + `push_str`, etc.
2. **`array::from_fn(|i| init(i))`** for arrays that can be initialised by a closure. The closure runs in element order; if it panics mid-build, std's drop guard correctly drops the prefix it built.
3. **`Vec::with_capacity` + `spare_capacity_mut` + guarded `set_len`** (per "`Vec::set_len` initialisation contract"). The `spare_capacity_mut()` typing keeps `MaybeUninit` visible; writes go through `MaybeUninit::write`; `set_len` runs only after the producer reports `n`.
4. **`unsafe fn` recv-style API directly accepting `&mut [MaybeUninit<T>]`.** Std's `UdpSocket::recv_from` / `TcpStream::read` / `read_buf` accept `&mut [MaybeUninit<u8>]` natively (Rust 1.85+); no `assume_init` needed because the bytes go through `slice::from_raw_parts(..., received)` to produce a `&[u8]` of exactly the initialised prefix. This is the pattern used at the only `MaybeUninit` production site in the workspace (`ripdpi-privileged-ops/src/linux/experimental_tier3/icmp_wrapped_udp.rs`).

**Anti-patterns.**

- `let mut a: [MaybeUninit<T>; N] = unsafe { MaybeUninit::uninit().assume_init() };` — the famous "uninit assume_init" trick. Sound only because `MaybeUninit<T>` has no validity invariant. Use `[const { MaybeUninit::uninit() }; N]` (Rust 1.79+) or `MaybeUninit::<[MaybeUninit<T>; N]>::uninit().assume_init()` with a SAFETY comment naming the "MaybeUninit<MaybeUninit<T>> always valid" argument.
- `let r: &T = unsafe { uninit.assume_init_ref() }` followed by `r.field` when the slot's bytes are partially uninit — UB on the field access.
- `let v: T = unsafe { uninit.assume_init() }` for `T: Drop` when the slot is only partially initialised — Drop runs on uninit memory.
- `mem::uninitialized::<T>()` — soft-deprecated; use `MaybeUninit::<T>::uninit()` instead. (The workspace has zero occurrences.)

**Workspace inventory.** As of issue #20:

| Site | Shape | Audit |
|---|---|---|
| `ripdpi-privileged-ops/.../icmp_wrapped_udp.rs:27` | `[MaybeUninit<u8>; 8192]` recv buffer, consumed via `slice::from_raw_parts(buf.as_ptr().cast::<u8>(), received)` | Sound. `UdpSocket::recv_from` natively accepts `&mut [MaybeUninit<u8>]` and is documented to initialise the first `received` bytes. The follow-on `slice::from_raw_parts` is allowlisted under issue #6. No `assume_init*` is used. |
| `soundness-canaries/.../lib.rs (test)` | Test-mode `&mut [MaybeUninit<u8>]` parameter in `simulated_recv_fill` | Sound. Issue #16 regression test demonstrating the workspace's recommended `with_capacity + spare_capacity_mut + set_len` idiom. Miri-validated. |

**ZERO production `assume_init` / `assume_init_ref` / `assume_init_mut` / `assume_init_drop` / `assume_init_read` calls** in the entire workspace. Every byte-fill operation goes through either `recv_from(&mut [MaybeUninit<u8>])` followed by `slice::from_raw_parts` (issue-#6-audited) or `Vec::with_capacity + spare_capacity_mut + MaybeUninit::write + set_len` (issue-#16-audited). The scanner enforces zero baseline going forward.

**Allowlist entry requirements.** An `MaybeUninit::assume_init` allowlist entry's `enforcement` field MUST address every point as FIVE NAMED mandatory fields:

1. **Initialisation proof** (which producer wrote every byte of `T`).
2. **Padding argument** (padding bytes zeroed or proven irrelevant).
3. **Reference safety** (no `&T`/`&mut T` to uninit memory created before `assume_init`).
4. **Drop safety** (panic-path guard, or `T: !Drop` stated explicitly).
5. **Owner** (crate/team, restated in the enforcement summary).

**CI Miri coverage.** Per "`Vec::set_len` initialisation contract", any new allowlisted `assume_init*` site in production code SHOULD also be exercised under Miri in `scripts/ci/run-rust-miri.sh`. The existing `soundness-canaries` Miri coverage validates the recommended `with_capacity + spare_capacity_mut + set_len` round-trip (which writes via `MaybeUninit::write` and would catch a regression that introduced unsound `assume_init` usage in the same crate).

## Zero-initialisation validity

`mem::zeroed::<T>()` and its variants (`MaybeUninit::<T>::zeroed`, `ptr::write_bytes(ptr, 0, n)`, `libc::memset(ptr, 0, n)`) produce a `T` (or `n` `T` values) whose bytes are all zero. The runtime cost is one `memset`; the soundness cost depends entirely on whether the all-zero bit pattern is a valid `T`.

**Types where zero IS a valid bit pattern:** integers, `f32`/`f64`, `[u8; N]` and other arrays of zero-valid types, `#[repr(C)]` POD structs whose every field is zero-valid, `Option<&T>` / `Option<Box<T>>` / `Option<NonNull<T>>` / `Option<NonZeroU32>` (the niche optimisation makes zero represent `None`), `MaybeUninit<T>`, and raw pointers `*mut T` / `*const T` (null bit pattern is fine; dereferencing it is the UB).

**Types where zero is NOT a valid bit pattern (UB to construct via `mem::zeroed`):** `&T` / `&mut T` (never null), `Box<T>` / `Rc<T>` / `Arc<T>` (never null), `NonNull<T>`, `NonZeroU*` / `NonZeroI*`, `bool` byte values outside `{0, 1}`, `char` surrogates and out-of-range code points, enums whose `0` discriminant is not declared (e.g. `#[repr(u8)] enum { A = 1, B = 2 }`), function pointers (`fn()`, `extern "C" fn(...)`), and any `#[repr(transparent)]` newtype around the above.

The audit checklist for each zero-init site:

1. **Identify `T`** (or the element type for `ptr::write_bytes` / `libc::memset`).
2. **Field-by-field zero-validity.** If `T` is a struct/enum, every field's all-zero bit pattern must be in the field's validity domain. Recurse into nested types.
3. **Reference/pointer/function-pointer check.** Does `T` transitively contain any `&T` / `&mut T` / `Box<T>` / `NonNull<T>` / `NonZero*` / function pointer / non-zero-variant enum? If yes, `mem::zeroed::<T>` is UB.
4. **`#[repr(C)]`.** FFI structs MUST be `#[repr(C)]` so the layout is stable and field offsets are knowable. Zero-init across versions of a `#[repr(Rust)]` struct is fragile because the compiler is free to reorder fields and change padding.
5. **Padding bytes.** With `mem::zeroed`, padding bytes are guaranteed zero; with `MaybeUninit` they're tracked as uninit. This matters when the consumer reads the struct as `&[u8]` or passes it across FFI as a byte block.

**Rule.** Application code SHOULD NOT use `mem::zeroed` or its variants. The preferred shapes, in order:

1. **Safe constructors:** `T::default()`, struct literals with every field named, `Vec::new()`, `String::new()`, `[const { … }; N]`.
2. **`MaybeUninit` staged init:** `let mut u = MaybeUninit::<T>::uninit(); /* fill */ unsafe { u.assume_init() }`. Forces field-by-field accountability — no "memset and pray".
3. **Field-by-field zero, not whole-struct zero:** `let s = MyStruct { a: 0, b: 0, c: false };` — the compiler chooses the byte representation; you don't pretend zero bytes are a valid `MyStruct`.

**Audited production zero-fill sites.** The current allowlist, rather than a
fixed count, is authoritative.

| File | API | Element type | Sound because |
|---|---|---|---|
| `ripdpi-vless/src/reality_hook.rs` | `ptr::write_bytes(*mut u8, 0, 32)` | `u8` | Fixed-size BoringSSL output buffer; every byte pattern is valid and the callback validates pointers and length before writing. |
| `ripdpi-privileged-ops/src/linux/mmap_region.rs:65` | `ptr::write_bytes(*mut u8, 0, len)` | `u8` | Element type is `u8`; every bit pattern is a valid `u8`. Destination is exclusive (`&mut self` on the owning `MmapRegion: !Copy`); no aliased reader can observe a mid-write state. Bounds (`len`) come from the region's own owned `NonZeroUsize`. |

**Anti-patterns.**

- `let s: MyStruct = unsafe { mem::zeroed() };` where `MyStruct` contains a `Box<u8>` field — UB; zero is a null Box.
- `let f: fn() = unsafe { mem::zeroed() };` — UB; zero is not a valid function pointer.
- `unsafe { ptr::write_bytes(buf.cast::<MyEnum>(), 0, n) };` for an enum whose `0` variant is not declared — UB on every subsequent read.
- `let mut x = MaybeUninit::<&T>::zeroed(); unsafe { x.assume_init() };` — UB; references cannot be null.

**Allowlist entry requirements.** A `mem::zeroed`, `MaybeUninit::zeroed`, `ptr::write_bytes`, or `libc::memset` allowlist entry's `enforcement` field MUST address all FIVE NAMED mandatory fields:

1. **Element type and layout** (concrete `T`, its `#[repr]`, the field list if relevant).
2. **Field-by-field zero-validity** (every field's validity domain; recursive if a field is itself a struct).
3. **No invariant-bearing fields** (no references, `NonNull`, `NonZero*`, `Box`, function pointer, non-zero-variant enum).
4. **Padding-byte semantics** (if the consumer reads the struct as `&[u8]`, that the padding-zero claim is documented; otherwise that the consumer reads only named fields).
5. **Owner.**

## Lifetime extension

Three patterns can silently extend the lifetime of a Rust reference past its owner's scope, all of which are UB on the next read:

1. **`mem::transmute::<&'a T, &'b T>`** — the textbook trick. Sound iff `'a: 'b`, but the cast itself doesn't check; only the programmer does.
2. **`mem::transmute_copy`** — the under-the-radar cousin. Does NOT enforce `size_of::<T>() == size_of::<U>()` at compile time; a size mismatch silently reads past the source allocation. Equally usable for lifetime extension AND for ABI-mismatched type punning.
3. **Raw pointer round-trip with synthesized lifetime** — `let ptr = r as *const T; ... let r2: &'static T = unsafe { &*ptr };` synthesises a `'static` lifetime out of a borrowed pointer. The borrow checker can't see what the `unsafe` block claims; the next read past the original owner's scope is UB.

The audit checklist for every potential lifetime-extension site:

1. **Where does the reference's lifetime come from?** Is it bound by the borrow checker to a real owner (`&self`, `&'a T` parameter, RAII guard), or does the function signature use an unconstrained `'a` (`fn f<'a>() -> &'a T`)?
2. **Is the returned `'static` actually `'static`?** A function returning `&'static str` is sound iff its body returns a string literal, `OnceLock`-backed value, `Box::leak`-ed allocation, or other genuinely-`'static` data. Returning `&'static str` from a `let s = String:: from(...); &s` is UB — the local `String` drops at end of function.
3. **Does an unsafe block synthesise a lifetime?** Look for `&*ptr` / `&mut *ptr` / `transmute::<*const _, &_>` / `transmute::<*mut _, &mut _>` patterns. If the returned reference's lifetime isn't tied to a real owner, it's UB-by-construction.
4. **Is an explicit `Box::leak` / `Vec::leak` / `String:: leak` paired with a justification?** Sound by language definition (the language-level `'static` is genuine — the memory is never freed), but the leaked memory is unreachable for the rest of the process lifetime. Each leak must be deliberate: process-lifetime configuration, one-time symbol-table allocation, `'static` callback registration. Never use to "fix" a lifetime error.

**Rule.** Application code SHOULD NOT extend reference lifetimes through `transmute` or raw-pointer tricks. The preferred shapes, in order:

1. **Tie the reference's lifetime to a real owner.** `fn get(&self) -> &T` is sugar for `fn get<'a>(&'a self) -> &'a T` — the returned `&T` cannot outlive `&self`, the borrow checker enforces it. The workspace's `MappedFile::as_slice(&self) -> &[u8]`, `BufferHandle::deref(&self) -> &[u8]`, and the `*const u8` returns from `MmapRegion::as_ptr(&self)` (followed by caller-side `&*ptr` bounded by the same `&self`) all use this shape.
2. **Return owned values.** `fn get(&self) -> String` instead of `fn get(&self) -> &'static str` when the data is per-instance.
3. **`Arc<T>` for shared ownership.** Clone the `Arc` instead of extending a reference; the language tracks the refcount for you.
4. **`Cow<'a, T>`** when the data may or may not need to be owned (e.g. parser output that might be a borrow into the input OR a fresh owned string after sanitisation).
5. **Explicit `Box::leak` / `Vec::leak` / `String::leak` with a SAFETY comment.** Reserved for process-lifetime configuration / one-shot allocations that genuinely live forever. Document WHY the leak is permanent (size bound, no per-request growth, no other lifecycle alternative).

**Anti-patterns.**

- `fn get_static<'a>(input: &'a str) -> &'static str { unsafe { std::mem::transmute(input) } }` — UB; the function's body claims `'static` but the input is bounded by `'a`.
- `let ptr = local_string.as_ptr(); let s = unsafe { &*ptr };` followed by use after `local_string` drops — UB; classic dangling-pointer-via-raw-pointer trick.
- `let r: &'static Config = unsafe { mem::transmute(& config) };` where `config` is a local — UB; the `config` drops at end of scope and the `'static` reference dangles.
- `Box::leak(Box::new(per_request_data))` to satisfy a function signature requiring `&'static T` — actually sound at the language level, but accidentally creates a per-request memory leak. Use `Arc<T>` and change the signature instead.

**Workspace inventory.** As of issue #22: **zero** production occurrences of any lifetime-extension trick.

- `rg '\bmem::transmute(_copy)?\b'` — zero (the workspace has only the scanner-pattern test fragments).
- `rg '\b(Box|Vec|String)::leak\b'` — zero.
- `rg 'extend_lifetime'` — zero.
- Every `&'static` return in the workspace is one of: - string literal / rodata (e.g. `proto_name(&L7Protocol) -> &'static str` in `ripdpi-strategy-lua`, `as_str(self) -> &'static str` on enum variants throughout `ripdpi-capabilities`, telemetry phase labels in `ripdpi-runtime-api`), - `'static` field of a `Copy`-trivial metadata struct (e.g. `StrategyDescriptorRegistration { id: &'static str, describe: fn() -> StrategyDescriptor }`), - `OnceLock::get_or_init` return value (e.g. `telemetry_slot() -> &'static Mutex<…>` in `ripdpi-runtime-api::global_telemetry`).

Each is sound: the language type system enforces the `'static` claim against an actually-`'static` value.

**Allowlist entry requirements.** A `mem::transmute`, `mem::transmute_copy`, or `explicit leak` allowlist entry's `enforcement` field MUST address all FIVE NAMED mandatory fields:

1. **Lifetime origin** (what the source reference's lifetime is, and what the target lifetime is).
2. **Lifetime-extension argument** (proof the target lifetime is sound for every reachable code path — typically "the owner is `'static`" or "the transmute is a no-op lifetime-wise, only the type changes").
3. **Size argument** (for `transmute_copy` only: explicit `size_of::<T>() == size_of::<U>()` proof, either by named ABI contract or by `const_assert!`).
4. **Leak justification** (for `Box::leak` / `Vec::leak` / `String::leak` only: why the leak is genuinely permanent — process-lifetime config, one-shot init, etc.; never to satisfy a borrow error).
5. **Owner.**

## Type punning and layout reinterpretation

Type punning is reading bytes through a reference to one type when they were written through (or are owned by) a different type. The five forms that appear in Rust:

1. `mem::transmute::<T, U>(value)` — by-value reinterpretation, must satisfy `size_of::<T>() == size_of::<U>()` at compile time.
2. `mem::transmute_copy::<T, U>(reference)` — reads `size_of::<U>()` bytes through a `&T`, NO compile-time size check.
3. `union` field reads — any field read returns the active variant's bytes interpreted as that field's type.
4. Pointer-cast + deref: `&*p.cast::<U>()` or `&*(p as *const U)` — the workspace's primary type-pun spelling.
5. Trait-mediated reinterpretation via `bytemuck::cast`, `bytemuck::from_bytes`, `zerocopy::transmute!`, `zerocopy::Ref:: new`, etc. — sound only if the `Pod` / `FromBytes` / `IntoBytes` / `Unaligned` trait bounds were derived correctly on a type with no padding, no non-pod fields, and a `#[repr(C)]` / `#[repr(transparent)]` layout.

Every type pun must satisfy all five preconditions below; missing any one is UB.

**Rule.** A type pun is sound only when:

1. **Size:** `size_of::<U>() <= size_of::<T>()` (or `== T` for `transmute`). For pointer-cast spellings the source allocation must contain at least `size_of::<U>()` initialised bytes reachable through the pointer.
2. **Alignment:** `align_of::<T>() >= align_of::<U>()`. A misaligned dereference of `&U` is instant UB on architectures that fault on it (ARM64 strict-alignment configurations) and silently miscompiled on others. **Verify with a `const _: () = { assert!(align_of::<T>() >= align_of::<U>()); }` block at the pun site so a future libc/platform layout change fails to compile.**
3. **Validity invariants:** every byte of the resulting `U` must be a valid `U` value. A `bool` with byte value 2, a `char` with high-surrogate code point, an enum with a discriminant not declared in its variants, a `NonZero*` with all zeros, a reference or `NonNull<T>` that is null — each is instant UB on the dereference, regardless of whether the bad value is read.
4. **Padding & initialisation:** if `T` contains padding bytes, reading them through `U` exposes uninit memory (UB). `#[repr(C)]` types still have inter-field padding when alignment forces it. The pun is sound only if `U`'s layout exactly mirrors the initialised bytes of `T`, padding included.
5. **Endianness & ABI:** when bytes cross a network or file boundary, little-endian / big-endian must match between writer and reader. Multi-byte integer fields use `from_ne_bytes` / `to_ne_bytes` (for in-memory shared state) or `from_le_bytes` / `to_le_bytes` / `from_be_bytes` / `to_be_bytes` (for protocols with a fixed byte order). Bare pointer-cast reinterpretation of a `[u8; 4]` into a `u32` is UB if alignment fails AND wrong-endian if not.

**Preferred shapes.** In order of preference:

1. **Safe by-value conversions.** `u32::from_ne_bytes([a, b, c, d])`, `i16::from_be_bytes(bytes)`, `f32::from_bits(u32_value)`. These are zero-cost, satisfy all five preconditions at the type level, and produce no UB regardless of input.

2. **`TryFrom` / explicit parsing.** When the source has a structured discriminant (TLV, length-prefixed, family-tag), use `match` + explicit field-by-field reads after validating the discriminant. The reads can be safe — pull primitive fields out of a `#[repr(C)]` struct one at a time, never via a wholesale reinterpretation.

3. **Family-tag-checked pun.** When POSIX/FFI dictates a union-style layout (`sockaddr_storage`, `msghdr.msg_control`), the `match family { AF_INET => &*(p.cast::<sockaddr_in>()), ... }` pattern is sound if (a) the tag is checked first, (b) a compile-time `assert!(align_of::<sockaddr_storage>() >= align_of::<sockaddr_in>())` block sits at the pun site, and (c) only well-defined primitive fields are read post-cast.

4. **`bytemuck::Pod` / `zerocopy::FromBytes`.** When parsing a high-volume on-wire format, trait-mediated reinterpretation is acceptable IF the derived bounds are reviewed and the source type has `#[repr(C, packed)]` with no padding AND the target type also has `#[repr(C, packed)]` AND every field type is itself `Pod` / `FromBytes`. The workspace does not currently depend on either crate; adoption requires a separate audit.

5. **`unsafe fn` + `# Safety`.** When none of the above apply (kernel ABI shims), wrap the pun in `unsafe fn` and document every precondition; the caller enters `unsafe { … }` with their own SAFETY comment listing the validity proof.

**Anti-patterns.**

- `mem::transmute::<&Foo, &Bar>(&foo)` — bypasses every type-level check Rust offers; almost always wrong outside of compiler internals. Use a safe `From` / `TryFrom` impl or explicit field reads instead.
- `mem::transmute_copy::<T, U>(&value)` where `size_of::<U>() != size_of::<T>()`. Silently reads past or short of `value` because the function does NOT enforce the size check. Use `transmute` (forced size check) if you really meant by-value reinterpretation.
- `union ByteSplit { whole: u32, parts: [u8; 4] }` — convenient but invites accidentally reading the wrong variant. Use `u32::to_ne_bytes()` and `u32::from_ne_bytes()` instead.
- `&*(buf.as_ptr() as *const Header)` where `buf: &[u8]` — UB on ARM64 if `buf` is not 4-byte aligned (which `&[u8]` doesn't guarantee). Use `ptr::read_unaligned` and copy out fields, or use `bytemuck::pod_read_unaligned` with verified bounds.
- A `bytemuck::Pod` impl on a type with one `bool` field — `bool` is not `Pod` (validity invariant: bytes must be 0 or 1), but `unsafe impl Pod` will compile. The pun then reads arbitrary bytes as `bool` → UB.
- `let x: u32 = unsafe { std::mem::transmute(*p) };` where `p: *const f32` — silently wrong on NaN/subnormal layout differences; use `f32::to_bits()` instead.

**Existing benign uses.** The audit recorded three production type-pun sites; each is allowlisted in `ci/unsafe-boundary-allowlist.toml` under the `cast then deref (type pun)` pattern with the validity argument:

| File | Conversion | Validity source |
|---|---|---|
| `crates/ripdpi-privileged-ops/src/linux/fd.rs` | `&sockaddr_storage` → `&sockaddr_in` / `&sockaddr_in6` | Family-tag match on `ss_family` + compile-time `assert!(align_of::<sockaddr_storage>() >= align_of::<sockaddr_in*>())` block in `fd.rs`; only well-defined primitive fields (`sin_addr.s_addr`, `sin_port`, `sin6_addr.s6_addr`, `sin6_port`) read post-cast. |
| `crates/ripdpi-vless/src/reality_hook.rs` | `*mut c_void` → `&RealityCallbackState` | The pointer was produced by `Box::into_raw(Box::new(RealityCallbackState { ... }))` (issue #15 ownership audit); the `RealityHookGuard` keeps the Box alive across all callback fires; Miri test under `--features miri-stubs` validates the round-trip (4/4 stages in `run-rust-miri.sh`). |

The workspace has ZERO production occurrences of `mem::transmute`, `mem::transmute_copy`, `union` declarations, `bytemuck::*`, or `zerocopy::*`. The CI scanner (`scripts/ci/check_unsafe_boundaries.py`) locks the workspace at that baseline via dedicated patterns; any new adoption requires an allowlist entry with the five-point checklist.

**Clippy lints.** The workspace's `[workspace.lints]` enforces:

- `useless_transmute = "deny"` — `transmute<T, T>` (identity).
- `transmute_ptr_to_ptr = "deny"` — `transmute<*const T, *const U>` that should be a plain cast (forces the cast form, which clippy then lints via the other rules below).
- `cast_ptr_alignment = "deny"` — `as *const V` / `.cast::<V>()` where `align_of::<V>()` is strictly greater than the source pointee's alignment. Catches the ARM64 UB risk (`&[u8]` → `&Header`) at compile time. Issue #23 audit confirmed zero findings; lint locks the baseline.
- `crosspointer_transmute = "deny"` — `transmute<*const T, *mut U>` (and inverse). Always expressible as a cast, so the transmute spelling is intentionally rejected.
- `transmute_undefined_repr = "warn"` — nursery lint that flags `mem::transmute<T, U>` where either type is missing `#[repr]` and the layout therefore isn't stable across rustc versions. Issue #23 audit confirmed zero findings; `warn` (escalated to error via CI's `-D warnings`) locks the baseline.
- `clippy::correctness` group (`deny`) covers `wrong_transmute`, `transmuting_null`, `unsound_collection_transmute`, `transmute_null_to_fn`, and the `transmute_int_to_*` family (bool / char / float / int) — all the compile-time-detectable transmute mistakes.
- `clippy::complexity` group (`warn`, escalated to error via CI `-D warnings`) covers `transmute_ptr_to_ref` and `transmutes_expressible_as_ptr_casts`.

Together this covers every transmute clippy lint that exists in stable rustc; the only remaining surface is the workspace's own custom scanner pattern `mem::transmute` (catches the spelling even if a future clippy bug regresses the existing lints) and the `cast then deref (type pun)` proximity detector (catches the pointer-cast spelling that clippy does NOT lint at all).

**Allowlist requirements.** Each `cast then deref (type pun)` / `union declaration` / `bytemuck::cast` / `zerocopy::transmute` / `zerocopy::IntoBytes::as_bytes` entry in `ci/unsafe-boundary-allowlist.toml` must state:

1. **Size argument** (`size_of::<U>() <= size_of::<T>()`, compile-time-asserted if the platform layout could change).
2. **Alignment argument** (`align_of::<T>() >= align_of::<U>()`, compile-time-asserted at the pun site).
3. **Validity invariants per field** (no `bool`/`char`/enum/ reference/`NonNull`/`NonZero` reads from punned bytes unless the bit pattern is statically known to be valid).
4. **Discriminant or provenance proof** (family-tag check before the pun; or the ownership chain that establishes the bytes were originally written through the target type).
5. **Owner.**

## FFI layout and ABI

Any struct, enum, or function signature that crosses a foreign- function boundary (`extern "C" { ... }` import, `extern "C" fn` / `extern "system" fn` export, function pointer typedef passed to C or Java) must have a stable, C-compatible layout. Rust's default struct/enum layout is intentionally unspecified — fields may be reordered, padding may be inserted, enum discriminants may not match what C expects. The five mechanisms below are the only sound spellings:

1. `#[repr(C)]` — fields laid out in declaration order with the same padding/alignment rules as a C struct. The default for any struct that crosses FFI.
2. `#[repr(transparent)]` — the struct is a single non-zero-sized field plus zero-sized markers. The ABI is exactly the inner type's ABI. Use for `NonNull<T>` newtypes and other zero-cost wrappers.
3. `#[repr(u8)]` / `#[repr(u16)]` / `#[repr(u32)]` / `#[repr(i8)]` etc. — enum with explicit integer representation. Required whenever an enum is passed across FFI; without it the discriminant size is implementation-defined and may differ from what C expects.
4. `#[repr(C, packed)]` (and `#[repr(packed)]` alone) — eliminates padding between fields. **Dangerous**: reading a packed field through `&field` materialises an underaligned reference (UB on ARM64 strict-alignment configurations). Only use with `addr_of!(s.field)` + `ptr::read_unaligned`. The scanner pattern `repr packed` flags every occurrence; workspace has zero today.
5. Opaque zero-sized handle: `#[repr(C)] struct Handle { _opaque: [u8; 0] }`. Used as the pointee type in `*const Handle` / `*mut Handle` for FFI handles whose internals are managed by the foreign library. `SslHandle` and `SslCtxHandle` in `ripdpi-vless/src/reality_hook.rs` are the workspace examples.

### Rule

A type that appears in an `extern "C"` / `extern "system"` declaration or definition MUST satisfy ALL of:

1. **Explicit `#[repr(...)]` attribute.** Default Rust layout is forbidden at FFI boundaries.
2. **Every field type is itself FFI-stable.** Primitives (`i*` / `u*` / `f32` / `f64` / `usize` / `isize`), `*const T` / `*mut T` raw pointers, `Option<NonNull<T>>` / `Option<NonZero*>` / `Option<fn(...) -> ...>` (the null-pointer-optimisation cases), `repr(C)` arrays of FFI-stable types, and other explicitly-repr structs/enums. `bool` is implementation-defined (matches C `_Bool` in practice but the Rust Reference does not guarantee it) and is rejected by the scanner. The full rejection list (issue #25): `&str`, `String`, `Vec<T>`, `Box<dyn Trait>`, `(T, U)` Rust tuples (no layout guarantee), `&[T]` / `&mut [T]` slices (fat pointer = ptr+len), `&dyn Trait` / `&mut dyn Trait` (trait-object fat pointer = ptr+vtable), `Result<T, E>` (Rust enum without explicit `#[repr]`), `Option<T>` for non-NonNull `T`, `impl Trait` (compiler-internal opaque type), and any `enum` declared without `#[repr(u*)]` / `#[repr(i*)]`. All are unconditionally rejected — their layouts are unspecified, they may carry Drop logic the C side cannot invoke, and they may embed vtable pointers or discriminants of unspecified size. The replacement shapes per case: `&str` → `*const u8` + `usize` length; `Vec<T>` → caller-owned `*mut T` + `usize` length + explicit free-function export; `Result<T, E>` → `repr(C)` tagged union OR negative-`isize` error code convention; `(T, U)` → `#[repr(C)] struct Pair { a: T, b: U }`; `&dyn Trait` → opaque handle + vtable of `extern "C" fn` pointers; `impl Trait` → return a concrete type (typically a `repr(C)` struct or an opaque handle).
3. **No alignment hazards.** `#[repr(packed)]` is forbidden unless every field access goes through `addr_of!` + unaligned read/write. The default `#[repr(C)]` layout is properly aligned by construction.
4. **`#[no_mangle]` always paired with `extern "<abi>"`** in the same source proximity. A `#[no_mangle]` on a default-Rust-ABI function exports an unstable-ABI symbol — guaranteed mismatch at the call site.
5. **Compile-time layout assertion at every kernel/library ABI boundary.** Every struct that mirrors a kernel `struct` (sockaddr, tcp_info, tcp_repair_*) or a foreign library binary layout MUST have a sibling `const _: () = { assert!( size_of::<T>() == EXPECTED); assert!(align_of::<T>() == EXPECTED); };` block. Any future field reorder/insert/delete fails to compile before the FFI buffer goes out of sync.

### Preferred shapes

1. **No FFI surface.** Wrap the foreign call in a Rust-only safe API that takes/returns owned Rust types; never expose the `extern "C"` signature beyond a thin shim module.

2. **Opaque handles.** `#[repr(C)] struct Handle { _opaque: [u8; 0] }` for any FFI handle whose internals are foreign-managed. The Rust side carries `*mut Handle` and never reads/writes through it directly.

3. **Compile-time-asserted `#[repr(C)]` mirror struct.** When the FFI surface is a value type (kernel sockopt buffer, BoringSSL constants struct), declare it with `#[repr(C)]`, set the field types to FFI-stable primitives, and add a sibling `const _: () = { ... };` block. The four workspace examples are `LinuxTcpInfo`, `TcpMd5Sig`, `TcpRepairWindow`, and `TcpRepairOpt` in `ripdpi-privileged-ops/src/linux/`.

4. **`#[repr(transparent)]` newtypes.** For zero-cost type safety (`NetworkScope(SHA256Hash)`, `OwnedFd(i32)`), use `transparent`. The ABI matches the inner field, so the FFI side sees the underlying primitive.

### Anti-patterns

- `pub struct Header { ... }` (no `#[repr]`) passed as `*const Header` to a C library — layout is unspecified; any compiler upgrade can break the contract.
- `extern "C" fn callback(arg: bool) -> i32` — `bool` is implementation-defined at the FFI level; use `u8` with explicit 0/1 convention.
- `extern "C" fn handle(msg: &str) -> i32` — `&str` is a fat pointer (`(ptr, len)`); C sees neither the pointer nor the length correctly.
- `extern "C" fn cb(buf: Vec<u8>) {}` — `Vec` is three words (ptr/len/cap) AND carries a Drop impl that the C side cannot invoke; the allocation leaks at best, UB at worst.
- `#[no_mangle] pub fn export() { ... }` (no `extern`) — exports a Rust-ABI symbol under a fixed name; any C caller gets the wrong calling convention.
- `#[repr(packed)] struct Foo { x: u32 }` followed by `&foo.x` — materialises an underaligned `&u32` reference; instant UB on ARM64 strict-alignment.
- Enum without explicit `#[repr(u*)]` / `#[repr(i*)]` passed across FFI — discriminant size is implementation-defined.

### Existing benign uses

The audit recorded all FFI-bearing types in the workspace; each is correctly repr-attributed and compile-time-asserted where the kernel ABI is the contract:

| File | Type | Layout |
|---|---|---|
| `crates/ripdpi-vless/src/reality_hook.rs` | `SslCtxHandle`, `SslHandle` | `#[repr(C)] struct H { _opaque: [u8; 0] }` opaque handle |
| `crates/ripdpi-privileged-ops/src/linux/tcp_info.rs` | `LinuxTcpInfo` | `#[repr(C)]` prefix of kernel `tcp_info`; compile-time `assert!(size_of >= 148)` + alignment relation |
| `crates/ripdpi-privileged-ops/src/linux/socket_options.rs` | `TcpMd5Sig` | `#[repr(C)]` matching Linux `tcp_md5sig`; compile-time `assert!(size_of >= sockaddr_storage + 88)` + alignment matches sockaddr_storage |
| `crates/ripdpi-privileged-ops/src/linux/tcp_repair/sockopt.rs` | `TcpRepairWindow`, `TcpRepairOpt` | `#[repr(C)]` matching Linux `tcp_repair_window` / `tcp_repair_opt`; compile-time `assert!(size_of == 20)` and `assert!(size_of == 8)` respectively |
| `crates/ripdpi-packets/src/classify.rs` | `ProtocolId` | `#[repr(u8)]` — does NOT cross FFI; the repr is for `enum_map::Enum` derive efficiency |

The four JNI bridge crates (`ripdpi-android`, `ripdpi-relay-android`, `ripdpi-warp-android`, `ripdpi-tunnel-android`) carry `#[allow(improper_ctypes_definitions)]` at module level because the `jni` crate's `EnvUnowned<'_>` and `JObject<'_>` types use lifetime parameters that rustc's CType check cannot prove are FFI-safe. The actual layout IS FFI-safe (newtypes around raw pointers), validated by the `jni` crate's own test suite.

### CI surface

- **Scanner** (`scripts/ci/check_unsafe_boundaries.py`): - `repr packed` — flags any `#[repr(packed)]` / `#[repr(packed(N))]` / `#[repr(C, packed)]`. - `extern fn non-FFI type` — flags `extern "C" fn` / `extern "system" fn` signatures with `bool`, `&str`, `String`, `Vec<T>`, `Box<dyn ...>`, `&[T]`, or `Option<T>` for non-NonNull/non-fn `T`. - `no_mangle without extern ABI` — proximity detector (3-line window) that flags `#[no_mangle]` not paired with `extern "<abi>"` immediately below. - `bindgen invocation` — flags any `bindgen::Builder` / `cbindgen::Builder` / `cbindgen::Config` / `cbindgen::Language` call. Workspace has zero occurrences; future adoption requires a committed binding snapshot + drift-detection CI step.
- **FFI header hygiene script** (`scripts/ci/check_ffi_headers.py`): separate CI step that enforces three properties: (a) no `bindgen` / `cbindgen` / `autocxx` / `safer-ffi` / `cxx` / `cxx-build` / `cxx-gen` deps in any workspace `Cargo.toml`; (b) no `.h` / `.hpp` / `.hxx` headers committed under `native/rust/` outside `vendor/` (BoringSSL); (c) no auto- generated Rust files matching `bindings.rs` / `bindgen_bindings.rs` / `generated.rs` / `auto_bindings.rs`. Together this constitutes the workspace's "header check" — confirming the FFI surface is hand-written and reviewable in PRs rather than auto-generated.
- **Rust lints** (workspace `[workspace.lints.rust]`): - `improper_ctypes = "warn"` — `extern "C" { fn(...) -> T; }` declarations whose parameter/return types are not FFI-stable. - `improper_ctypes_definitions = "warn"` — same lint for `extern "C" fn` / `extern "system" fn` definitions (functions we export). Four JNI bridge crates carry `#[allow(improper_ctypes_definitions)]` because the `jni` crate's `EnvUnowned<'_>` / `JObject<'_>` types use lifetime parameters that rustc's CType check cannot prove FFI-safe. The scanner pattern `extern fn non-FFI type` provides the cross-check that the allowance does not let a genuine FFI-unstable type slip through.
- **Compile-time layout asserts** at each FFI struct definition site: `const _: () = { assert!(size_of::<T>() == EXPECTED); assert!(align_of::<T>() == EXPECTED); };` blocks fail to build on any field reorder/insert/delete. The four kernel-ABI structs (`LinuxTcpInfo`, `TcpMd5Sig`, `TcpRepairWindow`, `TcpRepairOpt`) and the two opaque BoringSSL handles (`SslHandle`, `SslCtxHandle` — both asserted zero-sized) all carry these.

### Allowlist requirements

Each `repr packed` / `extern fn non-FFI type` / `no_mangle without extern ABI` entry in `ci/unsafe-boundary-allowlist.toml` must state:

1. **ABI mapping** (which foreign type / function this matches; ideally with a link to the C/Java header or the kernel `include/uapi/linux/<file>.h` source).
2. **Layout proof** (the compile-time `const _: () = assert!(...)` block protecting the struct's size and alignment; or the foreign-library binary contract that fixes the layout).
3. **Field type stability** (each field is a primitive, raw pointer, opaque handle, or transitively repr-stable type).
4. **Owner.**

## Callback registration with context pointers

A common FFI pattern: register an `extern "C" fn callback(arg: *mut c_void)` with a foreign library and pass a Rust-owned context pointer through `arg`. The library calls the callback at some future time(s), dereferences `arg`, and the Rust side recovers the typed context. The hazard is straightforward — if the Rust context is freed before the LAST callback fires, the next dereference is UAF.

The workspace has ONE production callback-with-context system: `RealityHookGuard` in `ripdpi-vless/src/reality_hook.rs`. The following pattern is the canonical sound shape — every new callback-registration site MUST satisfy all four rules.

### Rule

1. **Context allocation is `Box::into_raw`** (or a typed wrapper thereof — `Arc::into_raw`, `RegisteredBufferPool`, etc.). The allocator must be Rust's global allocator on BOTH sides (alloc with `Box::new`, free with `Box::from_raw`). NEVER mix `libc::malloc` / `libc::free` with `Box::*_raw` (see § "Allocator mismatch across FFI").

2. **The context pointer is owned by a RAII guard struct** named `*Guard`, `*Registration`, `*Hook`, or `*Slot` whose Drop impl reclaims the box. The guard is move-only (no `Clone`/`Copy`) so safe code cannot duplicate the owning handle. See § "`Clone` on owner-named types" and "`Copy` on owner-named types" for the scanner enforcement.

3. **The foreign library cannot fire the callback after Drop.** This is the load-bearing invariant; there are three sound ways to enforce it:

   - **Per-registration foreign resource.** Each callback gets its own foreign object (SSL_CTX, listener, etc.) that is dropped before the guard. After the foreign object drops, no further callback invocations are possible. This is what `RealityHookGuard` uses — each Reality connect builds a fresh `SSL_CTX` (per-connection), so the CTX-bound callback slot is single-use. The guard's Drop runs after the handshake's `connect().await` completes, by which point BoringSSL guarantees no further `client_hello_cb` fires.

   - **Explicit unregister in Drop.** The guard's Drop impl calls the foreign library's `unregister` / `set_cb(NULL)` function BEFORE reclaiming the box. This requires the foreign API to expose an unregister hook; not always available.

   - **Synchronous join.** The guard's Drop impl blocks until all pending callback invocations complete. Requires the foreign library to expose a "wait for in-flight callbacks to drain" primitive. Rare.

4. **The callback body defends against null `arg`.** Even with correct registration discipline, a defensive `if arg.is_null() { return error_code; }` at the top of the callback prevents UB in the (impossible-by-contract) case where the foreign library invokes the callback with a different arg. Cost: one branch.

5. **The callback is wrapped in `std::panic::catch_unwind`** so a Rust panic does not unwind across the `extern "C"` boundary (which is UB). On panic, latch a failure flag on the context for post-handshake inspection.

### Preferred shapes

1. **Per-registration foreign resource (workspace canonical).** `RealityHookGuard` is the reference implementation. Each Reality connect: - allocates `Box<RealityCallbackState>` (server pubkey + short ID + AtomicBool failure latch), - `Box::into_raw` to get `*mut RealityCallbackState`, - `SSL_CTX_set_client_hello_cb(ctx, cb, state_ptr.cast::<c_void>())`, - returns `RealityHookGuard { state_ptr }`, - the caller binds the guard to a local that outlives the `connect().await` call, - guard Drop calls `Box::from_raw(state_ptr)` to reclaim.

   Soundness: each connect builds a fresh `SSL_CTX` (issue #28 audit verified, `reality.rs` line 43-54), so the callback slot is single-use per connection. No callback can fire after `connect().await` returns because BoringSSL only calls `client_hello_cb` during `ssl_add_client_hello`.

2. **Explicit unregister in Drop.** When the foreign library exposes an unregister hook (e.g. `nl80211_remove_callback`, `libusb_set_pollfd_notifiers(NULL, NULL)`), the guard's Drop calls it BEFORE `Box::from_raw`. Cleaner contract but requires foreign-API support.

3. **`Arc<T>` + Weak.** If the callback may fire from multiple threads or contexts, wrap the context in `Arc<T>` and pass a `Weak<T>::into_raw()` to the foreign side. The callback recovers the `Weak` and `upgrade()`s; if the strong refs are gone (guard dropped), the callback returns gracefully. Costs a refcount but eliminates the lifetime contract.

### Anti-patterns

- `static mut STATE: Option<Box<Context>> = None;` filled at registration time and cleared at unregister. Race condition between the callback's read and the unregister's write; UB on any concurrent foreign-library use.
- `Box::leak(Box::new(state))` for callback context. The leaked memory cannot be reclaimed; if the registration is repeatable, this is an unbounded memory leak. See § "Lifetime extension" for the `Box::leak` scanner pattern.
- `Box::into_raw(state)` with no RAII guard. The matching `Box::from_raw` is in scope but a panic or early return leaks the box. Must be paired with `Drop`.
- `Box::into_raw` for context + `libc::free` to reclaim. The global allocator and `libc::free` are not the same heap. See § "Allocator mismatch across FFI".
- Callback body that dereferences `arg` without a null check. One mistake in the registration discipline → instant UB.
- Callback body that allocates / takes a mutex / does anything but trivial work. Foreign libraries may invoke the callback from a signal handler or with internal locks held; complex work can deadlock or violate async-signal-safety.
- Storing a `&'static Mutex<Context>` in the foreign library and expecting `'static` to bind correctly. The Rust borrow checker can't see across the FFI boundary; `'static` here is the LIE-by-omission shape — use `Arc` instead.

### Existing benign uses

| File | Pattern | Soundness mechanism |
|---|---|---|
| `crates/ripdpi-vless/src/reality_hook.rs` | `Box::into_raw(RealityCallbackState)` → `SSL_CTX_set_client_hello_cb` → `RealityHookGuard::Drop` reclaims | Per-connection SSL_CTX (single-use callback slot) + RAII guard outlives `connect().await` + null-arg defensive check + `catch_unwind` panic trap. Miri-validated via `--features miri-stubs` (4-stage CI Miri job). |

### CI surface

- **Scanner pattern `Box::into_raw`** (existing from issue #15): every occurrence requires an allowlist entry naming the matching `Box::from_raw` site AND the ownership-transfer contract. The single workspace site is `reality_hook.rs:219` (Reality callback state install) plus three test-mode sites in the `soundness-canaries` crate (all allowlisted).
- **Scanner pattern `derive Clone on owner-named type`** (existing from issue #13): prevents accidental duplication of guard handles that would lead to double-free.
- **Scanner pattern `derive Copy on owner-named type`** (existing from issue #14): same for `Copy`.
- **Miri test stage** `ripdpi-vless --features miri-stubs reality_hook` in `scripts/ci/run-rust-miri.sh` exercises the install / callback / drop cycle end-to-end without linking BoringSSL.
- **Production callback null-arg check** at `reality_hook.rs:340` (test `callback_inner_rejects_null_arg`) proves the defensive null check is in place.

### Allowlist requirements

Each new callback-registration `Box::into_raw` allowlist entry must state:

1. **Lifetime invariant** (which of the three Rule #3 mechanisms ensures no callback fires after Drop: per-registration foreign resource, explicit unregister, or synchronous join).
2. **Defensive null-arg check** (line number of the callback's `if arg.is_null()` check).
3. **Panic isolation** (line number of the callback's `std::panic::catch_unwind`).
4. **Owner.**

## Callback reentrancy

A user-supplied closure (observer, hook, listener, callback) must never be invoked while the surrounding object holds a synchronisation primitive lock OR is in a temporary inconsistent state. Both invariants protect against the same class of bug: the callback re-enters the same API from inside its own body and finds either a deadlocked lock or torn state.

Issue #29 found and fixed exactly this bug in `ripdpi-tunnel-core/src/stats/observer.rs`: `notify_dns_latency` previously held the `dns_latency_observer` Mutex across the user callback invocation; re-entering `set_dns_latency_observer` from inside the callback would deadlock.

### Rule

A function that invokes a user-supplied closure (`Box<dyn Fn>`, `Arc<dyn Fn>`, `impl Fn(...)`, etc.) MUST satisfy ALL of:

1. **No synchronisation primitive held during the invocation.** Clone an owning handle (`Arc`) inside the lock, release the lock, then invoke the closure outside the locked region. The Arc clone is O(1) (one atomic refcount bump). This is the workspace pattern in `notify_dns_latency` post-fix.

2. **No temporary inconsistent state visible to the callback.** If the API mutates a field before invoking the callback (e.g. "extract from list, invoke, re-insert"), the extracted slot must either be filled with a sentinel value (typestate) before the callback fires, OR the operation must be transactional (commit only after the callback returns).

3. **Reentrant calls back into the same API must be sound.** The callback body may legitimately call the same API again to register a replacement, query state, etc. The API design must support this — typically by being fully re-entrant (no locks held) or by detecting re-entry and queueing the second call.

4. **Callback body must not assume a particular caller state.** Documented contracts that say "the callback fires while the object is locked" are anti-patterns — they push the reentrancy hazard to every callback author.

### Preferred shapes

1. **Clone-out-of-lock + invoke-outside-lock** (workspace canonical, post-issue-#29). The function locks the synchronisation primitive briefly, clones the `Arc<dyn Fn>` holding the callback, releases the lock, and invokes the cloned callback. The lock window is O(1). See `notify_dns_latency` in `ripdpi-tunnel-core/src/stats/observer.rs`.

2. **RAII-restored state via stack discipline.** When the callback runs in a context where some temporary state must be installed (e.g. "set CURRENT_PLATFORM to P, run closure, restore previous"), use a Drop-guard pattern so the state is restored even if the closure panics. The workspace example is `with_tcp_desync_platform` in `ripdpi-desync-runtime/src/platform/registry.rs` — `Restore` guard re-stores the previous `CURRENT_PLATFORM` on Drop, surviving panics and supporting nested reentry correctly.

3. **Deferred action via channel.** When the callback's work must be deferred to a different thread or until the current operation completes, the callback enqueues a message and the consumer thread invokes the actual work later. Decouples the callback latency from the producer's critical path.

4. **Restricted callback capabilities.** When the callback must NOT re-enter the same API, give it a restricted capability handle (e.g. `&CallbackView` exposing only read methods) rather than a full `&Object`. Prevents reentry at the type level.

### Anti-patterns

- `let g = self.observer.lock().unwrap(); (g)(event);` — lock held across callback. Caught by scanner pattern `lock held across callback`.
- `let g = self.handlers.write().unwrap(); for h in g.iter() { h(event); }` — RwLock write lock held across multiple callback invocations. Catastrophic deadlock surface.
- `self.state = State::Invoking; (cb)(arg); self.state = State::Idle;` — temporary inconsistent state visible to callback. Use a typestate enum or transactional commit instead.
- Callback body that recursively calls the same API without bounded recursion. Unbounded reentry → stack overflow.
- Documenting "the callback runs under the lock" as the invariant. Push the lock OUT of the public API instead.

### Existing benign uses

| File | Pattern | Soundness mechanism |
|---|---|---|
| `ripdpi-tunnel-core/src/stats/observer.rs` | `Mutex<Option<Arc<dyn Fn>>>` observer slot | Issue #29 fix: clone Arc inside lock, release lock, invoke observer outside. Test `dns_latency_observer_reentry_does_not_deadlock` exercises the reentry path. |
| `ripdpi-desync-runtime/src/platform/registry.rs` | `thread_local!{RefCell<Option<*const dyn TcpDesyncPlatform>>}` + RAII restore | Single-threaded RefCell with stack-disciplined `Restore` guard; nested `with_tcp_desync_platform` calls work correctly via Drop ordering. |
| `ripdpi-vless/src/reality_hook.rs` | BoringSSL `client_hello_cb` with `Box::into_raw` userdata | Per-connection SSL_CTX (single-use callback slot); guard's Drop reclaims box; BoringSSL single-threaded `ssl_add_client_hello` contract. Audited under issue #28. |

### CI surface

- **Scanner pattern `lock held across callback`** flags single-line occurrences of `.lock()` / `.read()` / `.write()` / `.borrow()` / `.borrow_mut()` followed by a call expression matching common callback identifier conventions (`observer`, `callback`, `cb`, `handler`, `hook`, `listener`, `notify`, `emit`, `on_*`). Workspace currently has zero findings. Multi-line cases are not caught by this regex and rely on manual review.
- **Regression test `dns_latency_observer_reentry_does_not_deadlock`** in `stats::observer::tests` exercises the canonical reentry pattern: an observer that, when fired, replaces itself with a different observer (re-locking the same Mutex from inside the callback). Pre-issue-#29: deadlock. Post-fix: passes.

### Allowlist requirements

Each `lock held across callback` allowlist entry must state:

1. **Reentrancy contract** (whether the callback may re-enter the same API; if yes, why it is sound).
2. **Lock-window bound** (how long the synchronisation primitive is held during the callback — e.g. "single Arc clone, O(1)").
3. **Panic safety** (whether the callback can panic without leaving the object in an inconsistent state).
4. **Owner.**

## Drop and raw back-pointers

A `Drop` implementation runs at a point the language otherwise hides — when a `Box`, a local binding, a struct field, or a stack frame goes out of scope. A struct that stores a raw pointer (`*const T` / `*mut T` / `NonNull<T>`) to its parent, owner, container, or any object that does NOT own the struct is at elevated risk during Drop, because the pointee may have already been dropped, partially dropped, or be in the middle of dropping the struct itself. The pattern is the canonical bug in C and C++ intrusive data structures (linked lists, arenas, observer trees); Rust's borrow checker does not catch it because the back-pointer is raw and the dereference is in `unsafe { ... }`.

### Rule

A struct field whose name matches a back-pointer convention (`parent`, `owner`, `container`, `list`, `prev`, `next`, `head`, `tail`, `back`, `back_ptr`, `backptr`, `registry`) and whose type is a raw pointer or `NonNull<T>` is FORBIDDEN in production crates. The struct must instead carry one of:

- a lifetime-bound shared reference `&'a T` (the borrow checker enforces the parent outlives every child),
- an `Arc<T>` (the parent's refcount keeps it alive as long as any child exists),
- a `Weak<T>` (the child observes parent liveness via `upgrade()` before any dereference — including the dereference inside `Drop`),
- an opaque integer handle indexed through a separate registry (so the lookup at Drop time fails cleanly if the parent is gone, rather than UB).

A `Drop` implementation MUST NOT dereference a raw back-pointer under any circumstance. If the cleanup needs to notify a parent (e.g. decrement a refcount, remove from a list), the notification path must go through one of the safe shapes above.

### Preferred shapes

In order of preference for a child-of-parent relationship where the child's Drop must notify the parent:

1. **Lifetime-bound `&'a Parent`**. The child cannot outlive the parent because the borrow check forbids it. The Drop impl reads `self.parent.method()` through a safe `&Parent` reference. Examples in the workspace: `ripdpi-io-uring/src/bufpool.rs::BufferHandle<'pool>` returns its slot to `RegisteredBufferPool` via `&'pool` borrow; `ripdpi-android-proxy-adapter/src/lifecycle.rs::IdleGuard<'_>` resets `ProxySessionState` via `&'a Mutex<...>`; `ripdpi-desync-runtime/src/platform/registry.rs::Restore<'_>` restores a thread-local via `&'a RefCell<...>`.

2. **`Arc<Parent>`**. The parent stays alive for the child's entire lifetime (including Drop) because the strong count contains the child's reference. The Drop impl reads `self.parent.method()` through `Arc::deref`. Examples: `ripdpi-proxy-runtime/src/runtime/state/listener.rs:: ClientSlotGuard` decrements a shared `Arc<AtomicUsize>`; `ripdpi-relay-mux/src/lease.rs::LeaseGuard<S>` releases leases through `Arc<Mutex<RelayMuxState<S>>>`.

3. **`Weak<Parent>`** when the parent's lifetime is genuinely independent of the child. The Drop impl calls `if let Some(parent) = self.parent.upgrade() { ... }` — if the parent is gone, the cleanup is a no-op. Suitable for observer/listener patterns where the listener can outlive the observable.

4. **Opaque handle + registry lookup**. The child stores a `u64` / `NonZeroU64` handle; the Drop impl looks the parent up in a shared registry (`HandleRegistry`, `DashMap`, etc.) and bails out cleanly if the registry has already evicted it.

### Anti-patterns

- A struct field `parent: *mut Parent` whose Drop impl does `unsafe { (*self.parent).method() }`. The dereference is unchecked UB if the parent has already been dropped (Drop order is implementation-defined for sibling fields and for unrelated heap allocations; any reasoning about "parent outlives child" must be a Rust-level guarantee, not a comment).
- An intrusive linked list node `next: *mut Node` / `prev: *mut Node` whose Drop impl unlinks itself by writing through `prev` and `next`. The pattern is sound only with a pinning discipline, a head-anchor invariant, and a separate audit. The workspace has no such structure today; adoption requires a dedicated soundness review.
- An "arena" struct that hands out `&'static T` references derived from a raw pointer into its own storage. The `'static` lifetime laundering hides drop-order coupling and is rejected by Issue #25 (unstable-layout-across-FFI) plus the present rule.
- A `NonNull<Parent>` field where the safety comment claims "the parent always outlives this struct because the parent drops us first". Drop order between sibling fields of a containing struct is well-defined (declaration order), but drop order between unrelated heap allocations is not. The comment is a load-bearing oral tradition; the rule requires the lifetime to be in the type system.

### Existing benign uses

Audit log: the workspace has ZERO production occurrences of a raw back-pointer field. Every Drop impl that notifies a parent uses one of the four safe shapes above. The audit covered:

| Drop impl | Parent relationship | Safe shape used |
|---|---|---|
| `BufferHandle<'pool>` | RegisteredBufferPool returns slot to free-list | Lifetime-bound `&'pool` |
| `PendingBuffer<'pool>` | RegisteredBufferPool returns slot | Lifetime-bound `&'pool` |
| `IdleGuard<'_>` | ProxySessionState reset to Idle | Lifetime-bound `&'a Mutex<...>` |
| `Restore<'_>` | Thread-local slot restored | Lifetime-bound `&'a RefCell<...>` |
| `ClientSlotGuard` | Shared AtomicUsize decremented | `Arc<AtomicUsize>` |
| `LeaseGuard<S>` | RelayMuxState lease released | `Arc<Mutex<RelayMuxState<S>>>` |
| `MappedFile` / `MmapRegion` | No parent — sole-owner of mmap region | `NonNull<u8>` (owned, not back-pointer) |
| `RealityHookGuard` | No parent — owns `Box::into_raw` state | `*mut RealityCallbackState` (owned, not back-pointer) |
| `ScopedHandle<T, F>` | No parent — owns pointee via `F::free` | `NonNull<T>` (owned, not back-pointer) |

The three "owned, not back-pointer" entries are flagged by adjacent scanner patterns (`NonNull in public fn`, `Box::into_raw` / `Box::from_raw`) and allowlisted with the sole-owner justification.

### CI surface

`scripts/ci/check_unsafe_boundaries.py` runs the `raw back-pointer field` scan on every PR. It matches field declarations whose identifier is one of the back-pointer naming-convention set listed in the Rule above AND whose type starts with `NonNull<` or `*const` / `*mut`. The scan is intentionally name-based and structural: the alternative — a proximity scan that links a raw pointer field to its Drop impl — misses the "field present but Drop hasn't been written yet" window, which is exactly when the bug class is easiest to introduce.

Regression tests in `scripts/ci/tests/test_check_unsafe_boundaries.py` exercise both the positive shape (`parent: *mut Parent`, `prev: NonNull<Node>`) and the safe shapes (`parent: &'a Parent`, `parent: Arc<Parent>`, `parent: Weak<Parent>`) to ensure the regex does not over-fire on legitimate uses of the same names.

### Allowlist requirements

Each `raw back-pointer field` allowlist entry must state:

1. **Drop-order proof** — which side drops first and why (lifetime, refcount, registry).
2. **Liveness witness** — what mechanism, at the point of Drop, establishes the pointee is still a valid `T` (lifetime binding, atomic flag, lock, intrusive list anchor).
3. **Test coverage** — the name of the test that exercises both parent-then-child AND child-then-parent drop paths.
4. **Owner.**

The first three fields are deliberately stricter than the generic allowlist preconditions: a back-pointer bug class is load-bearing for many small UAFs at once (every Drop along the parent chain), so the review burden is intentionally higher.

## Partial initialisation and panic safety

A staged initialisation that writes some elements of a buffer, some fields of a struct, or some entries of a collection BEFORE publishing ownership (via `set_len`, `assume_init`, or a struct literal) is at elevated risk if the surrounding function can panic or return `Err` between the first write and the final commit. Two failure modes:

1. **Leak.** The partially-initialised prefix contains `T: Drop` values that have logically been written. Dropping the surrounding `MaybeUninit<T>` / `[MaybeUninit<T>; N]` / `Vec<MaybeUninit<T>>` does NOT call `T::drop`, so each already-written `T` leaks its owned resources (file descriptors, heap allocations, mutex guards, etc.).
2. **UB on continuation.** Code that proceeds to call `assume_init` or `set_len(written)` after the panic-recovery path treats uninit bytes as valid `T` values. Any subsequent read materialises an invalid `T` — instant UB.

The bug class only matters for `T: Drop`. For `T: !Drop` (integers, `[u8; N]`, `#[repr(C)]` POD structs without Drop- bearing fields, function pointers), `MaybeUninit<T>` is itself `!Drop` for its uninit contents, so a panic that abandons the prefix is sound by definition.

### Rule

A safe function that returns `Result` or can panic MUST NOT combine `MaybeUninit::write` / `ptr::write` / `addr_of_mut!` + field-by-field assignment with subsequent `assume_init*` / `set_len` on a `T: Drop` payload UNLESS one of:

- The full initialisation is committed by an infallible expression (no `?`, no panic source, no fallible call between the first write and the commit point), OR
- The function maintains an **initialisation guard**: a private RAII struct that owns the partial prefix, drops the written elements in its `Drop` impl, and is `mem::forget`-en or `into_inner`'d only on the commit path, OR
- The initialisation uses a safe API (`Vec::push`, `Vec::extend_from_slice`, `Vec::with_capacity` + `spare_capacity_mut` with `T: !Drop`) that handles the panic-leak class internally.

`Vec::set_len` is `unsafe fn` precisely because it publishes the prefix `[0, n)` as initialised; calling it BEFORE all `[0, n)` slots have been written with valid `T` values is the canonical bug. The existing `unsafe Vec::set_len` scanner pattern catches the inline `unsafe { v.set_len(n) }` shape; this rule adds the upstream write sites that produce the initialised prefix.

### Preferred shapes

In order of preference for a fallible initialisation that fills `n` slots:

1. **Safe `Vec::push` / `Vec::extend_from_slice` / `Vec::extend(iter)`.** Each element is moved into the Vec by value; if the iterator panics mid-way, the `Vec`'s Drop runs on the prefix already pushed (which contains valid `T` values, hence `T::drop` runs exactly once per element). No `unsafe` needed. Default to this for every case that can be expressed as "iterate and push".

2. **`Vec::with_capacity` + `spare_capacity_mut` + `set_len`, restricted to `T: !Drop`.** The `T: !Drop` bound makes panic-leak vacuously safe: there are no resources to leak. This is the canonical workspace pattern, documented by the reference test in `soundness-canaries/src/lib.rs::tests` (with `T = u8`). New occurrences must add a `static_assertions::assert_not_impl_any!(T, Drop)` or an equivalent compile-time check naming the `T: !Drop` precondition.

3. **All-or-nothing init using infallible writes.** Collect into a temporary (`Vec<T>`, array literal, struct literal) using safe operations; commit the result in a single infallible move into the final destination. No partial-init state exists, no panic can interrupt the commit.

4. **RAII initialisation guard.** A private newtype that owns a `&mut [MaybeUninit<T>]` plus a `written: usize` counter, whose `Drop` impl runs `unsafe { ptr::drop_in_place(slice::from_raw_parts_mut( slot.as_mut_ptr() as *mut T, self.written)) }` on the prefix. Commit by replacing the guard with the final value via `mem::forget`. This shape is reserved for cases where shapes 1–3 are not possible (FFI fill patterns with a `T: Drop` payload).

### Anti-patterns

- A `fn new(...) -> io::Result<Self>` that calls `MaybeUninit::write(&mut slot, value)` followed by a fallible syscall (`libc::ioctl(...)? -> err`) before commit. The Err branch returns without dropping `value`, leaking its resources. The fix: do the syscall FIRST, validate, then commit the initialisation infallibly.
- A struct-by-struct staged init using `addr_of_mut!` with a fallible expression for one field. The fix: build the contents in local variables (safe construction), then assign the struct literal as a single infallible move.
- `unsafe { v.set_len(initialised + 1) }` inside a loop where the per-iteration writer can panic. The prefix grows but the invariant "all of `[0, len)` is valid `T`" is violated the moment the writer panics. Use `Vec::push` (which only commits the length after the write returns) instead.

### Existing benign uses

Audit log: the workspace has ONE production `MaybeUninit` site plus ONE production `set_len` site, both panic-safe by construction:

| File | Pattern | Why panic-safe |
|---|---|---|
| `crates/ripdpi-privileged-ops/.../icmp_wrapped_udp.rs:27` | `[MaybeUninit<u8>; 8192]` stack buffer | `u8: !Drop`, so panic leaves no resources to leak. The slice constructed in the SAFETY block is sized to `received` (the contract-fulfilled prefix). |
| `crates/soundness-canaries/src/lib.rs::tests` (test code) | `Vec<u8>::with_capacity + spare_capacity_mut + set_len(n)` | `u8: !Drop`, plus test isolation. Documents the canonical workspace shape. |
| `crates/ripdpi-io-uring/src/bufpool.rs::RegisteredBufferPool::new` | `Vec::collect` + fallible `register_buffers(&iovecs)?` | All buffers are FULLY initialised with `vec![0u8; ...]` BEFORE the fallible syscall; Err branch drops them via standard `Vec<Box<[u8]>>` Drop. No partial-init window. |
| `crates/ripdpi-privileged-ops/.../mmap_region.rs::MmapRegion::new` | Single fallible step (`mmap_anonymous`) | Err branch: nothing has been allocated yet. Ok branch: the `NonNull<u8>` is moved into `Self` infallibly. No partial-init window. |

The `BufferHandle::set_len` and `File::set_len` callers in the workspace are safe inherent methods on those types, not the `unsafe Vec::set_len` form. Scanner excludes them.

### CI surface

`scripts/ci/check_unsafe_boundaries.py` runs three scans on every PR for the upstream-write sites of the partial-init class:

- **`MaybeUninit::write`** — qualified-path form (`MaybeUninit::write(...)` / `MaybeUninit::<T>::write(...)` / `std::mem::MaybeUninit::write(...)`). The bare method-call spelling `slot.write(v)` is NOT flagged because `.write(` is ambiguous with the `Write` trait.
- **`ptr::write`** — bare form only. The sibling APIs `ptr::write_bytes`, `ptr::write_volatile`, `ptr::write_unaligned` have their own audit-class and are excluded via a `(?!_)` negative lookahead.
- **`addr_of_mut!`** — macro invocation. The read-only `addr_of!` is intentionally NOT in scope (its failure mode is read-from-uninit, which is already caught by `MaybeUninit::assume_init`).

The commit point patterns (`unsafe Vec::set_len`, `MaybeUninit::assume_init`) are already covered by existing scanner rules and lock the workspace at their respective zero-occurrence baselines.

### Allowlist requirements

Each `MaybeUninit::write` / `ptr::write` / `addr_of_mut!` allowlist entry must state:

1. **Drop-leak proof** — either `T: !Drop` (and how the bound is enforced — `static_assertions::assert_not_impl_any!`, trait-dispatch ambiguity block, or `#[deny(...)]` lint) OR the name of the initialisation guard that handles panic recovery.
2. **Commit-path linearity** — the source range from first write to commit (`set_len` / `assume_init` / struct literal) must be infallible: no `?`, no panic source, no fallible call. The entry names the line range and confirms the inspection.
3. **Panic-path test** — the name of the test that forces a panic between the first write and the commit, asserting no leak (count `Drop` calls via a static counter) and no UB (no use of partially-init memory).
4. **Owner.**

## Field declaration order in `Drop` impls

Rust runs a user-defined `Drop::drop` body FIRST, then drops each field in declaration order (top-to-bottom). The body sees every field still alive; subsequent field drops happen in source order. A struct whose fields are inter-dependent at teardown — a guard plus the resource it guards, a callback registration plus the context that backs it, a runtime plus a handle that runs on it, a lock plus an artifact whose lifecycle is bound to the lock — is sensitive to BOTH the body's operation order AND the field declaration order. Getting either wrong produces use-after-free, lock-after-drop UB, panic-from-runtime-drop, or a small race window in which another process / thread observes inconsistent state.

### Rule

A struct with `impl Drop` and two or more fields whose teardown is mutually dependent MUST either:

1. **Drive every dependent teardown step explicitly in the Drop body**, using `Option::take()` to pull each owning field out of its slot in the chosen order. After the body finishes, the remaining fields drop in declaration order, but each `take()`d field is already `None` so the implicit drop is trivial. This is the load-bearing pattern in the workspace.

2. **Order fields so the declared-order field drops match the intended teardown order** when no explicit Drop body operates on those fields (i.e. the only ordering pressure is the automatic post-body field drop).

3. **Restructure the type to avoid the cross-field dependency** entirely -- carve the unrelated fields into a separate inner struct that owns the teardown discipline, then compose. The workspace explicitly DOES NOT permit `ManuallyDrop<T>` as the escape hatch here -- see the dedicated forbiddance section below for the reasoning and the preferred alternatives.

A Drop body that performs cleanup actions whose ordering matters (release a lock → unlink an artifact; cancel a worker → join its thread; signal shutdown → drain a channel) MUST sequence those actions in the body itself. Relying on the implicit field-drop order for ordering-critical work is a latent bug; the declared-order semantics are correct, but the next refactor that re-shuffles fields silently changes teardown semantics.

### Sentinel patterns

The following shapes MUST trigger a soundness review when added or modified:

- `Option<JoinHandle<T>>` + an `Arc<Runtime>` (or `Arc<CancellationToken>`) in the same struct, where the join handle runs work *on* the runtime. Drop MUST `take()` and join the handle BEFORE any field drop releases the runtime / cancellation source.
- `Option<oneshot::Sender<()>>` + `Option<JoinHandle<...>>` for a shutdown-signal + worker pair. Drop MUST `take()` and send first, then `take()` and join.
- A `Flock<File>` (or any kernel-lock guard) plus the path / artifact the lock protects. Drop MUST release the lock BEFORE removing or modifying the artifact, so a concurrent claimant cannot acquire the artifact while we still hold the lock on the orphaned inode.
- A callback registration handle plus the context object the callback reads through. The registration MUST be torn down before the context, either by an explicit unregister call in the Drop body or by ordering the registration field FIRST in declaration order.
- A guard or `MutexGuard`/`RwLockGuard` stored alongside the lock it was taken from. The guard MUST appear BEFORE the lock in declaration order; otherwise the lock drops first and the guard's drop dereferences freed memory.
- A `GlobalRef`/`JObject` issued from a `JavaVM` reference. The reference field MUST appear BEFORE the `JavaVM` field so the `DeleteGlobalRef` call in the guard's drop runs while the VM handle is still live.

### Existing benign uses

Audit log (2026-05-17): every Drop impl in `native/rust/crates/*` has been reviewed against this rule. The codebase uses pattern (1) — explicit `Option::take()` plus sequenced cleanup — for every teardown that ordering matters for. Representative examples:

| Drop impl | Ordering discipline | File |
|---|---|---|
| `TemporaryProxyRuntime` | `control.request_shutdown()` → wake via `TcpStream::connect(addr)` → `take()` + `handle.join()` | `ripdpi-monitor-proxy-runtime/src/lib.rs` |
| `EchoLoopback` | `take()` shutdown sender → fire-and-forget (test fixture) | `ripdpi-protocol-loopback/src/lib.rs` |
| `IoUringDriver` | `tx.send(Shutdown)` → `take()` + `join()` thread; field order is irrelevant because the body completes the handshake | `ripdpi-io-uring/src/ring/driver.rs` |
| `TunnelSessionHarness` | `cleanup()` chains `active_handle.take()` → cancel → join → remove from registry | `ripdpi-tunnel-android/src/session/state_machine.rs` |
| `UdpDnsServer` / `Socks5RelayServer` | `stop.store(true)` → wake socket → `take()` + `join()` thread | `ripdpi-monitor-engine/src/test_fixtures.rs` |
| `FixtureStack` | `stop.store(true)` → wake all TCP/UDP listeners → drain & join every handle in a single loop | `local-network-fixture/src/stack.rs` |
| `MasqueUdpFlow` | `driver_task.abort()` → `reader_task.abort()` (no explicit join — tokio cancels at next yield) | `ripdpi-masque/src/udp.rs` |
| `RealityHookGuard` | Owns single `*mut RealityCallbackState`; `Box::from_raw` + null the pointer to defuse accidental re-use | `ripdpi-vless/src/reality_hook.rs` |
| `PidFileGuard` | `take()` + drop the `Flock<File>` BEFORE `fs::remove_file(&path)` (fix landed for issue #31) | `ripdpi-proxy-runtime-adapter/src/platform.rs` |
| `MmapRegion` / `MappedFile` | Sole-owner; single `munmap` in Drop, no cross-field dep | `ripdpi-privileged-ops/src/linux/mmap_region.rs`, `ripdpi-geo/src/mapped_file.rs` |
| `ScopedHandle<T, F>` | Sole-owner generic RAII; `F::free(ptr)` exactly once | `soundness-canaries/src/lib.rs` |
| `RootHelperRegistration` | `registered: bool` gates `unregister_root_helper()` call; no second field to order against | `ripdpi-proxy-runtime/src/runtime/listeners.rs` |

The workspace has ZERO known field-order soundness bugs as of this audit. The `PidFileGuard` fix is the only modification the audit produced: the prior shape used `self.file.as_mut().flush()` (borrow, not move) which left the `Flock<File>` to drop AFTER `fs::remove_file(&self.path)`. Functionally correct (Linux flock is per-fd, an open-but-unlinked inode keeps its lock until the fd closes), but the conventional teardown order eliminates the small window in which a sibling process could `open(path, CREATE)` + `flock` on a fresh inode while the original guard still holds the lock on the orphaned one.

### CI surface

`scripts/ci/check_drop_order.py` runs on every PR (via `scripts/ci/run-rust-lint.sh`). It walks `native/rust/crates/*/src/**/*.rs`, finds every struct with `impl Drop`, parses the field list, and flags structs whose field types include 2+ resource-bearing patterns (`JoinHandle`, `Runtime`, `oneshot::Sender` / `mpsc::Sender` / `flume::Sender`, `CancellationToken`, raw pointers, `NonNull`, `Box<dyn ...>`, `OwnedFd`/`RawFd`, `Mmap`/`MmapMut`, `Flock`, `MutexGuard`, `RwLockGuard`, `File`, `OwnedSemaphorePermit`, `AbortHandle`).

Each flagged struct MUST satisfy ONE of:

1. The file contains a `Drop order:` marker comment (case- insensitive; line / block / doc comment all count) anywhere. Typically authored as a doc comment on the struct or on the `impl Drop` block stating which field drops first and why.
2. The struct is allowlisted in `ci/drop-order-allowlist.toml` with required fields `file`, `type_name`, `reason`, `owner`, `review_date`.

The marker-comment route is strongly preferred. The allowlist exists for true "all fields are independent" patterns where there is genuinely no ordering rationale worth writing down (as of 2026-05-17 the allowlist contains ZERO entries).

The scanner has dedicated unit tests at `scripts/ci/tests/test_check_drop_order.py` (19 cases covering regex, brace balancer, field parser, resource classifier, generic struct + generic impl matching, macro_rules! suppression). A final integration test in that suite asserts the workspace passes the scanner with the current marker set, so a regression in either the scanner or the marker placement fails CI immediately.

Regression tests for the `PidFileGuard` ordering live at `ripdpi-proxy-runtime-adapter/src/platform.rs`'s `pid_file_guard_tests` module — `drop_releases_lock_so_a_second_guard_can_claim_the_path` is the load-bearing assertion that the fix produces the intended sequence. End-to-end teardown regressions for `EchoLoopback` (a multi-resource owner with both `oneshot::Sender` and `JoinHandle`) live in `ripdpi-protocol-loopback/src/lib.rs`'s test module under the `Issue #31` banner — three async tests cover implicit-drop with no clients, implicit-drop with a live client, and 32-cycle construct-and-drop without leak.

### Cross-references

- `## Drop and raw back-pointers` (above) — the related rule for the parent-pointer side of drop discipline.
- `## Compile-fail enforcement` — most Drop-bearing types in the workspace are paired with `!Copy + !Clone` compile-fail regressions; those guards prevent the "duplicate guard ⇒ double free" failure mode that field/drop-order discipline alone cannot catch.
- `.claude/rules/llm-rust-prompts.md` — diff acceptance gate for AI-generated diffs touching `impl Drop`.

## `ManuallyDrop<T>` forbiddance

`std::mem::ManuallyDrop<T>` is a `#[repr(transparent)]` wrapper that DISABLES `T`'s destructor. The wrapped value's `Drop` no longer runs automatically; the surrounding code MUST hit a manual `ManuallyDrop::drop(&mut field)` (or `ManuallyDrop::take`
+ explicit consumption) on every path out of scope, including panic-unwind paths. Three failure modes show up empirically:

- **Forgotten drop.** A panic between construction and the manual `ManuallyDrop::drop` site leaks the resource. With `panic = "unwind"` (the `android-jni` cargo profile setting) the leak is silent and only the resource-exhaustion side effect surfaces, usually under load tests minutes later.
- **Double drop.** The author moves the value out of the wrapper via `ManuallyDrop::into_inner` AND also runs the manual `ManuallyDrop::drop`. The first call drops the value; the second call drops the already-dropped value via the freed allocation. UB.
- **Read after drop.** Code reads through `&self.field` / `&mut self.field` after the manual `ManuallyDrop::drop`. The type system permits the read because `ManuallyDrop<T>` is still a valid `T` according to the compiler, but the pointed-to bytes are now reused freed memory. UB on the first read.

The escape valve `ManuallyDrop` provides (suppress automatic Drop while keeping the field accessible) is almost never the right tool. The workspace's measured occurrences across the audit window (2026-05-17): ZERO production uses, ZERO test uses. Issue #33 retired `ManuallyDrop` as a valid escape hatch in the field-drop-order section above.

### Rule

`ManuallyDrop<T>` (qualified as `std::mem::ManuallyDrop<T>`, `core::mem::ManuallyDrop<T>`, or unqualified) is FORBIDDEN in production Rust crates under `native/rust/crates/*/src/**`. Any new occurrence must restructure to one of:

1. **`Option<T>`** with `Option::take()` for explicit teardown ordering. The borrow checker proves the value is consumed exactly once; `Drop` runs automatically on the `None` slot (which is trivial); the `Some` case runs `T`'s destructor at the explicit `take()` site. This is the canonical replacement and matches the rest of the workspace's discipline (`TemporaryProxyRuntime::handle`, `EchoLoopback::shutdown` + `join_handle`, `IoUringDriver::thread`, etc.).
2. **Private RAII guard** (`ScopedHandle<T, F>`, `BufferHandle<'pool>`, `IdleGuard<'_>`). The destructor is the single ownership-transfer point; safe code cannot bypass it (move-only handle, no `Copy`/`Clone`).
3. **Explicit enum state** (`Idle` / `Running` / `Destroyed` carrying the resource inside the active variant). The variant's `Drop` runs at the transition point; the state transition is in `match`-checked safe code.
4. **`mem::take` + `Default`** when the value's `Default:: default()` is provably cheap to drop (`Vec::new()`, `None`, `String::new()`). Use `mem::take` to swap the field out for the empty value and consume the original by value, calling `T`'s destructor through `drop()`.

If none of these apply, the type is a self-referential structure or a self-referential FFI handle exchange. In that case the correct shape is `Pin<Box<T>>` with explicit `PhantomPinned` plus a custom destructor on `T`, NOT `ManuallyDrop<T>`.

### Soundness boundary -- private and explicit

`ManuallyDrop<T>` is a SOUNDNESS BOUNDARY, not a convenience wrapper. Crossing it shifts responsibility for `T`'s destructor from the compiler to the surrounding code. That responsibility MUST be encoded in the type system, not in commentary:

- **Fields are private.** A `pub(crate) field: ManuallyDrop<T>` is forbidden. The wrapper must live behind a private module (`mod private { ... }`) so safe code outside the module cannot construct, move out of, or read through the wrapper except via the module's curated API.
- **Drop state is explicit.** The owning struct MUST carry an explicit state machine (typically a private enum: `NotInitialized | Initialized | ManuallyDropped`) and every `ManuallyDrop::drop` / `ManuallyDrop::take` / `ManuallyDrop::into_inner` call MUST happen only in the exact state transition the protocol allows. The state transition itself, not the `// SAFETY:` comment above it, is what proves the call is reachable exactly once.
- **Panic safety is committed in writing.** Every panic- unwind path through the wrapper's lifetime is enumerated in the allowlist entry below -- which path leaks deterministically, which path triggers a guard's Drop that hits the manual drop, which path is unreachable because of a preceding `?`. No path may double-drop.

### Allowlist

In the unusual case where `ManuallyDrop` is unavoidable (a crate-private FFI helper that must hand off a value across an allocator boundary; a self-referential type that cannot use `Pin<Box<T>>` for layout reasons), the type MUST:

- Satisfy the three Soundness-Boundary rules above.
- Carry a `// SAFETY:` block on every `ManuallyDrop::drop` / `ManuallyDrop::take` / `ManuallyDrop::into_inner` call site naming the matching construction site and the state transition that proves the call is reachable exactly once.
- Have a `#[test]` exercising the panic-unwind path (`std::panic::catch_unwind` around the construction + teardown sequence; assert the leak counter is unchanged) AND a Miri test (`cargo +nightly miri test`) that runs the same sequence under Miri's UB / UAF / leak detector. The reference implementation lives in `crates/soundness-canaries/tests/manuallydrop_canary.rs`.
- Earn an entry in `ci/unsafe-boundary-allowlist.toml` with the `ManuallyDrop` pattern AND the three extra required fields enforced by the scanner's allowlist validator: * `drop_state_protocol` -- the explicit state machine (e.g. `"NotInitialized -> Initialized -> ManuallyDropped; transition via Self::dispose() consuming self"`) and which code path triggers each transition. * `panic_safety` -- per-unwind-path proof that the manual drop runs exactly once or the leak is bounded (name the leak budget). * `alternative_rejected` -- a specific reason `Option<T>` + `Option::take`, a private RAII guard, `mem::take` + `Default`, or an explicit enum state with the resource in the variant is NOT sufficient for this site. The default policy answer is "use one of those"; the allowlist entry must rebut the default.

The scanner-side allowlist validator (`scripts/ci/check_unsafe_boundaries.py::load_allowlist`) enforces all of the above at PR time. An entry that lists `pattern = "ManuallyDrop"` but omits any of the three extra fields exits with code 2 (allowlist malformed), failing CI before the scan itself runs. Six unit tests in `scripts/ci/tests/test_check_unsafe_boundaries.py:: AllowlistValidatorTests` pin this enforcement down.

### CI surface

`scripts/ci/check_unsafe_boundaries.py` runs on every PR (via `scripts/ci/run-rust-lint.sh`). The `ManuallyDrop` pattern in that scanner's `PATTERNS` dict flags any new occurrence of the type. The four unit tests in `scripts/ci/tests/test_check_unsafe_boundaries.py` cover the field-shape match, the local-binding match, the substring guard (must NOT match `ManuallyDropped` / `manually_drop_*`), and the whitespace-tolerant `ManuallyDrop  <T>` form.

`mem::forget` is a separate but related pattern that suppresses Drop without `ManuallyDrop`. The five production `mem::forget` sites in the workspace (`soundness-canaries/src/lib.rs::take`, `ripdpi-io-uring/src/bufpool.rs::into_pending`, `ripdpi-privileged-ops/src/linux/fd.rs::close_owned_fd`, `ripdpi-android-proxy-adapter/src/lifecycle.rs::start_session`'s `IdleGuard` disarm, and the same disarm pattern in `ripdpi-proxy-runtime/src/runtime/listeners.rs`) are each allowlisted with documented enforcement and matching construction sites. New `mem::forget` occurrences inherit the existing allowlist discipline -- no new CI surface needed because the unsafe-boundary scanner already requires the allowlist entry on the surrounding `Box::into_raw` / `unsafe impl Send` / `Vec::set_len` patterns that the `mem::forget` site typically pairs with.

### Cross-references

- `## Field declaration order in Drop impls` (above) -- removes `ManuallyDrop<T>` from the list of valid teardown-ordering escape hatches.
- `## Ownership must be types, not flags` (below) -- forbids the related "manual drop flag" anti-pattern (a `bool` that tracks whether a separate resource has been dropped).
- `## `Box::into_raw` / `Box::from_raw` ownership transfer` -- the canonical FFI-friendly ownership-transfer pattern that replaces `ManuallyDrop` for the "hand the value across an allocator boundary" use case.

## Ownership must be types, not flags

A boolean field named `registered`, `is_alive`, `destroyed`, `initialized`, `disowned`, `owned_by_*`, or `freed` does not encode ownership — it only records a *belief* about a separate resource's state. If the resource is owned, the owning struct is the truth-bearing handle; the flag is at best a diagnostic check. If safe code can duplicate the flag, or set it to `true` without actually acquiring the underlying resource, or to `false` without releasing it, the flag silently becomes a lie and every downstream branch that depends on it is unsound.

**Rule.** Ownership and liveness MUST be represented by:

1. A **move-only handle** (no `Copy`/`Clone`) whose existence proves the resource is held. Drop releases. The compiler enforces "at most one owner".
2. An **RAII guard** that performs cleanup in `Drop`. A `bool` field inside the guard is acceptable **only** when used as a conditional-cleanup gate (`if self.registered { unregister(); }`) and the struct itself is move-only with a private field. The flag is then diagnostic; the move-only struct is the ownership token.
3. **Typestate** — distinct types per phase of the lifecycle, with transitions implemented as consuming methods (`fn destroy(self)`). Invalid transitions don't compile.
4. A **real reference count** (`Arc<T>`, `Rc<T>`, custom refcount with atomic increment/decrement under a release/acquire fence).
5. A **validated state machine** (enum + match) where every transition returns `Result` and unreachable states are `unreachable!()`.

**Anti-patterns reviewers reject.**

- A `pub struct` with a `pub registered: bool` field. Anyone can set the flag; the ownership semantics collapse.
- `Cell<bool>` for lifecycle: interior mutability with no synchronisation, no exclusivity, no auditable transitions.
- `if self.is_alive { unsafe { use_resource() } }` where the flag is the only safety guard. `debug_assert!(self.is_alive)` alongside is the release-mode trap (see § "`debug_assert!` as memory-safety guard").
- Multiple flags acting as a manual state machine (e.g. `initialized + registered + destroyed`) — replace with an enum.
- A "comment promise" — `// safety: the caller must ensure this flag is true` next to an `unsafe { ... }` block. Promises don't compile.

**The workspace's one allowlisted use** is `RootHelperRegistration::registered` in `crates/ripdpi-proxy-runtime/src/runtime/listeners.rs`. It fits shape #2 above: the struct is move-only (no `Copy`/`Clone` — enforced by compile-fail `AmbiguousIfCopy`/`AmbiguousIfClone` blocks), the field is private (default visibility), the constructor `for_config` sets it deterministically from config, and Drop branches on it for conditional cleanup. Runtime regression tests cover sequential lifecycle, no-op drop on unregistered guards, and the `mem::forget` leak documented limitation.

## `UnsafeCell<T>` discipline

`UnsafeCell<T>` is the **only** way Rust allows mutation through a shared reference (`&UnsafeCell<T>`). It is also the only primitive that defeats the compiler's aliasing rules without an `unsafe` block at the type level — the unsafety is moved to the `unsafe { *cell.get() }` deref instead.

**Rule.** `UnsafeCell<T>` permits interior mutability **but does not by itself make aliasing or threading sound.** Every `*cell.get()` deref must be guarded by an exclusivity protocol that the type system can enforce. The protocol must specify:

1. **The aliasing model.** Who is allowed to hold `&T` and `&mut T` simultaneously, and what makes simultaneous mutation impossible? Standard answers: move-only handle + free list (the `BufferHandle` design), `Mutex<T>`/`RwLock<T>` (locks), `Cell<T>`/`RefCell<T>` (single-threaded runtime check), atomics (lock-free primitive types).

2. **The synchronisation model.** When the cell is shared across threads, what supplies the release/acquire happens-before edge? Standard answers: `Mutex` unlock/lock, atomic operation, channel send/receive, thread spawn/join.

3. **The reentrancy behaviour.** If user-supplied code can re-enter the cell while a borrow is live, what prevents the second access from producing aliasing UB? Standard answer: don't expose user-supplied callbacks while a borrow is live; otherwise use `RefCell` (which panics on reentrancy) or restructure.

**Anti-patterns that the scanner + review reject.**

- A `pub struct` with a public `UnsafeCell<T>` field. The field must be private; the wrapper's API is the only valid access path.
- `unsafe impl Send for X {}` or `unsafe impl Sync for X {}` for a type whose `UnsafeCell<T>`'s contents are NOT protected by a release/acquire-class synchronisation primitive.
- A safe public method `fn get(&self) -> &mut T` (without `Mutex`- style guard wrapping) that derefs `*cell.get()`. The signature promises shared-to-exclusive without a runtime check; the type system can't see the exclusivity protocol and neither can callers.
- Returning the raw pointer from `cell.get()` to safe callers. The pointer is fine inside `unsafe { }`; surfacing it to safe code gives the caller a tool that bypasses the borrow check.

**Workspace inventory.** The only production `UnsafeCell` use is `Box<[UnsafeCell<Box<[u8]>>]>` in `crates/ripdpi-io-uring/src/` `bufpool.rs`. Its exclusivity protocol is documented in the next section and exercised by runtime tests in `bufpool::tests`. The scanner's `UnsafeCell::get` pattern (see "Custom scan" table) gates any new occurrence through the allowlist with the three-model template above.

## Creating `&mut T` from raw memory

`&mut T` carries the strongest aliasing guarantee in Rust: while it exists, no other reference (`&T` or `&mut T`) and no other route to the same memory may observe or mutate it. Producing one from a raw pointer or `*mut T` (the `&mut *ptr`, `ptr.as_mut()`, `NonNull::as_mut`, `get_unchecked_mut`, `slice::from_raw_parts_mut`, and `*cell.get()` paths) skips the borrow check entirely; soundness depends entirely on the surrounding type design proving exclusivity.

**Rule.** A safe public function must not turn a raw pointer or `*mut T` into `&mut T` unless the caller's type signature (typically `&mut self`, plus an upstream uniqueness protocol on the owning container) guarantees no other accessor exists. If the caller can violate uniqueness, the function must be `unsafe fn` with a `# Safety` section, OR the design must be reworked.

Concrete obligations:

1. **`&mut self` is the local exclusivity proof.** A method that derefs `*cell.get()` to `&mut T` must take `&mut self`. The borrow checker then rules out aliased mutable access for a single owner. The `BufferHandle::as_mut_buf(&mut self)` and `BufferHandle::deref_mut(&mut self)` patterns in `bufpool.rs` are the canonical examples.

2. **Container exclusivity is the upstream proof.** When the cell lives in a shared structure (a `Box<[UnsafeCell<T>]>` indexed by a handle, a `Mutex<T>`, etc.), the structure must enforce that at most one borrower exists per cell. The `BufferHandle` free-list discipline is one such protocol; `Mutex<T>` and `RwLock<T>` are the std-library equivalents. `Cell<T>` and `RefCell<T>` are alternatives for single-threaded use.

3. **Cross-thread synchronisation is a release/acquire edge.** When multiple threads access the cell, the protocol that transfers ownership of the cell must supply a happens-before relationship — typically a `Mutex` unlock/lock pair or an `AtomicUsize::store`/ `load` with `Release`/`Acquire`. `bufpool.rs::RegisteredBufferPool` uses the `Mutex<Vec<u16>>` free list for this.

4. **Move-only handles encode "at most one accessor".** A non-`Copy`, non-`Clone` handle whose constructor is gated by an exclusivity protocol (acquire from a registry, mutex lock, type-state transition) is a compile-time proof that safe code cannot duplicate the access right. The runtime checks (free-list bookkeeping, mutex contention) are necessary; the non-`Copy`/non-`Clone` constraint is what makes them sufficient.

5. **`debug_assert!` is not exclusivity.** A `debug_assert!(self .unique())` guard around `(*cell.get()).as_mut()` is compiled out of release builds; release-mode UB is the result if the assertion would have failed. See § "`debug_assert!` as memory-safety guard".

6. **Unbounded lifetimes leak the borrow.** A `fn as_mut<'a>(&self) -> &'a mut T` with an unconstrained `'a` lets the caller widen `'a` to `'static` and outlive `&self`. Tie the returned reference to `&mut self` (sugar form `fn as_mut(&mut self) -> &mut T`) so the borrow checker enforces the lifetime.

**Anti-patterns rejected by review.**

- `(*cell.get()).as_mut()` inside a method that takes `&self` (not `&mut self`), unless an enclosing exclusivity protocol is named in the SAFETY comment. The default expectation is `&mut self`.
- `fn get_mut(&self) -> &mut T` — taking a shared self yet returning exclusive — only sound when `T` is wrapped in interior mutability (Mutex, RefCell) and the function returns a guard, not a bare `&mut`.
- A safe `pub fn` that constructs `&mut T` from a `*mut T` parameter without internal validation. Either validate (null, alignment, exclusivity) before the conversion, or make the function `unsafe fn` with a `# Safety` section enumerating every precondition.
- Two methods with `&mut self` that each cache a `*mut T` in struct fields and re-deref later, allowing one call to mutate through a pointer the other call cached. The fields must be `&mut T` borrows bound to `&mut self`, or the cache must be invalidated on every mutation.

**Existing benign use.** The only `*cell.get()` → `&mut T` site in the workspace is `bufpool.rs`. Its exclusivity proof:
- `BufferHandle` is move-only (no `Copy`/`Clone`).
- The `RegisteredBufferPool::acquire()` constructor pops a unique index from a `Mutex<Vec<u16>>` free list under a lock; at most one `BufferHandle` exists per cell.
- `as_mut_buf(&mut self)` and `deref_mut(&mut self)` are anchored to `&mut self`, so two simultaneous `&mut [u8]` borrows from one handle cannot compile.
- `Drop` (and `PendingBuffer::complete`) push the index back to the free list; the next `acquire` may legitimately reuse the slot because the previous handle is gone.
- Runtime regressions in `bufpool::tests` witness this lifecycle.
- The compile-fail half (`!Copy + !Clone`, `&mut self`-anchored borrow, no `BufferHandle` constructor outside the crate) is enforced by the type system per "Compile-fail enforcement" below.

## `unsafe impl Send` and `unsafe impl Sync`

`Send` says "the whole value can be moved across threads safely." `Sync` says "`&T` can be shared across threads safely." Both are opt-in auto-traits: the compiler derives them automatically when every field implements them. A manual `unsafe impl Send` or `unsafe impl Sync` overrides the compiler's analysis, usually because the type contains a raw pointer (`*const T`/`*mut T`), `NonNull<T>`, `UnsafeCell<T>`, a JNI handle (`JavaVM`, jobject), or a thread-affine OS resource that the Rust type system can't reason about.

**Rule.** Every manual `unsafe impl Send | Sync` MUST:

1. carry a SAFETY comment naming the cross-thread invariant and the mechanism that enforces it (mutex unlock/lock for happens-before, read-only data, JNI spec contract, ownership transfer through a move-only handle, etc.);
2. live in an allowlist entry in `ci/unsafe-boundary-allowlist.toml` whose `enforcement` field reproduces the SAFETY argument in machine-readable form; and
3. include a `const _: fn() = || { fn assert_send<T: Send>() {} assert_send::<T>(); … }` block locking the claim — any future field change that breaks Send/Sync fails to compile at the assertion, before the lefthook clippy hook ever runs.

**Negative (`!Send` / `!Sync`) types** must use the trait-dispatch ambiguity trick (`AmbiguousIfSend<A>` / `AmbiguousIfSync<A>` overlapping blanket impls) to lock the absence of Send/Sync. This is the stable-Rust equivalent of `static_assertions::assert_not_impl_any!`. The pattern is in-place on `MmapRegion` in `crates/ripdpi-privileged-ops/src/linux/` `mmap_region.rs`; copy it verbatim for any future `!Send` type.

**The four manual `unsafe impl Send + Sync` impls in production**:

| Type | File | Cross-thread enforcement |
|---|---|---|
| `MappedFile` | `ripdpi-geo/src/mapped_file.rs` | Read-only mmap; no interior mutability; single owner; Drop munmaps once. |
| `RegisteredBufferPool` | `ripdpi-io-uring/src/bufpool.rs` | `Mutex<Vec<u16>>` free list supplies happens-before; per-cell access via the unique `BufferHandle` whose index is mutex-guarded. |
| `JniProtectCallback` (warp-android) | `ripdpi-warp-android/src/vpn_protect.rs` | JNI spec: `JavaVM` is thread-safe; `Global<JObject>` is GC-pinned across threads; `protect()` uses `attach_current_thread` per invocation. |
| `JniProtectCallback` (vpn-protect-adapter) | `ripdpi-android-vpn-protect-adapter/src/lib.rs` | Same as above (duplicate of the warp-android impl). |

**Anti-patterns rejected by review.**

- `unsafe impl Send for X {}` with no SAFETY comment — fails the `clippy::undocumented_unsafe_blocks` aspiration and the policy here.
- `unsafe impl Send` to "make it compile" because the type holds a raw pointer that's actually thread-affine (e.g. a `JNIEnv*`, an OpenGL context, a `MAP_SHARED` mmap with writeable mappings). These types must remain `!Send` and the design must change to use `Arc<Mutex<…>>`, a channel-based handoff, or per-thread registration.
- A `unsafe impl Send` impl whose SAFETY argument cites `debug_assert!` for the thread-affine invariant. The release-mode build is the one that ships; debug-only checks don't enforce thread safety.

## Documentation contract

Every `unsafe` block in production code must have a `// SAFETY:` comment within the two source lines immediately above it. The comment must answer:

1. What precondition makes the unsafe operation defined?
2. Who establishes the precondition (type? lifetime? RAII guard? callee contract?)
3. Why safe callers in this module cannot violate it.

Every `unsafe fn` must carry a `# Safety` rustdoc section with the same information. The macro-generated JNI exports are the only documented exception, justified in `docs/rust-soundness-policy.md`'s allowlist section.

## CI surface

The `rust-lint` job invokes `scripts/ci/run-rust-lint.sh`, which runs:

```
cargo fmt --check
python3 scripts/ci/check_runtime_crate_boundaries.py
python3 scripts/ci/check_unsafe_boundaries.py
cargo clippy --workspace --all-targets -- -D warnings
cargo doc --workspace --all-features --no-deps
```

The rustdoc step is included to catch new doc-comment compile errors but runs at default warning level because the workspace still has a small tail of pre-existing intra-doc-link warnings in legacy crates. Upgrading rustdoc to `-D warnings` requires clearing that warning tail first.

The dedicated `rust-miri` job runs `scripts/ci/run-rust-miri.sh`, which extends miri coverage opportunistically to crates with raw-pointer code (see commentary in that script).

`cargo test --all-features` runs in the existing `rust-tests` matrix; no change there.

## Lint waivers

The two `clippy::*` lints listed under "JNI/FFI allowances" in `native/rust/Cargo.toml` are the only blanket waivers. They exist because JNI macros and JNI-exported raw-pointer arguments would otherwise require per-symbol `#[allow(...)]` annotations that conflict with the no-baseline- extension policy enforced by `scripts/ci/check_rust_allow_guard.py`.

No additional waivers may be added without:

1. A note in this document explaining why.
2. A specific scan rule in `check_unsafe_boundaries.py` to replace the coverage the waiver removes.

## Compile-fail enforcement

We don't ship a separate `trybuild` harness — the Rust type system itself acts as the compile-fail test for every guarantee. The audit-driven refactors guarantee that these misuses *do not compile*:

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

Any new pattern in the same class should be encoded the same way: as a type or visibility constraint that makes the misuse impossible to *write*, not just impossible to do at runtime. If you cannot find such an encoding, propose the API as `unsafe fn` with a `# Safety` section instead.

## Adding new unsafe code

If you must add new `unsafe`:

1. Prefer to make it inaccessible from safe code (private `unsafe fn` helper called only from another `unsafe` block whose preconditions already imply the helper's).
2. Otherwise, make the surface `unsafe fn` with a `# Safety` section.
3. Pair the change with at least one regression test that demonstrates the precondition the type system or runtime check enforces.
4. If you are introducing a new `(file, pattern)` pair flagged by the scan, the PR description must contain a sentence that begins with "Restructure was rejected because …" explaining the chosen design.

Reviewers must check that step 4 is present and persuasive before approving.

## FFI panic-unwind containment

The `android-jni` and `android-jni-dev` cargo profiles in `native/rust/Cargo.toml` set `panic = "unwind"`. Unwinding through an `extern "C"` / `extern "system"` function is undefined behaviour. The release profile (`panic = "abort"`) is safer but is not the build that ships to Android.

### Rule

Every Rust function exported via one of the foreign-language ABIs (`extern "C"`, `extern "system"`, `extern "C-unwind"`, `extern "system-unwind"`) MUST guarantee that a Rust panic in its body cannot escape to foreign code. The two sanctioned shapes are:

1. **Preferred** — wrap the body with `android_support::ffi_boundary(default, || { ... })`. The helper runs the inner closure inside `catch_unwind` + `AssertUnwindSafe` and substitutes `default` on panic. The existing `android_support::install_panic_hook` logs the panic + backtrace before `catch_unwind` returns.

2. **Acceptable** — open-code `std::panic::catch_unwind(|| { ... })` and convert the `Result` into an FFI-safe return. This is the `JNI_OnLoad` shape: every cdylib's `JNI_OnLoad` opens with `match std::panic::catch_unwind(|| { ... }) { Ok(v) => v, Err(_) => JNI_ERR }`.

The body must contain `ffi_boundary(` or `catch_unwind(` *at the same function definition* — chaining the boundary into a helper one indirection away (`pub extern "system" fn export(...) { helper(...) }`, where `helper` does the wrap) hides the discipline from the CI scanner and from human reviewers reading the export site. Inline the wrapper.

### Sentinel value selection

The `default` passed to `ffi_boundary` is the value the JVM (or other foreign caller) sees when a panic is contained. It MUST be an unambiguous-failure value the caller already treats as an error:

| Return type           | Default                       | Why                                          |
|-----------------------|-------------------------------|----------------------------------------------|
| `jstring` / `jobjectArray` | `core::ptr::null_mut()`  | Callers already null-check string returns.   |
| `jboolean`            | `jni::sys::JNI_FALSE`         | "Failed / no" surface.                       |
| `jlong` (handle)      | `0`                            | "No handle" sentinel — every `jniCreate` caller treats `0` as failure. |
| `jint` (status code)  | `-1` (or any non-zero error)   | NEVER `0`: that is the success code for `jniStart`-style exports. |
| `()`                  | `()`                           | Nothing to communicate.                       |

The asymmetry on `jint` is the most important one: a careless `Default::default()` would substitute `0` on panic, which `jniStart`- shape callers treat as "started successfully" — silently turning a panic into a phantom success.

### Intentional unwind — the `-unwind` ABI variants

`extern "C-unwind"` / `extern "system-unwind"` sanction Rust panic unwinding through the foreign frame (Rust → C → Rust round-trips). They are NOT a workaround for "I do not want to wrap" — they require the foreign frame to be compiled with a matching unwind tolerance, which is a per-platform contract that this workspace does not generally satisfy. Use them only for Rust-to-Rust unwinding through a C trampoline, with an allowlist entry that names the foreign frame and its unwind ABI.

### Callbacks invoked from foreign code

The same rule applies to every Rust function whose POINTER is handed to foreign code: BoringSSL SSL_CTX callbacks, POSIX signal handlers, JavaVM thread-attach callbacks, libc qsort comparators. The `reality_client_hello_cb` in `ripdpi-vless/src/reality_hook.rs` is the canonical example — its body opens with `std::panic::catch_unwind(AssertUnwindSafe(|| inner(...)))`.

POSIX signal handlers (`signal_handler` in `ripdpi-root-helper`, `handle_signal` in `ripdpi-proxy-runtime`) are an exception by construction: they must remain async-signal-safe, so panicking in them is already undefined behaviour for reasons independent of FFI. The bodies are restricted to single `AtomicBool::store` calls that cannot panic; both are allowlisted on that basis.

### Drop in async / FFI contexts

A `Drop` impl that runs as part of stack unwinding through an FFI boundary will be invoked while `panicking()` is already true. If the Drop itself panics, the process aborts (`double-panic`). Drops that might panic (mutex poisoning, channel send, tokio runtime shutdown) MUST not appear on the unwind path of an `extern` function — wrap with `catch_unwind` inside the Drop, or restructure so the cleanup runs before the panic site.

### Enforcement

`scripts/ci/check_ffi_panic_boundary.py` runs in CI. It walks every `extern "C"` / `extern "system"` / `extern "C-unwind"` / `extern "system-unwind"` definition under `native/rust/crates/*/src/**` and fails the build if any function's body does not contain `ffi_boundary(` or `catch_unwind(` AND is not allowlisted in `ci/ffi-panic-boundary-allowlist.toml`. The allowlist requires a `reason` field that documents the no-panic property (atomic store only, literal-value return, stub under cfg). New entries are discouraged and audited at the `review_date`.

The scanner has corresponding unit tests in `scripts/ci/tests/test_check_ffi_panic_boundary.py` plus an integration test that exercises the live workspace. The runtime helper `android_support::ffi_boundary` has unit tests in `native/rust/crates/android-support/src/ffi_boundary.rs` covering each JNI return shape, plus a live `extern "system" fn` test that calls the ABI through a real function pointer with a panicking inner and asserts the sentinel returns without unwinding.

## Self-referential structs and Pin

Audit issue 35. Storing a raw pointer, reference, slice, or computed address that aims at another field of the same struct creates a self-referential type. Rust values are movable by default; a move invalidates every internal pointer and the type becomes UB on next access.

The default fix is to remove the self-reference: store an offset, an index, a `Range<usize>`, or an owned value instead. If the referent needs a stable address, allocate it separately (`Box<T>`, `Arc<T>`) so its address is independent of the parent struct's location.

If self-reference is genuinely required:

- The struct MUST add `PhantomData<PhantomPinned>` (or contain a `PhantomPinned` field) so it is `!Unpin`.
- The struct MUST never be exposed as plain `Self`; the only safe constructor returns `Pin<Box<Self>>` (or another stable container).
- Every projection method MUST go through `pin-project` or `pin-project-lite`; manual `unsafe { Pin::get_unchecked_mut(...) }` is forbidden in production code.
- Fields holding the internal pointer MUST be private.

### Enforcement

`scripts/ci/check_pin_and_self_reference.py` runs in CI. It scans every `.rs` file under `native/rust/crates/*/src/**` for the six high-risk patterns (`PhantomPinned`, `get_unchecked_mut`, `map_unchecked_mut`, `Pin::new_unchecked`, `unsafe impl Unpin`, and the self-referential field names `self_ptr` / `ptr_into_buf` / `slice_ptr` / `raw_view` / `cached_ref`) and fails the build if any production hit lacks an entry in `ci/pin-allowlist.toml`. The allowlist requires a `soundness_argument` field naming what is structurally pinned, what `Drop` does, and why no safe public API can break the invariant. As of 2026-05-17 the workspace has ZERO hits across all six patterns.

## Incorrect Pin API

Audit issue 36. `Pin<&mut T>` prevents moves only if every API on `T` preserves that invariant. The two canonical failure modes:

- A method on `Pin<&mut Self>` returns `&mut field`, letting a caller `std::mem::replace` the field and move the value `T` expected to be pinned.
- A manual `unsafe impl Unpin for SelfReferentialT` silently lifts the pin requirement and lets `mem::take` (or any `Pin::into_inner_unchecked` consumer) move the value.

The default position is identical to issue 35: use `pin-project` or `pin-project-lite` for projections; never write manual `get_unchecked_mut` / `map_unchecked_mut`; never write `unsafe impl Unpin` for a type whose constructor or fields can produce self-reference.

### Enforcement

Shares the `check_pin_and_self_reference.py` scanner and the `ci/pin-allowlist.toml` allowlist with issue 35. The five non-naming patterns (`get_unchecked_mut`, `map_unchecked_mut`, `Pin::new_unchecked`, `unsafe impl Unpin`, and `PhantomPinned`) directly police the issue-36 surface.

## Async borrow safety

Audit issue 37. Anything held across `.await` -- a `MutexGuard`, a `RefCell::borrow`, a raw pointer, a `&mut` borrow into another future's state -- must remain valid (and not produce a deadlock or panic) while the future is suspended.

The two patterns this codifies:

- **`std::sync::Mutex` / `parking_lot::Mutex` across `.await`**: forbidden. The sync lock blocks the runtime worker for the entire duration of any awaited future scheduled to that lock's region; if another arm of the same task tries to acquire the same lock, the runtime deadlocks. Use `tokio::sync::Mutex` if the critical section must span an `.await`, or restructure to drop the guard before the `.await`.
- **`RefCell::borrow` / `borrow_mut` across `.await`**: forbidden. A reentrant poll on the same task that tries to borrow the cell panics; this is the canonical issue-42 failure mode. Use `Cell` for `Copy` data or scope the borrow to a non-async block.

### Enforcement

The two Clippy lints `await_holding_lock` and `await_holding_refcell_ref` are set to `deny` in `[workspace.lints.clippy]` (`native/rust/Cargo.toml`). With CI's `-D warnings` they become hard errors; no allowlist exists at the guard level. The complementary `check_async_safety.py` scanner catches the related but distinct issue-39 case (blocking primitives called from within `async fn` bodies).

## Async cancellation safety

Audit issue 38. Dropping a future cancels it at any `.await` point. If the future temporarily mutated object state before `.await` and intended to restore it after, cancellation leaves the object permanently inconsistent.

The default fix is one of:

- Use an RAII guard whose `Drop` restores the temporary state unconditionally. The guard's destructor runs on both the resume-success path (where the guard is dropped explicitly at scope exit) and the cancel-on-await-drop path (where the future is being torn down).
- Defer committing state until after the awaited operation completes: `let result = op.await?; self.state = State::Ready;`. Cancellation in `op.await` leaves `self.state` unchanged.
- Use a typestate or split prepare/commit/rollback API so the intermediate state is invisible to other code paths.

Every `async fn` in production code SHOULD carry a rustdoc comment in the form:

```rust
/// # Cancel safety
///
/// Cancel-safe. <one-line reason: no mutable state crosses .await,
/// only stack-local borrows, etc.>
```

or:

```rust
/// # Cancel safety
///
/// NOT cancel-safe -- callers must use `tokio::select!` carefully
/// because dropping this future after the first `.await` leaves
/// `self.pending_queue` non-empty without a matching ack.
```

### Enforcement

The cancel-safety comment requirement is enforced at PR-review time and by the `async-cancel-safety` agent (see `.claude/agents/async-cancel-safety.md`). The agent runs as part of the soundness audit pass and the every-PR review when async code is touched. There is no per-line CI scanner for this rule.

## Blocking inside async runtime

Audit issue 39. A blocking call inside an `async fn` body that runs on a tokio worker pins that worker for the full duration of the syscall. Two consequences: `tokio::time::timeout` cannot fire (the timer driver does not advance while the worker is parked in libc), and concurrent blocking calls can exhaust the worker pool and deadlock the runtime.

The default fix is one of:

- Replace with the async equivalent: `tokio::net::lookup_host` instead of `ToSocketAddrs::to_socket_addrs`, `tokio::fs::*` instead of `std::fs::*`, `tokio::time::sleep` instead of `std::thread::sleep`, `tokio::net::TcpStream::connect` instead of `std::net::TcpStream::connect`.
- Wrap in `tokio::task::spawn_blocking(move || { ... })` and `.await` the join handle; the blocking work runs on tokio's dedicated blocking thread pool.
- Move long-lived blocking work to a dedicated OS thread that communicates with the runtime over a channel.

### Enforcement

`scripts/ci/check_async_safety.py` runs in CI. It walks every `async fn` body in production crates and flags any call to a known blocking primitive (`std::thread::sleep`, `std::fs::*`, `std::net::TcpStream::connect`, `std::net::UdpSocket::*`, `to_socket_addrs(`, `Command::output/status/spawn`). `#[cfg(test)]` modules are skipped wholesale (test fixtures routinely use `std::thread::sleep` for timing). New hits MUST appear in `ci/async-safety-allowlist.toml` with an `executor_strategy` field that names why the blocking is safe in context (dedicated current-thread runtime, single-shot bootstrap before runtime spawns, etc.). The placeholder value `review-needed: ...` is permitted only for entries grandfathered in the audit pass; new entries cannot use it.

The two highest-leverage call sites (`ripdpi-android-platform-adapter/src/doq.rs` and `ripdpi-xhttp/src/connect.rs`) were refactored to `tokio::net::lookup_host` in the audit pass for issues 37-39. The remaining 8 allowlisted entries are vendored SOCKS5/Hysteria2/ShadowTLS paths with `review-needed` follow-ups.

## `Arc<Mutex<T>>` discipline

Audit issue 40. `Arc<Mutex<T>>` is a legitimate primitive but a poor default. Per-feature use is fine; whole-module use is a design smell: it hides ownership, increases contention, and amplifies deadlock risk.

The default position is to ask, in order:

1. Can the data live in one task and communicate via channels (`mpsc`, `oneshot`, `watch`)? Channels move data and avoid all the lock-order questions below.
2. Is the data immutable after construction? `Arc<T>` alone suffices; no lock needed.
3. Are the readers many and writers rare? `Arc<ArcSwap<T>>` (or `Arc<RwLock<T>>`) is cheaper than `Arc<Mutex<T>>`.
4. Is the critical section CPU-bound and short? A sharded lock (`Arc<[Mutex<T>; N]>` keyed by hash) bounds contention.
5. If none of the above applies, `Arc<Mutex<T>>` is correct.

Every new `Arc<Mutex<T>>` introduction SHOULD document the owner model and contention expectation in a comment near the type declaration.

### Enforcement

There is no per-line CI scanner for `Arc<Mutex<T>>` overuse (it would over-fire on a workspace where the primitive is the legitimate primary tool). The `kotlin-design-auditor` and `rust-api-auditor` agents flag overuse during periodic design reviews. The complementary `check_locking_and_shared_state.py` scanner enforces the strict rules for the related `Rc<RefCell>` / `Rc<Mutex>` patterns.

## Deadlock from nested locks

Audit issue 41. Rust prevents data races but not deadlocks. A function that holds lock A and then acquires lock B can deadlock with another function that holds B and then tries to acquire A.

The two rules:

1. **Lock order**: every module with two or more locks MUST document the allowed acquisition order in a comment near the lock declarations. Violations of the documented order are reviewer-blocking.
2. **No user callbacks under lock**: a function holding a lock MUST NOT call back into user-supplied code (a closure parameter, a trait method on a generic argument, a `dyn` callback). The callee might re-enter the same lock, causing deadlock; releasing the lock before the callback eliminates the class entirely.

### Enforcement

The lock-held-across-callback scanner in `scripts/ci/check_unsafe_boundaries.py` (the `lock_held_across_callback` pattern) catches the second rule for the known lock types (`Mutex`, `RwLock`, `parking_lot::*`, `tokio::sync::Mutex`). The first rule is reviewer-enforced; there is no per-line scanner.

## RefCell runtime borrow panics

Audit issue 42. `RefCell<T>` enforces borrow rules at runtime. A borrow held while user code re-enters the same cell panics, not UB -- still a reliability bug in production.

The rule: `RefCell` is permitted for narrow, localized interior mutability (a cache invalidation flag in a single struct's method). It is forbidden for cross-component shared state. In particular, `Rc<RefCell<T>>` is forbidden for graph/observer/ callback patterns (see issue 43).

`Ref` and `RefMut` borrows MUST NOT cross `.await` (Clippy lint `await_holding_refcell_ref`, deny -- see issue 37 enforcement).

### Enforcement

The Clippy lint covers the cross-`.await` case. The broader `Rc<RefCell<T>>` ban is covered by issue 43.

## `Rc<RefCell<T>>` as implicit mutable graph

Audit issue 43. `Rc<RefCell<T>>` graphs hide ownership, panic under reentrancy, and leak via cycles. The lower-cost alternatives for every common case:

- **Tree with parent/child links**: parent owns child via `Rc<Child>`; child references parent via `Weak<Parent>`.
- **Graph**: arena-allocated nodes with typed integer IDs; the arena owns every node; "references" between nodes are IDs, not pointers.
- **Observer/listener pattern**: registration returns an RAII guard; the guard's `Drop` unregisters; cycles cannot form.
- **Mutation across components**: command queue (`mpsc::Sender`); one task owns the mutable state; everyone else sends commands.
- **Read-heavy state**: `Arc<ArcSwap<T>>` (or `arc-swap::ArcSwap`) for cheap atomic snapshot replacement.

### Enforcement

`scripts/ci/check_locking_and_shared_state.py` runs in CI. It flags every production hit of `Rc<RefCell<...>>`, `Rc<Mutex<...>>`, or `Rc<RwLock<...>>` (the latter two are almost always a typo for `Arc<...>`). New hits MUST appear in `ci/locking-allowlist.toml` with a `graph_shape` field naming the strong-edge owner chain, the `Weak` back-references, the cycle- handling story, and the reentrancy strategy. As of 2026-05-17 the workspace has ZERO hits.

The Clippy lint `rc_mutex` is set to `deny` in `[workspace.lints.clippy]` as a belt-and-suspenders check for the `Rc<Mutex<T>>` typo specifically.

## `Rc` / `Arc` cycles

Audit issue 44. A cycle in the strong reference graph leaks forever: every node holds a strong reference to the next, the reference counts never reach zero, the destructors never run.

The rule: in any graph-like or observer-like data structure, the strong-edge direction MUST be acyclic. Back-edges MUST use `Weak<T>`. Closures and tasks that capture `Arc<Self>` MUST have a documented lifecycle (either a finite lifetime tied to a specific operation, or an explicit `unregister`/`close` API).

Common offenders:

- Parent ↔ child references both using `Rc`/`Arc`: convert one direction to `Weak`.
- Observer lists where listeners capture `Arc<Self>` of the observed object: registration must return an RAII guard that the observer drops on unregister.
- Background tasks spawned with `tokio::spawn(async move { ... self_arc ... })`: the task must terminate when `self_arc` is the only remaining reference (typically via a `CancellationToken` shared by the spawner and the task).

### Enforcement

Reviewer-enforced. There is no per-line CI scanner; the `rust-api-auditor` agent flags suspicious patterns during periodic audits. Drop-counter tests (count `Arc::strong_count` after dropping the root) catch concrete leaks at test time.

## PhantomData and variance

Audit issues 45 and 46. `PhantomData<T>` simultaneously decides drop-check ownership, auto-trait inheritance (Send/Sync), and variance over lifetime and type parameters. Picking the wrong form is silent at the type level and catastrophic at runtime:

- `PhantomData<T>`              owns `T` for drop-check; inherits `Send`/`Sync` from `T`.
- `PhantomData<&'a T>`           borrowed; covariant in `T`/`'a`.
- `PhantomData<&'a mut T>`       borrowed; invariant.
- `PhantomData<*const T>`        no ownership; covariant; removes auto-traits (need explicit `unsafe impl Send/Sync` if desired).
- `PhantomData<*mut T>`          no ownership; invariant; removes auto-traits.
- `PhantomData<fn() -> T>`       Send + Sync without ownership.
- `PhantomData<fn(T)>`           contravariant marker.

The rule: every production `PhantomData<...>` field MUST be within 10 lines of a `// Variance:` (or `// PhantomData:`) comment that names the intended ownership, variance, and auto- trait effect. The marker may be a line comment, block comment, or doc comment.

For unsafe abstractions carrying lifetime parameters (typically `NonNull<T>` wrappers, iterator newtypes, FFI handles), the intended variance over each lifetime/type parameter MUST be documented AND covered by a compile-fail (`trybuild`) test that proves the variance is what the documentation claims.

### Enforcement

`scripts/ci/check_phantomdata_variance.py` runs in CI. Files containing the auto-trait assertion helpers (`AmbiguousIfSend`, `AmbiguousIfSync`, `AmbiguousIfCopy`) are skipped wholesale; the remaining production hits MUST satisfy the proximity-comment rule or appear in `ci/phantomdata-variance-allowlist.toml` with a complete `variance_argument`.

## Raw slice construction is a soundness boundary

Audit issue 47. `slice::from_raw_parts` and `slice::from_raw_parts_mut` synthesize a `&[T]` / `&mut [T]` from a pointer and length. Every contract in their `# Safety` sections is a separate UB vector: pointer validity, alignment, initialization, range-within-one-allocation, length-overflow, exclusive-access-for-mut. All silent under unit tests.

The rule: internal APIs accept `&[T]` / `&mut [T]`, NOT pointer + length. Conversion happens once at the FFI boundary with an explicit owner whose lifetime bounds the returned slice. Every `from_raw_parts*` call site is a soundness boundary that requires a written `soundness_argument`.

### Enforcement

`scripts/ci/check_raw_slice_and_layout.py` runs in CI. Every production call site of `slice::from_raw_parts`, `slice::from_raw_parts_mut`, `Layout::from_size_align`, or manual `* size_of::<T>()` arithmetic MUST appear in `ci/raw-slice-layout-allowlist.toml` with `soundness_argument` covering each safety condition individually (pointer validity, alignment, initialization, length overflow, lifetime owner, aliasing for `_mut`). The audit pass for issues 47-48 documented three known hits (`icmp_wrapped_udp.rs:51`, `mapped_file.rs:86`, `reality_hook.rs:266`); no new hits without a corresponding allowlist entry.

## Allocation / layout arithmetic

Audit issue 48. Manual `count * size_of::<T>()` arithmetic for allocation sizes overflows when `count` is attacker-controlled (network length field, parser tag, `as usize` from a foreign integer). The allocator returns a buffer smaller than the caller expected; the next write or read goes out of bounds.

The rule: allocation/layout math uses `Layout::array::<T>(count)`, `checked_mul`, `try_reserve`, or `usize::try_from`. Bare `count * size_of::<T>()` is forbidden in production code.

### Enforcement

`scripts/ci/check_raw_slice_and_layout.py` (shared with issue 47) catches `Layout::from_size_align` and `* size_of::<T>()` arithmetic. The Clippy lint `cast_possible_truncation` is set to `warn` in `[workspace.lints.clippy]` to catch the `as usize` / `as u32` truncation cases.

## `repr(packed)` discipline

Audit issue 49. Fields of `#[repr(packed)]` structs may be unaligned. Creating any Rust reference to such a field is UB even on aarch64; rustc's `unaligned_references` lint catches the reference case and is set to `forbid` in `[workspace.lints.rust]`.

The rule:

1. Wire formats are decoded with explicit byte parsing (`u32::from_le_bytes(buf[0..4].try_into()?)`), NOT by mapping bytes onto a packed struct.
2. The only acceptable use of `repr(packed)` is when an external ABI demands the exact byte layout AND field access goes through `std::ptr::addr_of!(...).read_unaligned()` (never `&packed.field`).
3. Derived `Debug`, `Clone`, `PartialEq`, etc. on packed structs may create references to packed fields; the `unaligned_references` lint catches the obvious cases, but new `#[derive(...)]` on a packed struct still requires manual review.

### Enforcement

`scripts/ci/check_packed_structs.py` runs in CI. Every production `#[repr(packed)]` (or `repr(packed(N))`, or `repr(C, packed)`) struct MUST appear in `ci/packed-allowlist.toml` with an `abi_source` field naming the external ABI demanding the packed layout and an `access_discipline` field naming the `addr_of!` + `read_unaligned` pattern in use. As of 2026-05-17 the workspace has ZERO `#[repr(packed)]` structs in production code.
