---
id: DGN-1795610468531248
title: Report failures of background detection checks
kind: chore
status: done
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
closed_at: "2026-11-25T14:39:51Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: The new regression failed without the Android diagnostics warning and then passed. It verifies the fixed warning has no Throwable, failed checks leave history empty, and a later check saves its result. All 453 detection tests, Ktlint, Detekt, and just ci-gates passed.
---

## Goal

`core/detection` `DetectionCheckScheduler.kt` catches every exception of a background check and reports nothing. The kept comment says "Silent failure for background check".

## Acceptance criteria

A failed background check is logged without private data or is recorded as a failed check. A test proves the failure is visible.
