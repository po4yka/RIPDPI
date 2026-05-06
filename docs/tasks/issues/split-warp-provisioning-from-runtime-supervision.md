---
title: Split WARP provisioning from runtime supervision
type: task
status: done
area: service
priority: high
owner: unassigned
parent: epic-finish-srp-residual-architecture-debt
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [x] #task Split WARP provisioning from runtime supervision #repo/RIPDPI #area/service #status/done ⏫

## Summary

`DefaultWarpRuntimeConfigResolver` refreshes WARP provisioning, loads
credentials, resolves endpoint selection, maps startup failures, and builds
native runtime config, while the same file owns runtime start/readiness/exit
supervision. Split provisioning policy from runtime process lifecycle so
endpoint and credential refresh changes do not share the supervisor surface.

## Audit citation

- `core/service/src/main/kotlin/com/poyka/ripdpi/services/WarpRuntimeSupervisor.kt` lines 38-183.

## Scope

- In scope: WARP provisioning refresh, credential loading, endpoint selection,
  startup failure mapping, native runtime config building, and
  start/readiness/exit supervision.
- Out of scope: changing WARP credentials format or native runtime protocol.

## Acceptance criteria

- [x] Provisioning and endpoint/credential refresh move behind a focused
    resolver.
- [x] Runtime start/readiness/exit supervision is owned by a process supervisor.
- [x] Startup failure mapping is testable without starting the runtime process.
- [x] Existing WARP runtime tests stay green or are expanded for the split.

## Links

- [[Epic - Finish SRP residual architecture debt]]
