---
title: "Serialize all WARP profile mutations"
type: task
status: todo
area: service
priority: critical
owner: Codex
parent: epic-fix-android-critical-residual-risks
blocks: []
blocked_by: []
created: 2026-07-13
updated: 2026-07-13
---

## Goal

Route `markProfileNeedsAttention()` and every WARP metadata, credential, endpoint, and active-profile mutation through the shared lock.

## Acceptance criteria

- A race test proves a late authentication failure cannot resurrect a deleted metadata profile.
- All WARP mutation entrypoints use the same lock owner.
- `:core:service:testDebugUnitTest` passes.
