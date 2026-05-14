---
title: Add captive portal and whitelist-mode test cases
type: task
status: done
area: testing
priority: medium
owner: unassigned
parent: epic-vpn-fleet-testing-matrix-and-release-gates
blocks: []
blocked_by: []
created: 2026-05-01
updated: 2026-05-14
---

- [x] #task Add captive portal and whitelist-mode test cases #repo/RIPDPI #area/testing #status/done 🔼

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

- [x] Captive tests cover Wi-Fi with VPN off, VPN with lockdown off, Always-on +
    Block, explicit portal login assist, return to strict DNS after login, no
    general browsing during assist, and subscription fetch policy.
- [x] Portal assist allows only portal host/network handling and expires
    automatically.
- [x] Whitelist-mode tests detect all foreign endpoints failing while expected
    local/RU services remain reachable.
- [x] UI/diagnostic result distinguishes captive portal, whitelist suspected,
    no connectivity, and normal VPN degradation.
- [x] Test results do not record user browsing destinations.

## Notes

Use controlled networks or agreed testers only.

## Work log

- 2026-05-14: Added the captive-portal-assist + whitelist/shutdown
  classification surface in `core/service` (the captive task's verify module)
  plus its tests.
  - New production code: `ConnectivityDegradationClassifier.kt` (pure verdict
    classifier — `Healthy` / `CaptivePortalSuspected` / `WhitelistModeSuspected`
    / `NoConnectivity` / `NormalVpnDegradation`, driven by `NetworkFingerprint`
    + aggregate `ReachabilityEvidence`) and `CaptivePortalAssistWindow.kt`
    (time- and scope-bounded portal-login grant: portal-host/local-host only,
    network-pinned, auto-expiring).
  - New tests: `CaptivePortalAndWhitelistModeTest.kt` — covers Wi-Fi/VPN-off,
    OS captive flag (Always-on + Block), undeclared portal, all-foreign-fail
    whitelist signature, no-connectivity, normal degradation, the four verdicts
    being mutually distinguishable, portal-assist host/network scoping, TTL
    auto-expiry, return-to-strict-DNS, and no-leak-across-handover. Privacy:
    fixtures use synthetic hosts + aggregate counters only — no user
    destinations recorded.
  - TDD red->green confirmed: a deliberately wrong expectation
    (`WhitelistModeSuspected` -> `CaptivePortalSuspected`) made the suite fail,
    then reverted to green.
  - The diagnostics-runner Rust crate in scope is partially obfuscated and the
    contract Verify (`just test-module core:service`) is Kotlin-only, so all
    work landed in `core/service/**`; the Rust crate was not modified.
- **Verify:** `just test-module core:service` -> BUILD SUCCESSFUL, exit 0.
  Detekt (`:core:service`, `:core:data`) and ktlint on changed files: clean.

## Links

- [[Add captive portal DNS assist via Network object]]
- [[Add captive-portal and whitelist-mode connection states]]
- [[Create protocol degradation incident playbook]]
