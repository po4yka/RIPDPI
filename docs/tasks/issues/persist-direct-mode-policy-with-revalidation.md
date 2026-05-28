---
title: Persist direct-mode policy with revalidation
type: task
status: todo
area: diagnostics
priority: medium
owner: unassigned
parent: epic-direct-mode-diagnostic-state-machine
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-05-28
---

- [ ] #task Persist direct-mode policy with revalidation #repo/RIPDPI #area/diagnostics #status/todo 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `persist-direct-mode-policy-with-revalidation`
- **Verify:** `just test-module core:diagnostics`
- **Scope (only modify these + this file + the ledger):** `core/diagnostics/**`, `core/data/runtime-state/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Phase 5 of the diagnostic. Policy is pinned with a TTL and invalidated on environmental change; after 3 consecutive failures re-runs the full diagnostic.

## Plan reference

ripdpi-android-direct-mode-plan-2026-04-20 "Phase 5 — Persistence and revalidation".

## Progress

Verified 2026-05-28 against the current persistence path:

- confirmed direct-path policy is stored with a 7-day TTL;
- runtime ignores unconfirmed authority policy records instead of blindly replaying one-off diagnostics results;
- three consecutive revalidation failures now retire the cached policy entry from runtime use;
- `NO_DIRECT_SOLUTION` entries now age out when their cooldown expires instead of living forever in the injected direct-path capability set.

Still open: ASN-aware invalidation, HTTPS/SVCB/ECH-specific invalidation, and the explicit shared atomic-write/revalidation surface across every policy store.

## Acceptance criteria

- [x] TTL: 7 days default, configurable later if needed.
- [ ] Invalidate on ASN change.
- [x] Invalidate on access-type change (wifi ↔ cellular).
- [x] Invalidate after 3 consecutive failures.
- [ ] Invalidate when HTTPS/SVCB TTL expires or ECH capability changes.
- [ ] Atomic write (shares path with Make cache snapshot writes atomic).
- [ ] Phase 6 rotation triggers only within the same policy entry — does not count against the TTL.

## Links

- Implement direct-mode diagnostic orchestrator Phases 1-4 (closed task)
- Rotate successful family through variant neighborhood
- Make cache snapshot writes atomic
- [[Epic - Direct-mode diagnostic state machine]]
- ripdpi-android-direct-mode-plan-2026-04-20
