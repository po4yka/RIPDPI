---
title: Finish proxy coordinator runtime-family split
type: task
status: backlog
area: service
priority: high
owner: unassigned
parent: epic-clear-post-srp-architecture-regressions
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [ ] #task Finish proxy coordinator runtime-family split #repo/RIPDPI #area/service #status/backlog ⏫

## Summary

`ProxyServiceRuntimeCoordinator` remains the only current P2 architecture-health
indicator. It still composes relay, WARP, proxy runtime supervisors, supervisor
exit handling, telemetry coordination, active policy application, handover
restart, and status reporting.

## Audit citation

- `core/service/src/main/kotlin/com/poyka/ripdpi/services/ProxyServiceRuntimeCoordinator.kt`
  lines 52-85.
- Architecture-health indicator:
  `kotlin-feature-spread`, `featureFamilyCount=7`, limit `5`.

## Scope

- In scope: proxy-mode service orchestration, supervisor bundle ownership,
  telemetry delegation, handover restart delegation, and status/reporting
  boundaries.
- Out of scope: changing proxy runtime behavior, relay/WARP semantics, or VPN
  service orchestration.

## Acceptance criteria

- [ ] `ProxyServiceRuntimeCoordinator` no longer appears as a P2
    architecture-health indicator.
- [ ] Relay, WARP, proxy runtime lifecycle, telemetry, exit handling, and status
    projection are owned by focused collaborators.
- [ ] Proxy startup, stop, handover restart, and unexpected-exit behavior remain
    covered by existing or new unit tests.
- [ ] `python3 scripts/ci/check_architecture_health.py --check` passes without
    new or worsened entries.

## Links

- [[Epic - Clear post-SRP architecture regressions]]
