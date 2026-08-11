---
id: DGN-1786486903868923
title: Make diagnostic snapshots temporally explicit
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
created: 2026-08-12
updated: 2026-08-12
spec_reason: regression-tested-single-module
related_tasks: []
status_detail: Added telemetry capture timestamps and a redacted chronological runtime snapshot timeline; focused RED/GREEN regressions, 1277 diagnostics tests, and staticAnalysis pass.
closed_at: "2026-08-11T22:48:39Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: "Focused timestamp regression passed; ordered runtime timeline RED/GREEN regression passed; :core:diagnostics:testDebugUnitTest passed 1277 tests; staticAnalysis passed; archive golden diff reviewed and blessed."
---

## Goal

Diagnostics exports identify when each runtime state was captured, so service
states from assessment, scan-session, and passive-runtime phases are not
mistaken for simultaneous contradictory observations.

## Acceptance criteria

- Connectivity runtime assessment records the timestamp of the telemetry
  snapshot used to build it.
- `analysis.json` exports one ordered runtime-snapshot timeline that labels
  assessment, scan-session, and passive-runtime sources with capture times.
- Timeline entries expose only already-redacted service status and component
  health fields.
- A focused regression test distinguishes halted, degraded, and healthy states
  captured at different times; diagnostics tests and `staticAnalysis` pass.
