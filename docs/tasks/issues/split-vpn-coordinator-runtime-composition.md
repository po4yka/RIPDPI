---
title: Split VPN coordinator runtime composition
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

- [ ] #task Split VPN coordinator runtime composition #repo/RIPDPI #area/service #status/backlog ⏫

## Summary

`VpnServiceRuntimeCoordinator` still composes proxy stack, tunnel runtime, DNS
policy, protect-failure monitoring, supervisor exit handling, telemetry
callbacks, active policy application, and runtime startup. Split construction
and startup wiring so VPN mode is not the convergence point for unrelated
runtime families.

## Audit citation

- `core/service/src/main/kotlin/com/poyka/ripdpi/services/VpnServiceRuntimeCoordinator.kt`.

## Scope

- In scope: VPN runtime composition, proxy-stack wiring, tunnel runtime wiring,
  DNS policy coordination, protect-failure monitoring, supervisor exit handling,
  telemetry callbacks, active policy application, and startup orchestration.
- Out of scope: changing active policy semantics or tunnel runtime behavior.

## Acceptance criteria

- [ ] VPN coordinator delegates runtime-family construction to focused
    factories or composition owners.
- [ ] DNS policy, protect-failure monitoring, telemetry callbacks, and
    supervisor exit handling have independent owners.
- [ ] Runtime startup consumes a composed session contract rather than
    constructing unrelated families inline.
- [ ] Existing VPN service tests stay green or are expanded for the split.

## Links

- [[Epic - Finish SRP residual architecture debt]]
