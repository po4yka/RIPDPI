---
title: Add NetworkCallback reconnect and underlying-network tracking
type: task
status: done
area: vpn
priority: high
owner: unassigned
parent: epic-fail-closed-android-vpn-policy-engine
blocks: []
blocked_by: []
created: 2026-05-01
updated: 2026-05-16
---

- [x] #task Add NetworkCallback reconnect and underlying-network tracking #repo/RIPDPI #area/vpn #status/done ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-networkcallback-reconnect-and-underlying-network-tracking`
- **Verify:** `just test-module core:service`
- **Scope (only modify these + this file + the ledger):** `core/service/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Use `ConnectivityManager.NetworkCallback` and `setUnderlyingNetworks()` to drive reconnect, failover, and policy refresh across Wi-Fi, cellular, captive, metered, suspended, and lost-network states.

## Motivation

Polling produces stale snapshots and misses transition windows where clients leak or show the wrong state. RIPDPI should treat network changes as lifecycle events.

## Scope

- In scope: network callbacks, capability/link-property handling, underlying-network publication, bootstrap re-evaluation, and transition tests.
- Out of scope: location-derived network fingerprinting and broad ISP profiling.

## Acceptance criteria

- [x] `onAvailable`, `onCapabilitiesChanged`, `onLinkPropertiesChanged`, and `onLost` update VPN provider state without polling loops.
- [x] DNS, route, metered, captive, suspended, and transport changes trigger scoped policy re-evaluation.
- [x] VPN builder sets underlying networks when available and safe.
- [x] Wi-Fi to LTE, LTE to Wi-Fi, sleep/wake, and captive-portal transitions do not mark the tunnel connected until health checks pass.
- [x] Transition tests verify no direct fallback occurs during reconnect.

## Design notes

Network callbacks should feed the same supervisor state machine used by direct-mode and Xray provider mode.

## Risks / open questions

- Some callback fields can be privacy-sensitive; keep persisted network keys coarse.

## Links

- [[Epic - Fail-closed Android VPN policy engine]]
- [[Epic - Runtime lifecycle and supervisors]]
- https://developer.android.com/develop/connectivity/network-ops/reading-network-state
