---
id: DGN-1786484399435246
title: Report effective strategy target selection in execution plans
kind: bug
status: done
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
status_detail: RED reproduced zero counts; strategy-only fallback implemented; focused, contract, golden, crate, clippy, fmt, and staticAnalysis gates passed.
closed_at: "2026-08-11T21:46:43Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: "RED: focused regression failed with (0,0) vs (2,1). GREEN: focused test passed. cargo nextest run --locked -p ripdpi-monitor-engine passed 187 tests with 2 skipped; cargo fmt --all -- --check passed; cargo clippy --locked -p ripdpi-monitor-engine --all-targets -- -D warnings passed; connectivity contract and JSON golden tests passed; ./gradlew staticAnalysis --console=plain passed."
---

## Goal

Execution-plan exports report the effective strategy domain and QUIC target
counts even when optional target-selection metadata is absent.

## Acceptance criteria

- A strategy-probe request with real domain and QUIC targets and no explicit
  target-selection metadata exports non-zero selected-target counts matching
  the effective request targets.
- Explicit target-selection metadata remains authoritative when present.
- The focused monitor-engine regression test, crate tests, Rust formatting and
  lint checks, and repository static analysis pass.
