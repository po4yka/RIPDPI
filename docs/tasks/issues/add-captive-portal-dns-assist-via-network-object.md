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

- [ ] #task Add captive portal DNS assist via Network object #repo/RIPDPI #area/vpn #status/backlog ⏫

## Summary

Implement captive-portal DNS assist as an explicit temporary state using Android's captive `Network` object, not a general fallback to local DNS.

## Motivation

Captive portals often require local DNS interception, but silently weakening DNS policy creates leaks. RIPDPI should make captive handling explicit, scoped, and short-lived.

## Scope

- In scope: portal state transition, portal-host allowlist, captive `Network` use, temporary direct DNS/HTTP for portal only, expiry, and UI warning.
- Out of scope: broad direct browsing during captive mode.

## Acceptance criteria

- [ ] Captive mode is entered only after Android or diagnostics identify a captive portal condition.
- [ ] Portal DNS/HTTP uses the captive `Network` object and only portal-scoped host/IP data.
- [ ] General proxy/default DNS remains strict and does not fall back to captive DNS.
- [ ] UI states that DNS is temporarily not private for portal login.
- [ ] Captive success or timeout returns the app to strict DNS policy.

## Design notes

This refines the broader captive/whitelist state task by specifying the DNS behavior.

## Risks / open questions

- Portal detection and portal URL exposure can be inconsistent; keep fallbacks user-driven.

## Links

- [[ripdpi-android-split-strict-dns-architecture-2026-05-01]]
- [[Add captive-portal and whitelist-mode connection states]]
- https://developer.android.com/reference/android/net/ConnectivityManager
