---
title: Implement Phase 0 passive observation from last flow
type: task
status: todo
area: diagnostics
priority: medium
owner: unassigned
parent: epic-direct-mode-diagnostic-state-machine
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-04-23
---

- [ ] #task Implement Phase 0 passive observation from last flow #repo/RIPDPI #area/diagnostics #status/todo 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `implement-phase-0-passive-observation-from-last-flow`
- **Verify:** `just test-module core:diagnostics`
- **Scope (only modify these + this file + the ledger):** `core/diagnostics/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Before active probing, extract what we can from the last real failed flow: DNS outcome, TCP SYN/SYN-ACK, did failure happen before or after ClientHello, did UDP/443 fail while TCP to same host worked, did the response look like a error-page.

## Plan reference

[[ripdpi-android-direct-mode-plan-2026-04-20]] "Phase 0 — Passive observation first".

## Progress

The full passive-observer struct is still not landed, but the repo-owned state machine no longer starts entirely from zero:

- diagnostics finalization now consults the previously confirmed authority record before pinning a new direct-path verdict;
- that stored authority prior is now used as a lightweight passive signal for confirmation/revalidation, especially when the current run only produced one active direct-path failure.

Still open: emitting a typed `PassiveObservation` payload directly from live runtime failures and feeding that payload into Phase 1 / Phase 2 before active probing starts.

## Acceptance criteria

- [ ] Passive observer runs when a flow fails; emits a typed `PassiveObservation` struct.
- [ ] Error-page detection uses a small heuristic set — TLS certificate mismatch, known middlebox block HTML shapes, response sizes, common block patterns.
- [ ] Phase 0 observation is consumed by Phase 1/Phase 2 classification instead of them probing from zero.
- [ ] Zero added cost on success paths.

## Links

- [[Epic - Direct-mode diagnostic state machine]]
- [[ripdpi-android-direct-mode-plan-2026-04-20]]
