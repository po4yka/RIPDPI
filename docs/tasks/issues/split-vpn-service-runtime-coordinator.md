---
title: Split VpnServiceRuntimeCoordinator into focused coordinators
type: task
status: backlog
area: service
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-05
updated: 2026-05-05
---

- [ ] #task Split VpnServiceRuntimeCoordinator into focused coordinators #repo/RIPDPI #area/service #status/backlog ⏫

## Objective

Break `VpnServiceRuntimeCoordinator` apart so each coordinator owns one lifecycle axis and the coordinator only holds a narrow VPN session facade.

## Context

`VpnServiceRuntimeCoordinator` currently injects and coordinates DNS failover, VPN tunnel runtime, upstream relay, WARP, proxy runtime, protect-failure monitoring, status reporting, screen observation, and direct-path telemetry. This keeps service lifecycle, network policy, telemetry, and multiple transport runtimes changing through one coordinator.

Source: `core/service/src/main/kotlin/com/poyka/ripdpi/services/VpnServiceRuntimeCoordinator.kt:3+`

## Acceptance criteria

- [ ] Extract a `TunnelRuntimeCoordinator` owning VPN tunnel startup/teardown.
- [ ] Extract a `ProxyStackCoordinator` owning proxy runtime and upstream relay lifecycle.
- [ ] Extract a `DnsPolicyCoordinator` owning DNS failover and DNS path transitions.
- [ ] Extract a `TelemetryCoordinator` owning status reporting, screen observation, and direct-path telemetry.
- [ ] `VpnServiceRuntimeCoordinator` becomes a narrow VPN session facade that delegates to the four above.
- [ ] No behavioral change: existing integration tests pass unchanged.

## Definition of done

All four coordinators exist with unit tests; the facade compiles; existing service tests green.
