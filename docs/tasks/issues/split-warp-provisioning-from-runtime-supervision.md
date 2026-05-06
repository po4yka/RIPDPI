---
title: Split WARP provisioning from runtime supervision
type: task
status: backlog
area: service
priority: high
owner: unassigned
parent: epic-finish-srp-residual-architecture-debt
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [ ] #task Split WARP provisioning from runtime supervision #repo/RIPDPI #area/service #status/backlog ⏫

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

- [ ] Provisioning and endpoint/credential refresh move behind a focused
    resolver.
- [ ] Runtime start/readiness/exit supervision is owned by a process supervisor.
- [ ] Startup failure mapping is testable without starting the runtime process.
- [ ] Existing WARP runtime tests stay green or are expanded for the split.

## Links

- [[Epic - Finish SRP residual architecture debt]]
