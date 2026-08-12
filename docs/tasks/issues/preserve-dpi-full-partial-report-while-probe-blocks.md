---
id: DGN-1786552247631919
title: Preserve dpi_full partial report while a probe blocks
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
status_detail: Implemented connectivity checkpoints with one-shot cancellation recovery, shared deadline provenance, serialized start/cancel lifecycle, and terminal evidence preservation. RED/GREEN regressions and affected Rust crate suites pass; remaining integration gates are recorded in the task.
---

## Goal

Preserve completed `dpi_full` connectivity evidence when a later native probe
remains blocked past the scan deadline and cancellation grace period.

## Acceptance criteria

- Completed connectivity stages publish a non-terminal partial-report
  checkpoint without ending normal polling.
- Cancellation can retrieve the latest checkpoint immediately while a later
  native worker is still blocked.
- A normal scan continues until the engine publishes its terminal report.
- Focused RED/GREEN regressions, the monitor-engine suite, Rust formatting,
  Clippy, static analysis, architecture health, and task validation pass.

## Ownership

- `native/rust/crates/ripdpi-monitor-engine/src/engine/runtime/`
- `native/rust/crates/ripdpi-monitor-engine/src/session/`
- `native/rust/crates/ripdpi-diagnostics-contracts/src/types/mod.rs`
- `native/rust/crates/ripdpi-diagnostics-runner/src/connectivity.rs`
- This portfolio issue, its simple-work execution record, and generated board
- No wire schema, JNI signature, lockfile, or generated contract fixture changes
