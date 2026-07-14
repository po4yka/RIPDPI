---
name: rust-lints
description: Current workspace lint policy and safe tightening workflow for RIPDPI Rust. Use when reviewing or modifying native/rust/Cargo.toml workspace lints, native/rust/clippy.toml, crate lint inheritance, or harness lint-policy drift.
---

# Rust lint policy

## Purpose

Document the lint levels that are enforced by the current workspace. This file is checked against `native/rust/Cargo.toml` and `native/rust/clippy.toml`; it must describe deployed policy, not an aspirational target. Proposed stricter lints belong in a tracked migration with measured findings and their own commit.

## `[workspace.lints]` — canonical template

Every workspace crate inherits these settings with `[lints] workspace = true` unless a documented build constraint requires otherwise.

```toml
[workspace.lints.clippy]
correctness = { level = "deny", priority = -1 }
suspicious = { level = "deny", priority = -1 }
style = { level = "warn", priority = -1 }
complexity = { level = "warn", priority = -1 }
perf = { level = "warn", priority = -1 }
cloned_instead_of_copied = "warn"
explicit_iter_loop = "warn"
implicit_clone = "warn"
inefficient_to_string = "warn"
map_unwrap_or = "warn"
redundant_closure_for_method_calls = "warn"
semicolon_if_nothing_returned = "warn"
uninlined_format_args = "warn"
unnested_or_patterns = "warn"
manual_let_else = "warn"
trivially_copy_pass_by_ref = "warn"
unused_self = "warn"
default_trait_access = "warn"
explicit_into_iter_loop = "warn"
match_wildcard_for_single_variants = "warn"
missing_safety_doc = "allow"
not_unsafe_ptr_arg_deref = "allow"
undocumented_unsafe_blocks = "allow"
mut_from_ref = "deny"
transmute_ptr_to_ptr = "deny"
useless_transmute = "deny"
multiple_unsafe_ops_per_block = "allow"
as_ptr_cast_mut = "warn"
ptr_as_ptr = "warn"
cast_ptr_alignment = "deny"
crosspointer_transmute = "deny"
transmute_undefined_repr = "warn"
await_holding_lock = "deny"
await_holding_refcell_ref = "deny"
rc_mutex = "deny"

[workspace.lints.rust]
unsafe_op_in_unsafe_fn = "deny"
improper_ctypes = "warn"
improper_ctypes_definitions = "warn"
```

The three unsafe-documentation lints remain `allow` during the existing annotation migration. `scripts/ci/check_unsafe_boundaries.py` is the current blocking contract for safe-wrapper documentation and containment. Do not describe the target `warn`/`deny` levels as already enforced.

## `clippy.toml` — canonical thresholds

```toml
msrv = "1.96.0"
too-many-arguments-threshold = 8
type-complexity-threshold = 300
```

`clippy.toml` also owns the checked-in `disallowed-methods` list. Additions must be validated across the full workspace before landing.

## Safe tightening workflow

1. Run the proposed lint at workspace scope without editing a baseline.
2. Classify every existing finding and estimate the remediation slice.
3. Land source remediation before or with the policy change; never weaken unrelated lints to compensate.
4. Update this skill in the same commit as the actual lint policy.
5. Run the strict harness policy check and full Clippy gate.

```bash
python3 scripts/ci/check_harness_policy.py
cargo clippy --manifest-path native/rust/Cargo.toml --workspace --all-targets --locked -- -D warnings
```

## Related skills

- `rust-discipline` for workspace Rust conventions.
- `rust-unsafe` for unsafe-boundary review.
- `rust-async-internals` for async lint interpretation.
- `cargo-workflows` for workspace membership and crate inheritance.
