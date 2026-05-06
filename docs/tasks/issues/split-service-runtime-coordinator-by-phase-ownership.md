---
title: Split service runtime coordinator by phase ownership
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

- [ ] #task Split service runtime coordinator by phase ownership #repo/RIPDPI #area/service #status/backlog ⏫

## Summary

`ServiceRuntimeCoordinator` centralizes lifecycle start/stop, permission
watchdog, telemetry-loop ownership, network-handover retry/backoff, shared
proxy-stack start/stop, and the generic base coordinator. Split runtime session
orchestration so handover policy, permissions, telemetry, and stack lifecycle no
longer share one service-layer kernel.

## Audit citation

- `core/service/src/main/kotlin/com/poyka/ripdpi/services/ServiceRuntimeCoordinator.kt` lines 53-322.

## Scope

- In scope: lifecycle phase orchestration, permission watchdog ownership,
  telemetry-loop scheduling, network-handover retry/backoff, and shared
  proxy-stack start/stop boundaries.
- Out of scope: changing VPN/proxy runtime behavior or foreground-service
  policy.

## Acceptance criteria

- [ ] Start/stop lifecycle orchestration is separated from permission watchdog
    and handover retry policy.
- [ ] Telemetry-loop ownership moves behind a narrow collaborator.
- [ ] Shared proxy-stack start/stop has a focused owner.
- [ ] Existing service lifecycle tests stay green or are expanded for the new
    collaborators.

## Links

- [[Epic - Finish SRP residual architecture debt]]
