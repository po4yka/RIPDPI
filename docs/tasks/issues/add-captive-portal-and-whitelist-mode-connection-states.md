---
title: Add captive-portal and whitelist-mode connection states
type: task
status: backlog
area: vpn
priority: medium
owner: unassigned
parent: epic-fail-closed-android-vpn-policy-engine
blocks: []
blocked_by: []
created: 2026-05-01
updated: 2026-05-01
---

- [ ] #task Add captive-portal and whitelist-mode connection states #repo/RIPDPI #area/vpn #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-captive-portal-and-whitelist-mode-connection-states`
- **Verify:** `just test-module core:service`
- **Scope (only modify these + this file + the ledger):** `core/service/**`, `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Add explicit captive-portal assist and whitelist-suspected connection states so restricted networks do not appear as generic VPN failures.

## Motivation

During captive portals, mobile restrictions, or whitelist-style shutdowns, a tunneled outbound profile can look broken even when the correct answer is controlled direct access, relay suggestion, or a blocked/offline state.

## Scope

- In scope: state model, UI copy, short-lived captive portal assist, whitelist suspected detection, and diagnostic evidence summary.
- Out of scope: building a domestic whitelist relay or storing live relay infrastructure data in TaskNotes.

## Acceptance criteria

- [ ] Connection state can represent `Captive portal assist`, `Whitelist suspected`, `No connectivity`, and `Blocked / reconnecting`.
- [ ] Captive portal assist requires explicit user action and expires automatically.
- [ ] Whitelist suspected requires evidence that normal foreign endpoints fail while allowed domestic probes succeed.
- [ ] UI suggests configured whitelist relay profile only if one exists in the local profile.
- [ ] No automatic hidden bypass opens broad direct traffic while secure VPN mode is expected.

## Design notes

Keep this as a user-visible network condition, not a secret routing exception.

## Risks / open questions

- Portal and whitelist probes can be privacy-sensitive; use minimal and configurable probe sets.

## Links

- [[Epic - Fail-closed Android VPN policy engine]]
- [[Epic - Direct-mode diagnostic state machine]]
- [[Replace generic relay suggestion with transport-specific remediation ladder]]
