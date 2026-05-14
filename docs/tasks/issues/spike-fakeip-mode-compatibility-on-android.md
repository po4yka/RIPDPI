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

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `spike-fakeip-mode-compatibility-on-android`
- **Verify:** `just test-module core:service`
- **Scope (only modify these + this file + the ledger):** `core/service/**`, `native/rust/crates/ripdpi-runtime-dns-cache/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

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
