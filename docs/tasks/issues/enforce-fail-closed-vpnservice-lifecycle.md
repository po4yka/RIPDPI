---
title: Enforce fail-closed VpnService lifecycle
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

- [x] #task Enforce fail-closed VpnService lifecycle #repo/RIPDPI #area/vpn #status/done ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `enforce-fail-closed-vpnservice-lifecycle`
- **Verify:** `just test-module core:service`
- **Scope (only modify these + this file + the ledger):** `core/service/**`, `core/engine/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Make VpnService startup, core failure, and `onRevoke()` paths fail closed by closing TUN, protected sockets, and provider runtimes before any direct traffic can continue silently.

## Motivation

Existing clients often look connected while the core, DNS resolver, or protected socket path has failed. RIPDPI should enter `Blocked / reconnecting` or `Revoked` states rather than leaving traffic behavior ambiguous.

## Scope

- In scope: `prepare()` handling, foreground startup sequencing, `protect()` failure behavior, TUN establishment failure, core crash handling, and `onRevoke()` cleanup.
- Out of scope: Android system lockdown implementation and provider-specific protocol retries.

## Acceptance criteria

- [x] VPN start aborts before TUN establishment if required transport sockets cannot be protected.
- [x] Core crash transitions connection state to blocked/reconnecting rather than connected.
- [x] `onRevoke()` closes TUN fd, tunnel sockets, provider runtimes, and local inbounds without main-thread assumptions.
- [x] Secure profiles never call `Builder.allowBypass()` unless the user explicitly enables an unsafe bypass setting.
- [x] Regression tests cover startup failure, core crash, and revoke cleanup.

## Design notes

Coordinate state names with Xray provider state so direct-mode and Xray-backed mode report the same lifecycle semantics.

## Risks / open questions

- Some OEMs reorder service shutdown and revoke callbacks; cleanup must be idempotent.

## Links

- [[Epic - Fail-closed Android VPN policy engine]]
- [[Epic - Runtime lifecycle and supervisors]]
- [[Run Xray as managed VPN relay runtime]]
- https://developer.android.com/reference/android/net/VpnService.Builder
