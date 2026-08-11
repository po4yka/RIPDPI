---
id: DGN-1786459542010496
title: Preserve partial strategy report at scan deadline
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
status_detail: Implementation and native regression validation complete
closed_at: "2026-08-11T15:04:01Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: RED reproduced missing TCP-only deadline report; focused regression passed; 184 monitor-engine tests passed; Clippy -D warnings passed; staticAnalysis passed
---

## Goal

Preserve already collected strategy candidate evidence when the scan deadline expires before every strategy lane runs.

## Acceptance criteria

- A deadline-expired strategy scan with TCP candidate evidence and no completed QUIC lane contains a strategy probe report.
- The preserved report is marked `PARTIAL_RESULTS`, retains observed candidates, and does not fabricate executed QUIC candidates.
- The focused native regression test, the monitor-engine crate tests, and repository static analysis pass.
