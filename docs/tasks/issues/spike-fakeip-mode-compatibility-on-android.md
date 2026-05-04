---
title: Spike FakeIP mode compatibility on Android
type: task
status: backlog
area: vpn
priority: low
owner: unassigned
parent: epic-fail-closed-android-vpn-policy-engine
blocks: []
blocked_by: []
created: 2026-05-01
updated: 2026-05-01
---

- [ ] #task Spike FakeIP mode compatibility on Android #repo/RIPDPI #area/vpn #status/backlog 🔽

## Summary

Evaluate FakeIP mode as an advanced Android profile option, while keeping Real IP plus domain mapping cache as the production default.

## Context

FakeIP can improve domain-aware routing but can also break captive portals, local networks, hardcoded-IP flows, and OEM network behavior. RIPDPI should not ship it as the default without compatibility evidence.

## Acceptance criteria

- [ ] Document candidate FakeIP pool, route rules, and reverse mapping requirements.
- [ ] Test at least browser, Telegram-like, bank/gov-direct, captive portal, local LAN, and hardcoded-IP flows.
- [ ] Compare failure modes against Real IP plus resolver-path metadata.
- [ ] Recommend ship/no-ship for advanced profiles with explicit caveats.

## Notes

This is intentionally low priority. The current production recommendation is Real IP mode.

## Links

- [[ripdpi-android-split-strict-dns-architecture-2026-05-01]]
- [[Bind DNS answers to route decisions]]


## general
