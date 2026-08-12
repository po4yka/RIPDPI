---
id: DGN-1786563985653994
title: Qualify direct-mode verdict under incomplete evidence
kind: bug
status: review
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
status_detail: Rejected strategy evidence now suppresses every categorical direct-mode verdict; RED/GREEN regression, diagnostics suite, and static analysis pass.
---

## Goal

Do not export a categorical direct-mode verdict when the strategy audit is rejected for incomplete or weak evidence.

## Acceptance criteria

- A rejected strategy audit suppresses `NO_DIRECT_SOLUTION` just as it already suppresses `TRANSPARENT_WORKS`.
- A reliable strategy audit may still export its derived direct-mode verdict.
- The regression test, diagnostics module tests, static analysis, architecture health, and task validation pass.
