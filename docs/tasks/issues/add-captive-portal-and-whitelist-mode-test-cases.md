---
title: Add captive portal and whitelist-mode test cases
type: task
status: backlog
area: testing
priority: medium
owner: unassigned
parent: epic-vpn-fleet-testing-matrix-and-release-gates
blocks: []
blocked_by: []
created: 2026-05-01
updated: 2026-05-01
---

- [ ] #task Add captive portal and whitelist-mode test cases #repo/RIPDPI #area/testing #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-captive-portal-and-whitelist-mode-test-cases`
- **Verify:** `just test-module core:service`
- **Scope (only modify these + this file + the ledger):** `core/service/**`, `native/rust/crates/ripdpi-diagnostics-runner/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Add tests for captive portal assist and whitelist/shutdown classification so
temporary local access does not become a general DNS/direct bypass.

## Context

Captive portals and whitelist-mode shutdowns can look like broken VPN. The
client and fleet tests must distinguish controlled portal access, blocked
foreign endpoints, and legitimate fallback modes.

## Acceptance criteria

- [ ] Captive tests cover Wi-Fi with VPN off, VPN with lockdown off, Always-on +
    Block, explicit portal login assist, return to strict DNS after login, no
    general browsing during assist, and subscription fetch policy.
- [ ] Portal assist allows only portal host/network handling and expires
    automatically.
- [ ] Whitelist-mode tests detect all foreign endpoints failing while expected
    local/RU services remain reachable.
- [ ] UI/diagnostic result distinguishes captive portal, whitelist suspected,
    no connectivity, and normal VPN degradation.
- [ ] Test results do not record user browsing destinations.

## Notes

Use controlled networks or agreed testers only.

## Links

- [[Add captive portal DNS assist via Network object]]
- [[Add captive-portal and whitelist-mode connection states]]
- [[Create protocol degradation incident playbook]]
