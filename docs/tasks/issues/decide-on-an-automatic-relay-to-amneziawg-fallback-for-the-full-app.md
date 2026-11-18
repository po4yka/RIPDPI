---
id: SVC-1795015549149698
title: Decide on an automatic relay-to-AmneziaWG fallback for the full app
kind: research
status: backlog
area: service
priority: low
owner: unassigned
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-11-18
updated: 2026-11-18
spec_reason: research-only
---

## Goal

Decide whether the full application needs an automatic fallback from relay servers to the AmneziaWG device VPN. The `simple` flavor had one (`FailoverCoordinator`); `AND-1795015211660121` removes it with the flavor, and the full application switches only between relay servers inside selector and urltest groups.

## Acceptance criteria

- A written decision: build the fallback, or record why selector and urltest failover is enough.
- The seams that only the `simple` flavor bound are either reused or removed: `ActiveTransportProvider`, `ExplicitUserStartPreparer`, `InitialRelayRacePolicy`, and the transport-failover apply path in `VpnServiceRuntimeCoordinator`.
- If the decision is to build it, a feature task with an OpenSpec change exists and this task links to it.
