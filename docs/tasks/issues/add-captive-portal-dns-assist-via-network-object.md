---
title: Add captive portal DNS assist via Network object
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

- [x] #task Add captive portal DNS assist via Network object #repo/RIPDPI #area/vpn #status/done ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-captive-portal-dns-assist-via-network-object`
- **Verify:** `just test-module core:service`
- **Scope (only modify these + this file + the ledger):** `core/service/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Implement captive-portal DNS assist as an explicit temporary state using Android's captive `Network` object, not a general fallback to local DNS.

## Motivation

Captive portals often require local DNS interception, but silently weakening DNS policy creates leaks. RIPDPI should make captive handling explicit, scoped, and short-lived.

## Scope

- In scope: portal state transition, portal-host allowlist, captive `Network` use, temporary direct DNS/HTTP for portal only, expiry, and UI warning.
- Out of scope: broad direct browsing during captive mode.

## Acceptance criteria

- [x] Captive mode is entered only after Android or diagnostics identify a captive portal condition.
- [x] Portal DNS/HTTP uses the captive `Network` object and only portal-scoped host/IP data.
- [x] General proxy/default DNS remains strict and does not fall back to captive DNS.
- [x] UI states that DNS is temporarily not private for portal login.
- [x] Captive success or timeout returns the app to strict DNS policy.

## Design notes

This refines the broader captive/whitelist state task by specifying the DNS behavior.

## Risks / open questions

- Portal detection and portal URL exposure can be inconsistent; keep fallbacks user-driven.

## Links

- [[ripdpi-android-split-strict-dns-architecture-2026-05-01]]
- [[Add captive-portal and whitelist-mode connection states]]
- https://developer.android.com/reference/android/net/ConnectivityManager
