---
title: Split service session DI by runtime family
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

- [x] #task Split service session DI by runtime family #repo/RIPDPI #area/service #status/done ⏫

## Summary

`ServiceSessionComponents` wires proxy, VPN, bootstrap scopes, relay, WARP,
proxy, tunnel, DNS failover, protect-socket, status reporter, and coordinator
providers in one file. Split DI modules by runtime family so unrelated runtime
composition changes do not share one review surface.

## Audit citation

- `core/service/src/main/kotlin/com/poyka/ripdpi/services/ServiceSessionComponents.kt` lines 109 onward.

## Scope

- In scope: Hilt/service-session component modules, provider grouping, runtime
  family boundaries, and composition tests.
- Out of scope: changing provider implementations or service startup behavior.

## Acceptance criteria

- [x] Proxy, VPN, relay, WARP, tunnel, DNS failover, protect-socket, status, and
    coordinator providers are grouped by clear runtime-family ownership.
- [x] Adding a provider for one runtime family does not require editing a broad
    cross-runtime module.
- [x] DI graph tests or compile checks cover the split modules.
- [x] Existing service tests stay green.

## Links

- [[Epic - Finish SRP residual architecture debt]]
