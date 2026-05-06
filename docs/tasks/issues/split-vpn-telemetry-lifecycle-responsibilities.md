---
title: Split VPN telemetry lifecycle responsibilities
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

- [ ] #task Split VPN telemetry lifecycle responsibilities #repo/RIPDPI #area/service #status/backlog ⏫

## Summary

`VpnTelemetryCoordinator` is not just telemetry: it monitors protect failures,
rebuilds the VPN tunnel for DNS refreshes, runs encrypted-DNS recovery,
classifies fatal runtime failures, updates status, and stops the service. Split
telemetry polling, DNS policy recovery, and service lifecycle failure handling.

## Audit citation

- `core/service/src/main/kotlin/com/poyka/ripdpi/services/VpnTelemetryCoordinator.kt` lines 76-153.

## Scope

- In scope: lifecycle stop/fatal-failure handling, DNS-policy tunnel refresh,
  telemetry collection, and status reporting boundaries.
- Out of scope: changing foreground service behavior or telemetry event schemas.

## Acceptance criteria

- [ ] Telemetry coordinator only collects and publishes telemetry state.
- [ ] Fatal runtime failure handling and service stop policy move to lifecycle
    ownership.
- [ ] DNS policy tunnel refresh moves to DNS/tunnel coordination ownership.
- [ ] Unit tests cover the split collaborators and existing service tests stay
    green.

## Links

- [[Epic - Finish SRP residual architecture debt]]
