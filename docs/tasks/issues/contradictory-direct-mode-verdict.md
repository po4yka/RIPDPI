---
id: DGN-1786466092652027
title: Reject contradictory direct-mode success
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
status_detail: Direct TLS attempts now take precedence over HTTP redirects in verdict classification; regression and full diagnostics/static analysis pass.
closed_at: "2026-08-11T16:41:48Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: "RED: focused mixed HTTP-success/TLS-failure test returned TRANSPARENT_WORKS before implementation. GREEN: focused test and DirectModePolicySupportTest class passed. Combined gate: ./gradlew :core:diagnostics:testDebugUnitTest staticAnalysis completed BUILD SUCCESSFUL."
---

## Goal

Do not report that direct mode works when HTTP succeeds but the matching direct TLS handshake fails.

## Acceptance criteria

- Direct TLS evidence takes precedence over an HTTP redirect for the same authority.
- A direct TLS handshake failure cannot produce `TRANSPARENT_WORKS` merely because HTTP succeeded.
- Diagnostics unit tests and static analysis pass.
