---
id: DGN-1786548966528287
title: Stop diagnostic DNS oracle fallback after quorum
kind: bug
status: review
area: diagnostics
priority: high
risk: standard
owner: codex
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-08-12
updated: 2026-08-12
spec_reason: regression-tested-single-module
related_tasks: []
status_detail: Implemented trusted-agreement quorum short-circuit; focused and full Rust tests, downstream tests, rustfmt, clippy, staticAnalysis, and task validation pass locally. Awaiting exact-SHA hosted CI.
---

## Goal

Stop diagnostic DNS oracle evaluation as soon as two encrypted resolvers agree,
so later fallback endpoints cannot consume the remaining scan budget with
redundant timeouts.

## Acceptance criteria

- The primary resolver and fallbacks are attempted in their existing order.
- Evaluation stops immediately after the first matching pair of non-empty
  encrypted answers establishes trusted agreement.
- A primary failure or disagreement still advances through fallbacks until
  agreement is reached or the configured fallback limit is exhausted.
- A focused RED/GREEN regression, the complete diagnostics DNS crate tests,
  Rust formatting, Clippy, static analysis, and task validation pass.

## Ownership

- `native/rust/crates/ripdpi-diagnostics-dns/src/dns_oracle.rs`
- `native/rust/crates/ripdpi-diagnostics-dns/src/dns_oracle/attempts.rs`
- This portfolio issue, its simple-work execution record, and generated task
  board
- No serialized shared-file lane is modified
