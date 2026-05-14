---
title: Add explicit IPv6 policy modes and leak tests
type: task
status: backlog
area: vpn
priority: critical
owner: unassigned
parent: epic-fail-closed-android-vpn-policy-engine
blocks: []
blocked_by: []
created: 2026-05-01
updated: 2026-05-01
---

- [ ] #task Add explicit IPv6 policy modes and leak tests #repo/RIPDPI #area/vpn #status/backlog 🔺

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-explicit-ipv6-policy-modes-and-leak-tests`
- **Verify:** `just test-module core:service`
- **Scope (only modify these + this file + the ledger):** `core/service/**`, `core/data/settings/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Add explicit IPv4-only and verified dual-stack VPN modes, with tests proving IPv6 cannot bypass the tunnel accidentally.

## Motivation

Existing Android clients often proxy IPv4 while IPv6 continues over the underlying network. RIPDPI should default to IPv4-only unless full dual-stack routing, DNS, and leak tests pass.

## Scope

- In scope: profile-level IPv6 policy, VPN builder address/route/DNS handling, re-establish behavior when policy changes, and IPv6 leak tests.
- Out of scope: server-side IPv6 provisioning and user-facing education beyond state labels.

## Acceptance criteria

- [ ] Secure default is `ipv4_only`.
- [ ] IPv4-only profiles do not add IPv6 address, route, DNS, or `allowFamily(AF_INET6)` behavior.
- [ ] Dual-stack mode requires explicit profile support for IPv6 TUN address, `::/0`, AAAA DNS through tunnel, and transport support.
- [ ] Changing IPv6 mode forces VPN session re-establish.
- [ ] Leak tests fail if an IPv6-capable network exposes direct public IPv6 while VPN is connected.

## Design notes

Treat direct IPv6 while IPv4 is proxied as a leak state, not a feature.

## Risks / open questions

- Android builder family behavior can be subtle when DNS servers or addresses implicitly allow a family; tests should exercise real Builder configurations where possible.

## Links

- [[Epic - Fail-closed Android VPN policy engine]]
- https://developer.android.com/reference/android/net/VpnService.Builder
