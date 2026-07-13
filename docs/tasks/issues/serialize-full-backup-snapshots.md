---
title: "Serialize full backup snapshots"
type: task
status: todo
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

Capture FULL backup profiles, active IDs, and credentials from one serialized logical snapshot.

## Acceptance criteria

- A concurrent activation test cannot produce an active ID absent from the exported profile set.
- Export and relevant mutations share one serialization boundary.
- `:core:data:testDebugUnitTest` passes.
