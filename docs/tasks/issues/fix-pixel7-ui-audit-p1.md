---
title: Fix Pixel 7 UI audit P1 findings
type: task
status: doing
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

- [ ] Full-variant secondary diagnostic screens have tested context and Up navigation.
- [ ] Simple-variant diagnostics have tested progress, cancellation, terminal-state, and control-conflict behavior.
- [ ] Targeted tests, Roborazzi checks, variant builds, and Pixel 7 journeys pass.
- [ ] Independent review finds no unresolved P1 or P2 regression in the changed slice.
- [ ] The atomic commits are rebased onto current `origin/main`, integrated, pushed, and verified by exact remote SHA.

## Work log

- 2026-07-18: Revalidated the audit against `origin/main`; both P1 findings still reproduce in current source.
