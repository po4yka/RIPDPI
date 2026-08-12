---
id: DGN-1786565526520878
title: Exclude incomplete scans from approach validation
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
created: 2026-08-13
updated: 2026-08-13
spec_reason: regression-tested-single-module
related_tasks: []
status_detail: Incomplete reports no longer contribute validation evidence; RED/GREEN regression, diagnostics suite, static analysis, architecture health, and task validation pass.
---

## Goal

Ensure approach summaries report `verificationState=validated` only when at least one complete diagnostic report is eligible as validation evidence.

## Acceptance criteria

- `PARTIAL_RESULTS` and `TERMINATED` reports do not increment validated scan or success metrics and cannot produce a validated verification state.
- A normal completed report remains eligible and preserves existing success classification.
- An observed RED/GREEN unit regression, the affected module suite, static analysis, task validation, and architecture health pass.
