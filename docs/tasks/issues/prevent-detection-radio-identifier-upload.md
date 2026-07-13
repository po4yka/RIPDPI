---
title: "Prevent detection radio identifier upload"
type: task
status: review
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

## Work log

- Removed the BeaconDB request path and endpoint from production code; detection now retains only local aggregate candidate counts.
- Added regression coverage that local radio candidate findings contain no BeaconDB result.
- Verified with `./gradlew :core:detection:testDebugUnitTest -Pripdpi.skipNativeBuild=true --console=plain`.
