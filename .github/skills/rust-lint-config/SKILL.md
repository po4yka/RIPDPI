---
name: rust-lint-config
description: >
  Use when modifying clippy, rustfmt, or cargo-deny configuration, adding new lints,
  or debugging lint failures in the native/rust/ workspace.
---

# Rust Lint Configuration

All lint configuration is centralized at the workspace level. Individual crates inherit via `[lints] workspace = true` -- never add per-crate lint overrides.

## Configuration Files

| File | Purpose |
|------|---------|
| `native/rust/Cargo.toml` | `[workspace.lints.clippy]` and `[workspace.lints.rust]` -- lint levels |
| `native/rust/clippy.toml` | Clippy thresholds and disallowed methods |
| `native/rust/rustfmt.toml` | Formatting rules |
| `native/rust/deny.toml` | License, advisory, and dependency checks |

## Current Lint Levels

### Workspace Cargo.toml `[workspace.lints.clippy]`

**Denied (errors)**:
- `correctness` -- logic errors that are almost certainly bugs
- `suspicious` -- code that is likely wrong

**Warned**:
- `style`, `complexity`, `perf` -- broad lint groups
- Cherry-picked pedantic lints: `cloned_instead_of_copied`, `explicit_iter_loop`, `explicit_into_iter_loop`, `implicit_clone`, `inefficient_to_string`, `map_unwrap_or`, `redundant_closure_for_method_calls`, `semicolon_if_nothing_returned`, `uninlined_format_args`, `unnested_or_patterns`, `manual_let_else`, `trivially_copy_pass_by_ref`, `unused_self`, `default_trait_access`, `match_wildcard_for_single_variants`

**Allowed (JNI/FFI exceptions)**:
- `missing_safety_doc` -- JNI crates have many trivially-safe extern fns
- `not_unsafe_ptr_arg_deref` -- required by JNI function signatures

### Workspace Cargo.toml `[workspace.lints.rust]`

- `unsafe_op_in_unsafe_fn = "warn"` -- require explicit `unsafe` blocks inside `unsafe fn`

### clippy.toml

```toml
too-many-arguments-threshold = 8
type-complexity-threshold = 300
disallowed-methods = [
    { path = "std::iter::Iterator::for_each", reason = "Use a `for` loop for side effects (F-COMBINATOR)" },
]
```

### rustfmt.toml

```toml
edition = "2021"
max_width = 120
use_small_heuristics = "Max"
```

### deny.toml

- **Licenses**: allow-list (MIT, Apache-2.0, BSD-2/3-Clause, ISC, 0BSD, Zlib, CDLA-Permissive-2.0, Unicode-3.0, OpenSSL)
- **Advisories**: deny all, no ignores
- **Bans**: `multiple-versions = "warn"`, `wildcards = "warn"`
- **Sources**: only crates.io registry allowed

## Running Lints

### Local (fast iteration)

```bash
cd native/rust

# Clippy -- all targets, treat warnings as errors
cargo clippy --workspace --all-targets -- -D warnings

# Format check
cargo fmt --all -- --check

# Supply chain / license / advisory
cargo deny check

# All three in sequence
cargo clippy --workspace --all-targets -- -D warnings && cargo fmt --all -- --check && cargo deny check
```

### CI

The `.github/workflows/ci.yml` pipeline runs these as separate steps. Clippy and fmt run on every push/PR to main.

### Single-crate iteration

```bash
cargo clippy -p ripdpi-monitor --all-targets -- -D warnings
```

## Adding a New Lint

1. **Add** the lint to `[workspace.lints.clippy]` in `native/rust/Cargo.toml`
2. **Run** `cargo clippy --workspace --all-targets` to find all violations
3. **Fix** violations across the workspace. If too numerous for one patch:
   - Add `#[allow(clippy::lint_name)]` with a `// TODO(po4yka): fix` comment on individual sites
   - Create a follow-up task to clean them up
4. **Never** suppress a lint workspace-wide to avoid fixing violations
5. **Run** `cargo nextest run --workspace` to verify fixes did not break behavior

### Adding a disallowed method

Add to the `disallowed-methods` list in `native/rust/clippy.toml`:

```toml
disallowed-methods = [
    { path = "std::iter::Iterator::for_each", reason = "Use a `for` loop for side effects" },
    { path = "your::new::method", reason = "Explanation of why it is banned" },
]
```

## Suppressing a Lint

### Per-site (preferred)

```rust
#[allow(clippy::too_many_arguments)]
fn complex_jni_bridge(a: i32, b: i32, c: i32, ...) { ... }
```

Always add a comment explaining why the suppression is necessary.

### Per-crate (rare, JNI only)

The workspace already allows `missing_safety_doc` and `not_unsafe_ptr_arg_deref` for JNI crates. Adding new per-crate allows requires justification in the commit message.

### Never suppress workspace-wide

If a lint produces too many false positives across the entire workspace, reconsider whether it belongs in the config at all rather than setting it to `"allow"`.

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Adding lint config to a specific crate's Cargo.toml | All lints are workspace-level; crates use `[lints] workspace = true` |
| Suppressing lint workspace-wide to avoid fixing code | Fix the violations or add per-site `#[allow]` with TODO |
| Running `cargo clippy` without `--all-targets` | Tests and examples need linting too |
| Forgetting `-- -D warnings` in CI | Warnings must be treated as errors in CI |
| Adding a dependency without running `cargo deny check` | New deps may violate license or advisory policy |
| Using unstable rustfmt options | `rustfmt.toml` uses only stable options (`edition`, `max_width`, `use_small_heuristics`) |
| Raising thresholds in clippy.toml to avoid refactoring | Fix the code; thresholds are already generous (8 args, 300 type complexity) |
