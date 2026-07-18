---
title: Fix Pixel 7 UI audit P1 findings
type: task
status: review
area: ui
priority: high
owner: Codex
parent: null
blocks: []
blocked_by: []
created: 2026-07-18
updated: 2026-07-18
---

## Goal

Close every P1 finding from the Pixel 7 UI/UX audit in both GitHub app variants.

## Scope

- Give Packet captures and Past replays a visible title and an Up action while preserving system Back, deep links, and their existing transitions.
- Make the Simple diagnostic report show current progress and status, expose cancellation, announce state changes accessibly, and prevent conflicting connection actions while it runs.
- Add regression coverage, render affected UI, and verify both variants on the connected Pixel 7.

## Ship definition

- [x] Full-variant secondary diagnostic screens have tested context and Up navigation.
- [x] Simple-variant diagnostics have tested progress, cancellation, terminal-state, and control-conflict behavior.
- [ ] Targeted tests, Roborazzi checks, variant builds, and Pixel 7 journeys pass.
- [ ] Independent review finds no unresolved P1 or P2 regression in the changed slice.
- [ ] The atomic commits are rebased onto current `origin/main`, integrated, pushed, and verified by exact remote SHA.

## Work log

- 2026-07-18: Revalidated the audit against `origin/main`; both P1 findings still reproduce in current source.
- 2026-07-18: Added Full titles, Up navigation, scrolling, Compose coverage, and three reviewed Roborazzi fixtures for Packet captures and Past replays.
- 2026-07-18: Added Simple STARTING/RUNNING/terminal progress, cancellation, disabled conflicting connection controls, scrolling, live-region semantics, localized status text, and regression coverage.
- 2026-07-18: Hardened home-run admission, session-scoped cancellation, parallel-stage progress ownership, partial-report persistence, and DNS-corrected reprobe ownership after independent review.
- 2026-07-18: Targeted app/core tests, affected lint/detekt, architecture health, and narrow Full/Simple Roborazzi verification passed. Remaining risk is limited to the required rebased-tree gates, final APK builds, repeated Pixel 7 journeys, and remote integration evidence.

## Golden rationale

The user explicitly authorized updating Roborazzi goldens in this conversation. The three Full fixtures intentionally add the visible `Packet captures` / `Past replays` top app bars and opaque screen background; the Simple fixture intentionally records the new in-progress report state with a disabled Connect action and enabled Cancel action. Dedicated golden reviewers inspected every expected/actual image, found no volatile fields or unrelated drift, and reported exact zero-pixel verification diffs.
