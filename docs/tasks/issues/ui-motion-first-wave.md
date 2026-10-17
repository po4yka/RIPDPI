---
id: UIX-1792222949017657
title: Add clear connection and diagnostics feedback
kind: feature
status: doing
area: ui
priority: high
owner: UI motion integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: ui-motion-first-wave
created: 2026-10-17
updated: 2026-10-17
---

## Goal

Make pending actions, completed diagnostics, and recent events clear without unnecessary motion.

## Acceptance criteria

- Disabled animations settle immediately; reduced motion has no bounce or infinite motion.
- The disconnect confirmation shows its real remaining four-second window and never changes the action deadline.
- Diagnostics changes from an active run to its actual result once, without success feedback for failure or cancellation.
- Events keep the reader position while new records arrive and offer a visible Jump to latest action away from the top.
- All new labels exist in all ten locales, with usable font scale 2.0 and RTL layouts.
- Targeted behavior tests, static analysis, locale lint, combined architecture checks, and emulator acceptance pass.
- Independent review, rebase, ff-only integration into main, and the authorized remote push are observed.

## Ownership

- Integration owner: locale resource sets, RipDpiTestTags.kt, task/OpenSpec files, combined tests, device checks, review fixes, and Git integration.
- Motion worker: RipDpiMotion.kt, RipDpiConnectionActuator.kt, their focused helpers and tests, in RIPDPI-motion-controls. No locale or shared tag writes.
- Diagnostics worker: DiagnosticsOutcomeCard.kt, directly related presentation helpers and tests, in RIPDPI-motion-diagnostics. No locale or shared tag writes.
- History worker: HistorySections.kt, directly related event-list presentation helpers and tests, in RIPDPI-motion-history. No locale or shared tag writes.
- Review agents are read-only. Each writer has its own worktree. All baseline, golden, build, and native contract files remain outside scope.

## Design references and limits

Use docs/design/rds/preview/motion-reduced-motion.html, vpn-confirm-disconnect.html,
motion-connection-states.html, motion-page-transitions.html, and the existing Compose theme.
No exact RDS sheet exists for Jump to latest: use the existing button and panel roles.
Keep the current disconnect double-press behavior and cancellation availability.
Do not invent network progress or change runtime, storage, or wire contracts.
