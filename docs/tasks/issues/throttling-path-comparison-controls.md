---
id: DGN-1786464858515872
title: Use throttling controls for path comparison
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
status_detail: Included ru_throttling reports in path-comparison selection; focused regression and full diagnostics/static analysis pass.
closed_at: "2026-08-11T16:24:44Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: "RED: focused throttling-selection test failed at requireNotNull before implementation. GREEN: focused test and PathComparisonSelectionBuilderTest class passed. Combined gate: ./gradlew :core:diagnostics:testDebugUnitTest staticAnalysis completed BUILD SUCCESSFUL."
---

## Goal

Run the focused in-path comparison when the throttling stage provides successful controls and a failed target.

## Acceptance criteria

- Raw-path evidence from `ru_throttling` participates in path-comparison selection.
- A successful control plus a failed target from that stage produces a non-empty comparison selection.
- Diagnostics unit tests and static analysis pass.
