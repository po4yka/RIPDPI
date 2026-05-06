---
title: Extract WARP bootstrap proxy runtime construction policy
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

- [x] #task Extract WARP bootstrap proxy runtime construction policy #repo/RIPDPI #area/service #status/done ⏫

## Summary

`ManagedWarpBootstrapProxyRunner` reserves a loopback port, snapshots app
settings, reconstructs proxy preferences with WARP-control-plane host
filtering, disables relay/WARP, creates a session-scoped proxy supervisor, and
starts/stops that runtime around enrollment. WARP enrollment should not know
proxy runtime construction mechanics.

## Audit citation

- `core/service/src/main/kotlin/com/poyka/ripdpi/services/WarpBootstrapProxyRunner.kt` lines 46-82.

## Scope

- In scope: bootstrap loopback port reservation, settings snapshotting,
  proxy-preference reconstruction, WARP control-plane host filtering,
  relay/WARP disabling, and bootstrap proxy supervisor ownership.
- Out of scope: changing WARP enrollment protocol behavior.

## Acceptance criteria

- [x] WARP enrollment delegates proxy runtime construction to a focused
    bootstrap proxy builder/runner contract.
- [x] WARP control-plane host filtering is expressed as bootstrap policy rather
    than inline proxy-preference mutation.
- [x] Relay/WARP disabling for enrollment is isolated and tested.
- [x] Bootstrap start/stop cleanup remains symmetric on failure and
    cancellation.

## Links

- [[Epic - Finish SRP residual architecture debt]]
