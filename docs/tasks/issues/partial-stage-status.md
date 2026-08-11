---
id: DGN-1786457955593087
title: Treat partial diagnostics stages as incomplete
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
status_detail: Implementation and regression tests complete
closed_at: "2026-08-11T14:35:19Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: "Focused partial-results regression test passed; :core:diagnostics:testDebugUnitTest passed; staticAnalysis passed"
---

## Goal

Ensure diagnostics stages finalized with partial results are not reported as successfully completed.

## Acceptance criteria

- A completed scan lifecycle carrying `PARTIAL_RESULTS` is classified as a failed Home diagnostics stage.
- Partial stages are excluded from `completedStageCount` and included in the existing failed-stage accounting.
- The focused diagnostics unit test and repository static analysis pass.
