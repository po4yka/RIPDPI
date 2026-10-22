---
id: DAT-1792661188293948
title: Keep verified reserve profiles available offline
kind: feature
status: doing
area: data
priority: high
owner: Offline reserves
parent: null
blocked_by: []
spec_mode: required
openspec_change: offline-reserve-profiles
created: 2026-10-22
updated: 2026-10-22
---

## Goal

Retain accepted encrypted reserve profiles across offline restart and rejected refresh, then qualify failover with actual HTTPS payloads.

## Acceptance criteria

- Offline reload preserves complete member credentials, payload policy and selected profile.
- Invalid/obsolete refresh cannot overwrite or falsely validate saved reserves.
- Corrupt sealed storage fails without legacy fallback or destructive overwrite.
- Cold service startup explicitly loads the catalogue before awaiting selector readiness; unreadable storage fails preparation and remains retryable.
- Local real HTTPS/SOCKS checks select a working reserve and reject wrong content.
- Relevant JVM/static/task gates pass; no server deployment is performed.

## Ownership

- Parent owns this portfolio, OpenSpec, docs, final integration and offline integration tests.
- Refresh worker owns app subscription refresh code/tests, then guarded startup and deferred ProxyGroupStores loading after persistence handoff.
- Persistence worker initially owns ProxyGroupStores and persistence tests, then hands off that file and owns the shared encrypted editor transaction and offline socket integration test.
- Task files, dependencies, schemas and shared generated assets have only the parent writer.
- Reuse TRN-1792659369802828 payload verification through its existing PR dependency.

## Combined review ownership

- Parent owns combined integration, SHARE backup redaction, its tests and all task/specification files.
- Queue worker owns selector round scheduling and its focused concurrency tests, with service probe changes only if the shared-capacity boundary requires them.
- Refresh worker owns malformed-policy rejection before subscription side effects and its app/parser regression tests.
- Workers use separate worktrees from the merged client branch; no shared schema, dependency, runtime model or generated file has multiple writers.

## Acceptance incident — 2026-10-22

The prior closure was premature. Final CI 39772247126 on
`b0b48501b738295bc0597ab5fb6815c0975d477f` failed service-start instrumentation
on API 27, 33, 35, 36 and 37: all 24 ServiceLifecycleIntegrationTest cases
timed out before Running, and two SelectorRuntimeInstrumentedTest native utility
cases timed out before measurement on each API. The deferred group snapshot contract left selector lifecycle
preparation waiting for a seed before its first validated catalogue load.

The three closure commits were reverted with ordinary commits, preserving their
receipts in history. This task remains doing until explicit cold-start loading,
read-failure propagation, affected JVM/static checks and the complete required
instrumentation matrix pass. No timeout, assertion or safety gate may be weakened.
