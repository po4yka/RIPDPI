---
name: rust-lints
description: Canonical [workspace.lints] and clippy.toml configuration for AI-resilient Rust. Use when reviewing or modifying the workspace Cargo.toml lint section, adding a new crate, or auditing why a class of LLM-generated bug went undetected by clippy.
---

# Rust Lints -- RIPDPI

## Purpose

Document the canonical clippy / rustc / rustdoc lint set that maximizes automatic rejection of LLM-class failure modes (async lock-across-await, undocumented unsafe, stack-overflow boxes, blanket-impl semver hazards, RAII swallowing). Each lint is annotated with the LLM-class bug it catches and the rationale for its level. This skill is the leverage point — every rule in `rust-discipline`, `rust-unsafe`, `rust-async-internals` is enforced more cheaply via the lints once they are in tree.

## When to consult

- Adding a new crate to `native/rust/crates/`.
- Reviewing or modifying `native/rust/Cargo.toml` `[workspace.lints.*]` sections.
- Authoring `native/rust/clippy.toml`.
- Auditing why an LLM-generated bug class (e.g., `MutexGuard` across `.await`) was not caught.
- Tightening lints for AI-heavy modules (authorship ≥ 50%).

## `[workspace.lints]` — canonical template

Place at the workspace root `native/rust/Cargo.toml`. Each subcrate inherits via `[lints] workspace = true`.

```toml
[workspace.lints.rust]
unsafe_op_in_unsafe_fn       = "deny"   # force explicit `unsafe { ... }` inside unsafe fn
missing_docs                 = "warn"
unused_lifetimes             = "warn"
unreachable_pub              = "warn"
elided_lifetimes_in_paths    = "warn"
let_underscore_drop          = "deny"   # catches `let _ = guard;` swallowing Drop
non_ascii_idents             = "deny"
trivial_numeric_casts        = "warn"
unused_must_use              = "deny"
# unsafe_code              = "forbid"   # per-crate where unsafe is banned

[workspace.lints.clippy]
# Group activations
all                          = { level = "deny",  priority = -1 }
pedantic                     = { level = "warn",  priority = -1 }
nursery                      = { level = "warn",  priority = -1 }
cargo                        = { level = "warn",  priority = -1 }

# Critical for AI-generated code — promote to deny.
await_holding_lock           = "deny"   # std::sync::MutexGuard across .await
await_holding_refcell_ref    = "deny"
await_holding_invalid_type   = "deny"   # configured via clippy.toml below
mem_forget                   = "deny"   # forget on Drop types is data loss
undocumented_unsafe_blocks   = "deny"   # every unsafe block needs // SAFETY:
multiple_unsafe_ops_per_block = "deny"  # one SAFETY: comment per op, not per block
missing_safety_doc           = "deny"   # # Safety on every pub unsafe fn
missing_panics_doc           = "warn"
missing_errors_doc           = "warn"
unwrap_used                  = "warn"   # promote to deny per-crate when ready
expect_used                  = "warn"
panic                        = "warn"
todo                         = "warn"
unimplemented                = "warn"
dbg_macro                    = "deny"
print_stdout                 = "warn"
exit                         = "deny"
large_stack_arrays           = "warn"
large_stack_frames           = "warn"
large_futures                = "warn"
large_enum_variant           = "warn"
exhaustive_enums             = "warn"   # require #[non_exhaustive] on pub enum
exhaustive_structs           = "warn"
inefficient_to_string        = "warn"
disallowed_methods           = "deny"
disallowed_types             = "deny"
str_to_string                = "warn"
string_to_string             = "warn"
implicit_clone               = "warn"
needless_pass_by_value       = "warn"
ref_option                   = "warn"
trivially_copy_pass_by_ref   = "warn"
redundant_closure_for_method_calls = "warn"
collapsible_if               = "warn"
uninlined_format_args        = "warn"

# Restriction-class lints with high AI signal-to-noise.
allow_attributes             = "warn"
allow_attributes_without_reason = "warn"   # forbid bare #[allow(...)] without justification
indexing_slicing             = "warn"   # use .get() not buf[i] on untrusted input
integer_division             = "warn"
arithmetic_side_effects      = "warn"   # catches unchecked u32 += on packet lengths
modulo_arithmetic            = "warn"
unwrap_in_result             = "warn"

[workspace.lints.rustdoc]
broken_intra_doc_links       = "deny"
private_intra_doc_links      = "warn"
missing_crate_level_docs     = "warn"
bare_urls                    = "warn"
```

## `clippy.toml` — canonical thresholds and lists

Place at `native/rust/clippy.toml`. Configures parameterized lints from the table above.

