---
id: DGN-1786478954023338
title: Export detection verdict evidence in home diagnostics
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
status_detail: Implemented evidence aggregation, fail-closed detection mapping, structured archive export, and regression coverage.
closed_at: "2026-08-11T20:41:40Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: ":app:testGithubFullDebugUnitTest; :core:diagnostics:testDebugUnitTest (1272 tests); staticAnalysis; check_architecture_health.py; cargo metadata --locked"
---

## Goal

Ensure the home diagnostics detection stage exports the concrete evidence that supports its verdict and fails closed when a detected verdict has no supporting proof.

## Acceptance criteria

- Evidence-only detection signals are included in the exported home-stage findings and signal count.
- Findings and evidence from every detection category considered by the verdict evaluator are eligible for export.
- A `DETECTED` home-stage verdict cannot be returned with an empty evidence list.
- `home-analysis.json` includes the detection verdict and its supporting findings as structured fields.
- Focused app and diagnostics tests plus repository static analysis pass.
