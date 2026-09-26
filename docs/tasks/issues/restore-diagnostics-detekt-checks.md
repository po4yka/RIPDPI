---
id: DGN-1790431078167248
title: Restore diagnostics detekt checks
kind: chore
status: review
area: diagnostics
priority: low
owner: Diagnostics
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-09-26
updated: 2026-09-26
spec_reason: mechanical-refactor
---

## Goal

Keep the diagnostics detekt gate green after the DoH survey change.

## Acceptance criteria

- Wrap the five overlong lines without changing behavior.
- `:core:diagnostics:detekt` passes.

## Parallel ownership

This worktree owns `DiagnosticsLocalNetworkPreflight.kt` and the two matching tests. Other parallel writers own export, finalization, dpi/dpich, and rkn paths.
