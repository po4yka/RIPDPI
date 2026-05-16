---
title: Add authoritative DNS leak-test harness
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

- [x] #task Add authoritative DNS leak-test harness #repo/RIPDPI #area/vpn #status/done ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-authoritative-dns-leak-test-harness`
- **Verify:** `just test-module core:service`
- **Scope (only modify these + this file + the ledger):** `core/service/**`, `native/rust/crates/ripdpi-diagnostics-dns/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Build a DNS leak-test harness using unique random test domains and an authoritative test zone, so QA can verify which resolver path actually saw the query.

## Context

Public DNS leak-test pages are useful but not reproducible enough for RIPDPI regression work. A controlled authoritative zone lets the app test proxy, direct, IPv6, captive, and outage scenarios without logging user-identifying profile data.

## Acceptance criteria

- [x] Test harness generates unique per-run domains for proxy, direct, IPv6, and captive scenarios.
- [x] Authoritative logs record resolver source and coarse time bucket without storing live profile secrets.
- [x] App-side test reports expected resolver path versus observed resolver path.
- [x] Failure cases cover remote resolver outage, bootstrap resolver failure, proxy outbound failure, Android Private DNS enabled, Wi-Fi/LTE switch, captive portal, and core crash.
- [x] Harness integrates with the Android VPN leak-test matrix.

## Notes

Do not store live device identifiers or subscription tokens in authoritative DNS logs.

## Links

- [[ripdpi-android-split-strict-dns-architecture-2026-05-01]]
- [[Add Android VPN leak-test instrumentation matrix]]
- [[Add DNS interceptor and split DNS leak tests]]
