---
title: "Prevent detection radio identifier upload"
type: task
status: doing
area: android
priority: critical
owner: Codex
parent: epic-fix-android-critical-residual-risks
blocks: []
blocked_by: []
created: 2026-07-13
updated: 2026-07-13
---

## Goal

Keep raw BSSID and cell identifiers on device unless a separately informed and explicit user contract authorizes transmission.

## Acceptance criteria

- A regression test proves a normal detection run does not send BSSID or cell IDs to BeaconDB.
- Detection retains useful local signals without leaking raw radio identifiers.
- `:core:detection:testDebugUnitTest` passes.
