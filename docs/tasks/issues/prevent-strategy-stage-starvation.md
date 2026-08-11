---
id: DGN-1786474501506331
title: Prevent strategy stage starvation under scan deadlines
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
created: 2026-08-11
updated: 2026-08-11
spec_reason: regression-tested-single-module
related_tasks: []
status_detail: Adaptive stage slices implemented; focused and full monitor-engine tests, clippy, rustfmt, staticAnalysis, and read-only review passed.
closed_at: "2026-08-11T19:28:45Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: "cargo test -p ripdpi-monitor-engine --locked: 169 unit tests plus integration suites passed; cargo clippy -p ripdpi-monitor-engine --locked --all-targets -- -D warnings passed; cargo fmt --all -- --check passed; ./gradlew staticAnalysis passed (722 tasks); read-only PR review clean"
---

## Goal

Ensure an early strategy lane cannot consume the entire scan deadline and prevent later planned lanes from running.

## Acceptance criteria

- Each runnable strategy stage receives an adaptive slice of the remaining global deadline.
- Unused time from a stage is available to later stages, while the global scan deadline remains a hard upper bound.
- A TCP stage that uses its complete slice does not prevent a later QUIC stage and recommendation stage from running.
- Focused monitor-engine regression tests, full crate tests, formatting, clippy, and repository static analysis pass.
