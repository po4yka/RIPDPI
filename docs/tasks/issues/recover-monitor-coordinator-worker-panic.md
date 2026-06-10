---
title: "Recover from runner-thread panic in monitor-engine coordinator instead of killing the scan"
type: task
status: todo
area: diagnostics
priority: medium
owner: unassigned
parent: epic-june-2026-audit-remediation
blocks: []
blocked_by: []
created: 2026-06-10
updated: 2026-06-10
source_wiki_pages: []
linked_task: null
---

## Motivation

The 2026-06-10 Rust API audit found that `ripdpi-monitor-engine/src/engine/runtime_coordinator.rs:69` calls `handle.join().expect("parallel runner thread panicked")`. A panic in any single parallel runner thread propagates through the `.expect()` into the coordinator thread, killing the entire diagnostics scan session — one flaky probe takes down the whole scan. Related: `runtime_coordinator.rs:63` and `session/worker.rs:47` were flagged in the same family.

## Proposed change

1. Replace the `.expect()` at line 69 with explicit recovery: on `Err(payload)` from `join()`, log the panic payload (downcast to `&str`/`String` where possible) and record that runner's outcome as `RunnerOutcome::Cancelled`/`Failed` so the scan continues with the remaining runners' results.
2. Review lines 63 and `session/worker.rs:47` for the same pattern; apply consistent recovery.
3. Ensure a single runner panic is surfaced in the scan report (so it is diagnosable) but does not abort sibling runners.

## Acceptance criteria

- [ ] PR confirms current state at `runtime_coordinator.rs:63,69` and `session/worker.rs:47`.
- [ ] A panicking runner thread no longer aborts the scan; its outcome is recorded as failed/cancelled and other runners complete.
- [ ] The panic is logged/surfaced for diagnosability (payload captured).
- [ ] Test: inject a panic into one runner, assert the scan completes with the other runners' results and the failed runner is marked.
- [ ] `cargo nextest run -p ripdpi-monitor-engine --locked` green; clippy clean.

## Risks / open questions

- Decide whether a runner panic should fail the overall scan verdict or just that probe family — default to per-runner isolation, surfaced in the report.

## References

- Audit memory `project_native_audit_findings.md` (2026-06-10, item 13 / N3).
- `diagnostics-system` skill (ProbeTask families, RunnerOutcome).
