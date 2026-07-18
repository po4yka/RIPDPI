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
- [x] Targeted tests, Roborazzi checks, variant builds, and Pixel 7 journeys pass.
- [x] Independent review finds no unresolved P1 or P2 regression in the changed slice.
- [ ] The atomic commits are rebased onto current `origin/main`, integrated, pushed, and verified by exact remote SHA.

## Work log

- 2026-07-18: Revalidated the audit against `origin/main`; both P1 findings still reproduce in current source.
- 2026-07-18: Added Full titles, Up navigation, scrolling, Compose coverage, and three reviewed Roborazzi fixtures for Packet captures and Past replays.
- 2026-07-18: Added Simple STARTING/RUNNING/terminal progress, cancellation, disabled conflicting connection controls, scrolling, live-region semantics, localized status text, and regression coverage.
- 2026-07-18: Hardened home-run admission, session-scoped cancellation, parallel-stage progress ownership, partial-report persistence, and DNS-corrected reprobe ownership after independent review.
- 2026-07-18: Targeted app/core tests, affected lint/detekt, architecture health, and narrow Full/Simple Roborazzi verification passed. Remaining risk is limited to the required rebased-tree gates, final APK builds, repeated Pixel 7 journeys, and remote integration evidence.
- 2026-07-18: Rebased-tree tests, lint/detekt, architecture health, locked Cargo metadata, and all four narrow Roborazzi fixtures passed. Native `githubFullDebug` and `githubSimpleDebug` APKs were built and verified at SHA-256 `962996fa965c588642c80e36f8a4c718ed8109d7f490a43632fffbdae078d2e2` and `8c3a9c6d28dee13e64a2cee84d3a29342af2367d3ed0e82489d17b2ea6b995e7`.
- 2026-07-18: Independent combined-diff review finished with P1=0 and P2=0 after two follow-up fixes. The exact APKs passed all 14 Pixel 7 checks, including Full Up/Back, compact layouts, Simple progress/cancel, font scale 1.5, Arabic RTL, dark theme, unified status semantics, crash/ANR scan, and device-state restoration.

## Golden rationale

The user explicitly authorized updating Roborazzi goldens in this conversation. The three Full fixtures intentionally add the visible `Packet captures` / `Past replays` top app bars and opaque screen background; the Simple fixture intentionally records the new in-progress report state with a disabled Connect action and enabled Cancel action. Dedicated golden reviewers inspected every expected/actual image, found no volatile fields or unrelated drift, and reported exact zero-pixel verification diffs.
