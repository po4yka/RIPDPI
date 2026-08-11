---
id: DGN-1786461308570121
title: Serialize home diagnostic stages
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
status_detail: Serialized composite stages; RED observed 4 concurrent scans, regression and module tests pass
closed_at: "2026-08-11T15:32:23Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: RED reproduced four concurrent profile scans; serialization regression passed; composite-run tests passed; core diagnostics unit tests passed; staticAnalysis passed
---

## Goal

Describe the observable outcome.

## Acceptance criteria

Define verifiable completion criteria.
