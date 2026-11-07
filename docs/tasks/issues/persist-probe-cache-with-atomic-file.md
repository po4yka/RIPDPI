---
id: DGN-1794036642254618
title: Persist the probe cache with AtomicFile
kind: chore
status: todo
area: diagnostics
priority: medium
owner: Standard-library migration
parent: EPC-1794036260770217
blocked_by: []
spec_mode: required
openspec_change: persist-probe-cache-with-atomic-file
created: 2026-11-07
updated: 2026-11-07
---

## Goal

An interrupted probe-cache write must not destroy the previous complete cache.

## Audit evidence

Audit finding 12 (attachment 5), 2026-11-07, baseline 202f970bb3574980dd2eb87fea002cac8fbe29f7. This is a confirmed replacement candidate, not proof that an implementation is complete. Recheck callers and tests before editing.

## Scope

Use the existing Android AtomicFile pattern for both reads and writes, with finishWrite and failWrite.

Keep the mutex, 24-hour TTL, 10-entry limit, JSON shape, and safe fallback for an already corrupt file. No database or schema migration.

## Acceptance criteria

- A completed cache write survives reopen with the same entries and timestamps.
- An interrupted or failed replacement leaves the previous valid cache readable through AtomicFile.
- Existing JSON caches load without migration; expiry, entry limit, and corrupt-file fallback remain unchanged.
- Extend owning cache tests for old-file retention after failed write, successful replacement/readback, corrupt input, TTL, and eviction boundaries.
- Named local gates and repository-required independent review pass; record exact implementation SHA and command results. Commit each complete atomic implementation unit on main.

## Dependencies and order

Priority band 2; recommended position 12 in the epic queue. Prerequisites: None. Respect the epic priority and shared-file queue.

No dependency is implied by priority alone. Shared Cargo and Gradle files use one writer; the epic's queue prevents concurrent edits without introducing false semantic blockers.

## Ownership

- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/ProbeResultCache.kt`

The same writer owns related module tests, direct dependency declarations, and task artifacts. Shared dependency and generated-output lanes follow the epic. The user directed work on main on 2026-11-07. Work directly on main and commit each completed atomic unit. This instruction overrides worktree-isolation guidance for this programme. Do not push, publish, or deploy without separate authorization.

## Dependency decision

Use only existing platform or workspace libraries. Check the direct module dependency before editing.

## Verification

- `./gradlew :core:diagnostics:testDebugUnitTest`
- `just ci-gates`

Run the named targeted checks first. Run staticAnalysis for Kotlin cross-module or security changes and just ci-gates for affected source contracts. Native dependency changes also require locked metadata and cargo-deny. Obtain the repository-required independent review; add async, JNI, or unsafe review only when the actual diff meets those rules. Record exact commands, results, and the implementation SHA. Commit only the finished task slice on main.
