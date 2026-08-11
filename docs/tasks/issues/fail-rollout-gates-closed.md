---
id: DGN-1786467480568194
title: Fail rollout gates closed without evidence
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
status_detail: Rollout gates now use maximum retry evidence from the telemetry window and require verified runtime capabilities; regression and full static gates pass.
closed_at: "2026-08-11T17:15:31Z"
closed_reason: Rollout gates fail closed on historical instability and unverified runtime capabilities.
evidence_summary: "RED: focused renderer test exposed acceptance+instability+android passes; GREEN: DiagnosticsArchiveRendererTest passed; ./gradlew :core:diagnostics:testDebugUnitTest staticAnalysis --quiet exited 0."
---

## Goal

Fail rollout quality gates when telemetry history shows instability or required runtime capabilities remain unknown.

## Acceptance criteria

- Instability uses the maximum retry count across the exported telemetry window rather than only the newest sample.
- Android compatibility passes only when the execution plan exists and every required capability has a verified available status.
- Diagnostics unit tests and static analysis pass.
