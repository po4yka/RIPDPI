---
title: Add NetworkCallback reconnect and underlying-network tracking
type: task
status: backlog
area: vpn
priority: high
owner: unassigned
parent: epic-fail-closed-android-vpn-policy-engine
blocks: []
blocked_by: []
created: 2026-05-01
updated: 2026-05-01
---

- [ ] #task Add NetworkCallback reconnect and underlying-network tracking #repo/RIPDPI #area/vpn #status/backlog ⏫

## Summary

Use `ConnectivityManager.NetworkCallback` and `setUnderlyingNetworks()` to drive reconnect, failover, and policy refresh across Wi-Fi, cellular, captive, metered, suspended, and lost-network states.

## Motivation

Polling produces stale snapshots and misses transition windows where clients leak or show the wrong state. RIPDPI should treat network changes as lifecycle events.

## Scope

- In scope: network callbacks, capability/link-property handling, underlying-network publication, bootstrap re-evaluation, and transition tests.
- Out of scope: location-derived network fingerprinting and broad ISP profiling.

## Acceptance criteria

- [ ] `onAvailable`, `onCapabilitiesChanged`, `onLinkPropertiesChanged`, and `onLost` update VPN provider state without polling loops.
- [ ] DNS, route, metered, captive, suspended, and transport changes trigger scoped policy re-evaluation.
- [ ] VPN builder sets underlying networks when available and safe.
- [ ] Wi-Fi to LTE, LTE to Wi-Fi, sleep/wake, and captive-portal transitions do not mark the tunnel connected until health checks pass.
- [ ] Transition tests verify no direct fallback occurs during reconnect.

## Design notes

Network callbacks should feed the same supervisor state machine used by direct-mode and Xray provider mode.

## Risks / open questions

- Some callback fields can be privacy-sensitive; keep persisted network keys coarse.

## Links

- [[Epic - Fail-closed Android VPN policy engine]]
- [[Epic - Runtime lifecycle and supervisors]]
- https://developer.android.com/develop/connectivity/network-ops/reading-network-state
