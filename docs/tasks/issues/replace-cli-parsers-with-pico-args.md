---
title: Replace 3 hand-written CLI argument parsers with pico-args
type: task
status: backlog
area: rust-native
priority: medium
owner: unassigned
parent: consolidate-rust-manual-implementations-with-vendored-deps
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [ ] #task Replace 3 hand-written CLI argument parsers with pico-args #repo/RIPDPI #area/rust-native #status/backlog 🔼

## Summary

Three internal binaries parse `std::env::args()` manually with `while i < args.len()` loops. Replace all three with `pico-args = "0.5"` (zero transitive deps, 500 lines of source).

## Affected binaries

| Binary crate | Hand-rolled parser location |
|---|---|
| `ripdpi-root-helper` | `src/main.rs` — parses `--socket <path>` |
| `ripdpi-naiveproxy` | `src/main.rs` (via `parse_args()`) |
| `ripdpi-cloudflare-origin` | `src/main.rs` (via `parse_args()`) |

## Implementation steps

1. Add `pico-args = "0.5"` to `[workspace.dependencies]`.
2. Add it to each binary crate's `[dependencies]`.
3. For each binary rewrite the arg-parsing block:
   ```rust
   let mut args = pico_args::Arguments::from_env();
   let socket: PathBuf = args.value_from_str("--socket")?;
   args.finish()?; // returns Err if unknown args remain
   ```
4. Remove the `while i < args.len()` loops and any associated index arithmetic.
5. `cargo build -p ripdpi-root-helper -p ripdpi-naiveproxy -p ripdpi-cloudflare-origin` passes.

## Acceptance criteria

- [ ] `pico-args` in `[workspace.dependencies]`.
- [ ] Hand-rolled arg loops deleted from all three binaries.
- [ ] `cargo build` for all three succeeds.
- [ ] Unknown/extra arguments produce an error (enforced by `args.finish()`).
