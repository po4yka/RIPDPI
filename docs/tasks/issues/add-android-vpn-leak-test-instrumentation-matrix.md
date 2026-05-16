---
title: Add Android VPN leak-test instrumentation matrix
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

- [x] #task Add Android VPN leak-test instrumentation matrix #repo/RIPDPI #area/vpn #status/done ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-android-vpn-leak-test-instrumentation-matrix`
- **Verify:** `just test-module core:service`
- **Scope (only modify these + this file + the ledger):** `core/service/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Create an Android VPN leak-test matrix that exercises DNS, IPv6, kill-switch, network transition, revoke, per-app, and credential-revocation behavior across supported API levels.

## Context

The policy-first client is only credible if the failure modes are reproducible. This task collects the cross-cutting instrumentation and acceptance matrix rather than leaving each feature to test only its happy path.

## Acceptance criteria

- [x] DNS leak test proves proxied domains do not use ISP/default-network DNS.
- [x] IPv6 leak test proves IPv4-only mode does not expose direct IPv6 on IPv6-capable networks.
- [x] Core-crash and service-stop tests prove traffic is blocked or the VPN state is revoked, not silently direct.
- [x] Wi-Fi to LTE, LTE to Wi-Fi, sleep/wake, and captive portal transitions are covered.
- [x] `onRevoke()` test verifies sockets, TUN fd, and provider runtimes close.
- [x] Per-app allow/disallow tests cover reconnect requirement and lockdown interactions.
- [x] Revoked credential fixtures prove stale UUID/shortId/password/profile tokens no longer work in local validation paths.

## Notes

Start with emulator and fake-network harness coverage, then add real-device smoke cases for API 26, 29, 30, 33, 34, 35, and current preview when available.

## Links

- [[Epic - Fail-closed Android VPN policy engine]]
- [[Epic - Orchestration test posture]]
- [[Add Xray provider regression matrix]]
