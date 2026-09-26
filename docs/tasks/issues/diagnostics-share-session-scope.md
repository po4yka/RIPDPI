---
id: DGN-1790430603321063
title: Keep diagnostics share summary scoped to selected session
kind: bug
status: review
area: diagnostics
priority: medium
owner: Diagnostics export agent
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-09-26
updated: 2026-09-26
spec_reason: regression-tested-single-module
---

## Goal

Sharing a selected diagnostics session must not include snapshots, context, or telemetry from another session when the selected session has no matching artifact.

## Acceptance criteria

- A regression test fails before the fix and passes after it.
- The selected-session share summary has no environment or telemetry section when those artifacts are absent for that session.
- The focused `:core:diagnostics` unit test passes.

## Parallel ownership

This task owns only `DiagnosticsShareSummaryBuilder.kt` and `DiagnosticsDetailAndShareServicesTest.kt` in `:core:diagnostics`. Other agents own all other diagnostics implementation and tests. No serialized shared files are changed.
