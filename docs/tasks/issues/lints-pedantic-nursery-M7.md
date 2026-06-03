---
title: Adopt clippy::pedantic / clippy::nursery per-crate for high-AI-authorship crates
type: task
status: todo
area: tooling
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-06-03
updated: 2026-06-03
---

## Summary

`.claude/rules/llm-rust-prompts.md` ("CI infrastructure expectations") asks for
`clippy::pedantic` + `clippy::nursery` to be enabled "for files where AI
authorship >= 50%". This task records the concrete adoption plan and explains
why a blanket workspace config edit is the **wrong** way to satisfy that ask.

## Why NOT a workspace-wide lint-group edit

The workspace lint floor lives in `native/rust/Cargo.toml`
(`[workspace.lints.clippy]`, lines ~321-477) and is inherited by every member
crate via `[lints] workspace = true`. Two failure modes block the obvious edits:

1. **`pedantic = { level = "warn", priority = -1 }` (and `nursery`) workspace-wide
   floods the gate.** CI's pre-commit gate runs
   `cargo clippy --workspace --no-deps --all-targets -- -D warnings`. Adding
   either group as `warn` immediately escalates *every* pedantic/nursery finding
   across ~112 crates to a hard error. The two groups are large and noisy
   (`module_name_repetitions`, `must_use_candidate`, `missing_errors_doc`,
   `similar_names`, `cast_*`, `option_if_let_else`, ...); they would turn the
   gate red with hundreds of findings that are mostly stylistic, not
   correctness. This is out of scope and would block all other work.

2. **`pedantic = "allow"` / `nursery = "allow"` workspace-wide is a no-op that
   muddies intent.** Both groups are already allow-by-default in clippy, so a
   blanket `allow` changes nothing at the lint level. Worse, it sits directly
   above the deliberately curated cherry-pick block (`cloned_instead_of_copied`,
   `map_unwrap_or`, `redundant_closure_for_method_calls`,
   `semicolon_if_nothing_returned`, `default_trait_access`, ... and the
   soundness-focused `transmute_undefined_repr` nursery lint) and would imply the
   workspace had "considered and rejected" the whole group rather than
   hand-selecting from it. It dilutes the signal of the curated block without
   buying any enforcement.

So the correct unit of adoption is **per-crate (or per-file) opt-in**, scoped to
the crates with the highest AI-authorship density, not a workspace lint-group
flip.

## Existing curated pedantic/nursery lints (do not duplicate)

`[workspace.lints.clippy]` already cherry-picks these individual pedantic lints
to `warn` (escalated to error by `-D warnings`):

- `cloned_instead_of_copied`, `explicit_iter_loop`, `implicit_clone`,
  `inefficient_to_string`, `map_unwrap_or`, `redundant_closure_for_method_calls`,
  `semicolon_if_nothing_returned`, `uninlined_format_args`,
  `unnested_or_patterns`, `manual_let_else`, `trivially_copy_pass_by_ref`,
  `unused_self`, `default_trait_access`, `explicit_into_iter_loop`,
  `match_wildcard_for_single_variants`.

And these nursery / soundness lints: `transmute_undefined_repr` (warn),
`as_ptr_cast_mut` (warn), `ptr_as_ptr` (warn), `await_holding_lock` (deny),
`await_holding_refcell_ref` (deny), `rc_mutex` (deny), `mut_from_ref` (deny),
`transmute_ptr_to_ptr` (deny), `useless_transmute` (deny),
`cast_ptr_alignment` (deny), `crosspointer_transmute` (deny).

The per-crate opt-in below is **additive** on top of this floor: it turns on the
*rest* of the pedantic group for the target crate, while the curated lints stay
workspace-wide.

## Recommended approach: per-crate `#![warn(clippy::pedantic)]` opt-in

For a chosen crate, add at the top of its crate root (`src/lib.rs`):

```rust
// AI-authorship >= 50% — opt into the full pedantic group on top of the
// curated workspace floor. See docs/tasks/issues/lints-pedantic-nursery-M7.md.
#![warn(clippy::pedantic)]
// Selectively silence the high-noise / low-value pedantic lints, with a reason:
#![allow(clippy::module_name_repetitions)] // crate uses <crate>_<thing> naming
#![allow(clippy::missing_errors_doc)]      // error types are self-describing
#![allow(clippy::must_use_candidate)]      // not worth the annotation churn here
```

Notes:

- Prefer crate-attribute opt-in over `[lints]` table entries so the opt-in lives
  next to the code it governs and is visible in every diff to that crate.
- `nursery` is **not** recommended as a group opt-in: it contains
  false-positive-prone and unstable lints. Cherry-pick individual nursery lints
  into the workspace floor instead (the pattern already used for
  `transmute_undefined_repr`).
- Every `#![allow(...)]` carries a one-line reason comment (mirrors the existing
  per-lint comment discipline in `[workspace.lints.clippy]`).
- Land **one crate per commit**. Each opt-in is its own gate-clean diff:
  enable, fix the surfaced findings, confirm
  `cargo clippy --workspace --no-deps --all-targets -- -D warnings` is green,
  commit. Do not bulk-enable.

## Candidate crates (verify AI-authorship before enabling)

Heuristic shortlist — newer, self-contained, mechanically-generated or
LLM-scaffolded crates with limited downstream blast radius. Verify per-crate
(git blame / authorship) before flipping; this list is a starting point, not a
mandate:

- `ripdpi-anytls`
- `ripdpi-mieru`
- `ripdpi-ssh`
- `ripdpi-shadowtls`
- `ripdpi-webtunnel`
- `ripdpi-tor`
- `ripdpi-naiveproxy`
- `ripdpi-cloudflare-origin`
- `ripdpi-masque`
- `ripdpi-ipfrag`

Prefer leaf crates (no in-workspace dependents) for the first few so a churny
fix-up does not ripple. Start with the smallest leaf to validate the
fix-the-findings effort before scaling out.

## Acceptance criteria

- [ ] This plan doc exists and is linked from the AI-Rust review discipline
      (`.claude/rules/llm-rust-prompts.md`) where pedantic/nursery is mentioned,
      if/when that file is next touched.
- [ ] At least one leaf candidate crate carries a gate-clean
      `#![warn(clippy::pedantic)]` opt-in (its own commit).
- [ ] No workspace-wide `pedantic`/`nursery` group entry is added to
      `[workspace.lints.clippy]` (the curated cherry-pick block stays the floor).

## Definition of done

- The per-crate adoption pattern is documented (this file), and the first
  demonstration crate compiles gate-clean under
  `cargo clippy --workspace --no-deps --all-targets -- -D warnings`.

## Links

- `.claude/rules/llm-rust-prompts.md` — "CI infrastructure expectations" and the
  diff-acceptance gate that motivate this.
- `native/rust/Cargo.toml` — `[workspace.lints.clippy]` curated floor.
- `rust-lints` skill — canonical `[workspace.lints]` / `clippy.toml` template.
