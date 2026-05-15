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
