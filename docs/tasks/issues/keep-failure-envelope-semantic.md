---
id: DGN-1786483004046842
title: Keep failure envelope limited to classified failures
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
closed_at: "2026-08-11T21:31:25Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Architecture health, Cargo metadata, task contracts, and git diff checks passed.
---

## Goal

Keep `analysis.json.failureEnvelope` restricted to structured telemetry failure
classes instead of treating every warning or error event as a failure-class
transition.

## Acceptance criteria

- Warning and error session events do not populate failure timestamps,
  `latestFailureClass`, or `failureClassTransitions` by themselves.
- Structured telemetry failure classes and retry counters remain exported.
- Regression tests and repository static analysis pass.
