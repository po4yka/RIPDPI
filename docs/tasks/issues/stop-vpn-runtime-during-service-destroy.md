---
title: "Stop VPN runtime during service destroy"
type: task
status: todo
area: vpn
priority: critical
owner: Codex
parent: epic-fix-android-critical-residual-risks
blocks: []
blocked_by: []
created: 2026-07-13
updated: 2026-07-13
---

## Goal

Make direct `Service.onDestroy()` perform a bounded runtime stop before unregistering native and socket protection.

## Acceptance criteria

- A lifecycle test invokes destroy without `ACTION_STOP` while a fake runtime is active.
- Runtime stop completes before native protect and Unix socket cleanup.
- `:core:service:testDebugUnitTest` passes.
