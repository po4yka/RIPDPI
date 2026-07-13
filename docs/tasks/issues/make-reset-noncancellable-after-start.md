---
title: "Make reset noncancellable after start"
type: task
status: review
area: data
priority: critical
owner: Codex
parent: epic-fix-android-critical-residual-risks
blocks: []
blocked_by: []
created: 2026-07-13
updated: 2026-07-13
---

## Goal

Once destructive reset starts, complete the erasure despite caller or `viewModelScope` cancellation.

## Acceptance criteria

- A cancellation test proves all reset phases complete after cancellation.
- UI completion is delivered only if its owner remains active, without interrupting erasure.
- Focused app reset tests pass.

## Work log

- Wrapped the full destructive reset transaction in `NonCancellable`; caller cancellation can suppress UI completion but cannot leave a partially wiped device.
- Added a gate-controlled cancellation test that cancels during rule deletion and verifies every later phase still completes.
