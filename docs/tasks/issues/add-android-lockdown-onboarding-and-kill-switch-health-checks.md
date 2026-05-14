---
title: Add Android lockdown onboarding and kill-switch health checks
type: task
status: backlog
area: vpn
priority: critical
owner: unassigned
parent: epic-fail-closed-android-vpn-policy-engine
blocks: []
blocked_by: []
created: 2026-05-01
updated: 2026-05-01
---

- [ ] #task Add Android lockdown onboarding and kill-switch health checks #repo/RIPDPI #area/vpn #status/backlog 🔺

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-android-lockdown-onboarding-and-kill-switch-health-checks`
- **Verify:** `just test-module core:service`
- **Scope (only modify these + this file + the ledger):** `core/service/**`, `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Add onboarding and runtime health UI that guides users to Android Always-on VPN plus Block connections without VPN and clearly reports whether lockdown is active, missing, or unknown.

## Motivation

App-level reconnect is not a hard kill switch. Android lockdown is user or device-admin controlled, so RIPDPI must make the system requirement visible instead of implying the client can enforce it alone.

## Scope

- In scope: onboarding checklist, Settings deep links, runtime kill-switch status, blocked/reconnecting state copy, and health checks after network transitions.
- Out of scope: silently enabling lockdown for the user or claiming hard protection when the OS setting is not enabled.

## Acceptance criteria

- [ ] Onboarding distinguishes VPN permission, Always-on VPN, Block connections without VPN, battery optimization, and foreground-service health.
- [ ] Connection screen shows `System lockdown enabled`, `not enabled`, or `unknown`.
- [ ] Secure profiles can warn or block start when lockdown is required but not observed.
- [ ] UI disables or explains disconnect actions when Android controls an always-on VPN lifecycle.
- [ ] Tests cover the health-state reducer without requiring private Android APIs.

## Design notes

Use explicit language: RIPDPI can fail closed inside its service, but Android system lockdown is the only consumer-grade hard kill switch.

## Risks / open questions

- Android exposes limited public state for lockdown; some verification may need behavioral tests rather than a direct setting read.

## Links

- [[Epic - Fail-closed Android VPN policy engine]]
- https://developer.android.com/develop/connectivity/vpn
