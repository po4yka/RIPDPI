---
id: DGN-1786477253910681
title: Calibrate connectivity assessment causal language
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
status_detail: Calibrated four connectivity assessment summaries and the selective-failure next action; four regression tests, full diagnostics tests, staticAnalysis, architecture health, task validation, and locked Cargo metadata pass.
closed_at: "2026-08-11T19:57:42Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Four regression tests and the full core diagnostics unit suite pass; repository staticAnalysis, architecture health, task validation, and locked Cargo metadata pass.
---

## Goal

Ensure connectivity assessments describe observed evidence and candidate explanations without presenting correlation as a proven cause.

## Acceptance criteria

- Resolver divergence with mixed controls and no paired in-path evidence does not claim DNS interference is the likely cause.
- Selective raw-path failures are not presented as confirmed policy filtering or reachability failure without corroborating evidence.
- Assessment summaries retain actionable evidence and explicitly state the remaining causal uncertainty.
- Focused and full diagnostics tests plus repository static analysis pass.