```toml
# Stay on workspace MSRV — clippy will not suggest features above this.
msrv = "1.94.0"

# Allow workspace owners to use non-prebreaking-API freedom in this private workspace.
avoid-breaking-exported-api = false

# Size thresholds — pair with workspace lints large_stack_frames / large_futures / etc.
stack-size-threshold         = 4096   # large_stack_frames triggers above 4 KiB
future-size-threshold        = 16384  # large_futures triggers above 16 KiB
type-complexity-threshold    = 250
too-many-arguments-threshold = 6

# Block sync locks held across .await — the highest-signal async bug class.
await-holding-invalid-types = [
  { path = "std::sync::MutexGuard",        reason = "use tokio::sync::Mutex in async paths" },
  { path = "std::sync::RwLockReadGuard",   reason = "use tokio::sync::RwLock" },
  { path = "std::sync::RwLockWriteGuard",  reason = "use tokio::sync::RwLock" },
  { path = "parking_lot::MutexGuard",      reason = "blocking lock in async context" },
  { path = "parking_lot::RwLockReadGuard", reason = "blocking lock in async context" },
  { path = "parking_lot::RwLockWriteGuard", reason = "blocking lock in async context" },
]

# Promote dangerous functions to compile errors.
disallowed-methods = [
  { path = "std::mem::forget",  reason = "use ManuallyDrop and document Drop semantics explicitly" },
  { path = "std::env::set_var", reason = "non-thread-safe; set at process start only" },
  { path = "std::ptr::read",    reason = "use std::ptr::read_unaligned for byte buffers from I/O/FFI; see rust-unsafe skill" },
]

# Force allocator decisions to be deliberate in async/multi-thread crates.
disallowed-types = [
  # Override per-module via #[allow(...)] when a sync block is genuinely sync-only.
  { path = "std::sync::Mutex",   reason = "in async modules use tokio::sync::Mutex (override per-module)" },
  { path = "std::sync::RwLock",  reason = "in async modules use tokio::sync::RwLock" },
]
```

## Crate-level attributes that raise the floor

Add to each crate's `lib.rs` / `main.rs`:

```rust
// Pure-logic crates: zero unsafe allowed. RIPDPI examples: ripdpi-packets,
// ripdpi-desync, ripdpi-config, ripdpi-failure-classifier, ripdpi-ipfrag, ripdpi-session.
#![forbid(unsafe_code)]

// Crates that have unsafe (FFI, syscalls): require explicit `unsafe { ... }`
// inside `unsafe fn` and demand SAFETY comments per operation.
#![deny(unsafe_op_in_unsafe_fn)]
#![warn(
    clippy::undocumented_unsafe_blocks,
    clippy::multiple_unsafe_ops_per_block,
)]

// Nightly-only — track but do not deny on stable.
#![cfg_attr(nightly, feature(must_not_suspend))]
#![cfg_attr(nightly, deny(must_not_suspend))]

// Rustdoc hygiene.
#![deny(rustdoc::broken_intra_doc_links)]
#![warn(missing_docs)]
```

## Cross-reference: which LLM bug class each lint catches

| Lint | LLM-class bug it catches |
|------|--------------------------|
| `await_holding_lock` + `await-holding-invalid-types` | `std::sync::Mutex` guard held across `.await` (article §2 / §3) |
| `mem_forget = deny` + disallowed `std::mem::forget` | Leaking Drop on resource-bearing types (sqlx::Transaction, OwnedFd) |
| `undocumented_unsafe_blocks` + `multiple_unsafe_ops_per_block` | Missing or under-specified `// SAFETY:` comments |
| `missing_safety_doc` | Missing `# Safety` rustdoc on `pub unsafe fn` |
| `large_stack_arrays` + `large_stack_frames` + `large_futures` | `Box::new([0u8; N])` and oversized async state machines |
| `exhaustive_enums` + `exhaustive_structs` | Public enums/structs without `#[non_exhaustive]` (semver hazard) |
| `disallowed_methods` on `ptr::read` | Misaligned reads on byte buffers — ARM64 UB |
| `let_underscore_drop = deny` | `let _ = guard;` silently dropping a critical RAII handle |
| `arithmetic_side_effects` + `indexing_slicing` | Unchecked integer arithmetic / index access on untrusted input |
| `allow_attributes_without_reason` | Bare `#[allow(...)]` smuggling clippy violations past review |
| `redundant_closure_for_method_calls` | `|x| x.foo()` instead of `T::foo` — common LLM stylism |
| `uninlined_format_args` | `format!("{}", x)` instead of `format!("{x}")` — drives token waste |

## Per-crate exceptions

Subcrates that legitimately need a weaker lint level apply local `#[allow(...)]` with a justification. Example:

```rust
// In a sync-only crate that uses std::sync::Mutex for sharing with non-tokio threads.
#![allow(clippy::disallowed_types, reason = "this crate has no async; std::sync::Mutex is correct here")]
```

Never lower a lint globally to silence a single site. Never add an `#[allow(...)]` without a `reason = "..."`. The `allow_attributes_without_reason` lint enforces this.

## Verification

After modifying workspace lints:

```bash
cd native/rust
cargo clippy --workspace --all-targets --all-features --locked -- -D warnings 2>&1 | tee clippy.log
rg '^\s*#\[allow\([^)]+\)\]' --type rust -n native/rust/  # audit existing allows
```

Any `#[allow(...)]` without a `reason = "..."` clause is a violation under the canonical workspace lints.

## Related skills

- `rust-discipline` — items 22–25 reference these lints by name.
- `rust-unsafe` — `unsafe_op_in_unsafe_fn` and SAFETY comment patterns.
- `rust-async-internals` — `await_holding_*` lints and `await-holding-invalid-types`.
- `cargo-workflows` — workspace structure and edition migration.
- `rust-sanitizers-miri` — lints are static; Miri catches the runtime tail.
