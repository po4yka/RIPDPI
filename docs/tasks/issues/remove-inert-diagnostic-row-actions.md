---
id: DGN-1790339654179112
title: Remove inert diagnostic row actions
kind: bug
status: review
area: diagnostics
priority: medium
owner: Diagnostics UI
parent: null
blocked_by: []
spec_mode: required
openspec_change: remove-inert-diagnostic-row-actions
created: 2026-09-25
updated: 2026-09-25
status_detail: Diagnostic and history row Compose semantics tests, app lint, and staticAnalysis passed; device and remote CI pending.
---

## Goal

Diagnostic rows with no action are presented as information, while rows with a detail action remain interactive.

## Acceptance criteria

- Live passive events, overview warnings, latest scan, recent approach sessions and probes, and history detail events do not expose a click action when none exists.
- Session and event detail rows with real callbacks remain clickable.
- Focused Compose semantics tests and app lint pass.

## Ownership

- Diagnostics UI writer owns diagnostic row call sites, `HistoryCards.kt`, `HistoryDetailSheets.kt`, focused tests, and this task/OpenSpec change.
- `docs/tasks/board.md` is generated and serialized at integration. No locale resource changes.
