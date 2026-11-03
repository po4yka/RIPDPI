---
id: DGN-1790431078167248
title: Restore diagnostics detekt checks
kind: chore
status: done
area: diagnostics
priority: low
owner: Diagnostics
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-09-26
updated: 2026-11-04
spec_reason: mechanical-refactor
closed_at: "2026-11-03T20:00:29Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: "Observed at 9b2a2f478: ./gradlew :core:diagnostics:detekt passed (exit 0)"
---

## Goal

Keep the diagnostics detekt gate green after the DoH survey change.

## Acceptance criteria

- Wrap the five overlong lines without changing behavior.
- `:core:diagnostics:detekt` passes.

## Parallel ownership

This worktree owns `DiagnosticsLocalNetworkPreflight.kt` and the two matching tests. Other parallel writers own export, finalization, dpi/dpich, and rkn paths.
