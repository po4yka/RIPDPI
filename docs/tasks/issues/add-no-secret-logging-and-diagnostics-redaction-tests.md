---
title: Add no-secret logging and diagnostics redaction tests
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

- [x] #task Add no-secret logging and diagnostics redaction tests #repo/RIPDPI #area/vpn #status/done ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-no-secret-logging-and-diagnostics-redaction-tests`
- **Verify:** `just test-module core:service`
- **Scope (only modify these + this file + the ledger):** `core/service/**`, `core/diagnostics/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Add release-log and diagnostics tests that fail if VPN credentials, subscription URLs, tokens, endpoints, or raw configs appear in logcat, crash reports, or exported bundles.

## Motivation

Several existing clients leak operational details through logcat, crash exports, copied URIs, or diagnostics. RIPDPI should make no-secret logging a tested invariant.

## Scope

- In scope: redaction helpers, R8 release-log policy, diagnostics-mode consent and TTL, export bundle redaction, and test fixtures with realistic secret-looking values.
- Out of scope: third-party crash-report service integration.

## Acceptance criteria

- [x] Release builds strip or downgrade verbose logs that could contain network/config state.
- [x] Test fixtures containing UUIDs, shortIds, subscription tokens, passwords, and endpoints are fully redacted from diagnostics output.
- [x] Diagnostics mode is opt-in, time-limited, and exports encrypted or explicitly user-controlled bundles.
- [x] Crash/report path stores config hash, profile ID, and state reason rather than raw profile fields.
- [x] Clipboard/share actions clear or warn when content contains live profile material.

## Design notes

Prefer deny-by-default secret wrappers plus allowlisted diagnostic fields. Do not rely only on regex cleanup after logging.

## Risks / open questions

- Some lower-level native libraries may log before Kotlin redaction; capture and sanitize their output path separately.

## Links

- [[Epic - Fail-closed Android VPN policy engine]]
- [[Epic - Privacy and diagnostics]]
- https://developer.android.com/privacy-and-security/risks/log-info-disclosure
