---
id: CIC-1795610815732127
title: Remove the KSP execution lock after the KSP race fix
kind: chore
status: backlog
area: ci
priority: low
owner: comment-ratchet
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-11-25
updated: 2026-11-25
spec_reason: mechanical-refactor
---

## Goal

`build-logic/convention/src/main/kotlin/ripdpi.android.hilt.gradle.kts` registers `kspExecutionLock`, a shared build service with `maxParallelUsages = 1`, so that all `KspAATask` runs are serial. A deleted comment said that this works around a race in KSP 2.3.13 and must go after google/ksp#3286 is fixed.

## Acceptance criteria

KSP is on a version that contains the fix for google/ksp#3286, the lock and its `usesService` call are deleted, and a parallel clean build passes.
