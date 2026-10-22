---
id: DAT-1792661188293948
title: Keep verified reserve profiles available offline
kind: feature
status: done
area: data
priority: high
owner: Offline reserves
parent: null
blocked_by: []
spec_mode: required
openspec_change: offline-reserve-profiles
created: 2026-10-22
updated: 2026-10-22
closed_at: "2026-10-22T11:39:01Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Source 5be355fd0397dfea71f7e68ec790a29c568be174 passed full affected JVM job119791123903, coverage119791124405 and static119790735839; encrypted reserve reload, failure-safe refresh and real TLS/SOCKS offline selection tests passed. No platform-keystore/device/deployment claim.
---

## Goal

Retain accepted encrypted reserve profiles across offline restart and rejected refresh, then qualify failover with actual HTTPS payloads.

## Acceptance criteria

- Offline reload preserves complete member credentials, payload policy and selected profile.
- Invalid/obsolete refresh cannot overwrite or falsely validate saved reserves.
- Corrupt sealed storage fails without legacy fallback or destructive overwrite.
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
