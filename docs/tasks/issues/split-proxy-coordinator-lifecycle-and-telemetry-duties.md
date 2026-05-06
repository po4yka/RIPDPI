---
title: Split proxy coordinator lifecycle and telemetry duties
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

- [x] #task Split proxy coordinator lifecycle and telemetry duties #repo/RIPDPI #area/service #status/done ⏫

## Summary

`ProxyServiceRuntimeCoordinator` starts and restarts relay, WARP, and proxy
runtimes, polls all three telemetry sources, synthesizes tunnel telemetry,
updates status, and handles handover restart in one coordinator. Split proxy
mode lifecycle from telemetry and handover concerns.

## Audit citation

- `core/service/src/main/kotlin/com/poyka/ripdpi/services/ProxyServiceRuntimeCoordinator.kt`.

## Scope

- In scope: proxy-mode runtime lifecycle, relay/WARP/proxy restart handling,
  telemetry polling, tunnel telemetry synthesis, status updates, and handover
  restart ownership.
- Out of scope: changing proxy-mode feature behavior.

## Acceptance criteria

- [x] Runtime start/restart logic is separated from telemetry polling.
- [x] Tunnel telemetry synthesis has a focused owner.
- [x] Handover restart policy is delegated to handover ownership.
- [x] Proxy service tests cover lifecycle and telemetry paths independently.

## Links

- [[Epic - Finish SRP residual architecture debt]]
