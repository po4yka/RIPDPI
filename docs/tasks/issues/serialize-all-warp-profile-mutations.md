---
title: "Serialize all WARP profile mutations"
type: task
status: review
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

## Work log

- Routed late authentication failure mutation through the singleton WARP store lock.
- Re-reads current metadata after acquiring the lock and exits when a concurrent reset already deleted the profile.
- Added a deterministic race test proving the stale failure cannot resurrect deleted metadata.
