---
id: DGN-1795610468531248
title: Report failures of background detection checks
kind: chore
status: backlog
area: diagnostics
priority: low
owner: comment-ratchet
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-11-25
updated: 2026-11-25
spec_reason: mechanical-refactor
---

## Goal

`core/detection` `DetectionCheckScheduler.kt` catches every exception of a background check and reports nothing. The kept comment says "Silent failure for background check".

## Acceptance criteria

A failed background check is logged without private data or is recorded as a failed check. A test proves the failure is visible.
