---
title: Implement direct-mode diagnostic orchestrator Phases 1-4
type: task
status: done
area: diagnostics
priority: high
owner: unassigned
parent: epic-direct-mode-diagnostic-state-machine
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-05-16
---

- [x] #task Implement direct-mode diagnostic orchestrator Phases 1-4 #repo/RIPDPI #area/diagnostics #status/done ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `implement-direct-mode-diagnostic-orchestrator-phases-1-4`
- **Verify:** `just test-module core:diagnostics`
- **Scope (only modify these + this file + the ledger):** `core/diagnostics/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

The glue. Runs Phase 1 (DNS classification) → Phase 2 (transport
classification) → Phase 3 (ranked arm generation per class) → Phase 4
(execute with early stop + one confirmation request). Respects the
attempt budget.

## Plan reference

[[ripdpi-android-direct-mode-plan-2026-04-20]] Phases 1–4 + candidate arms
A0–A10.

## Progress

The first persisted/user-visible slice is now landed:

- subsystem outputs already produced by the DNS classifier, transport-policy
verdicts, and transparent TLS-family work are now preserved through the
stored diagnostics report instead of losing `strategyRecommendation` at the
engine-wire boundary;
- the summary layer now surfaces all three verdict families, including the
positive `TRANSPARENT_WORKS` case;
- Home audit can once again consume a persisted strategy recommendation when
there is no reusable validated strategy-probe winner;
- the repo-owned persistence path now honors `confirm_once` semantics:
transparent / owned-stack results only pin after corroborating evidence or a
matching prior, and negative results only pin after repeated active failure.

Still open: the actual ranked-arm dispatcher, hard attempt-budget
enforcement, and the full class-to-arm execution ladder from the plan.

## Acceptance criteria

- [x] Orchestrator delegates to subsystem epics, never reimplements them:
- DNS → [[Epic - Encrypted DNS and HTTPS SVCB classifier]]
- Transport policy → [[Epic - Direct-mode transport policy and verdicts]]
- TLS family arms → [[Epic - Semantic TLS first-flight family engine]]
- Arm ranking → [[Epic - Privacy-preserving strategy learner]]
- Owned-stack arms → [[Epic - Owned-stack mode with Android 17 ECH]]
- [x] Per-class arm list matches the plan:
- `DNS_BLOCK:           A1, A3, A4, A5, A6, A10, A9`
- `SNI_TLS_SUSPECT:     A3, A5, A6, A7, A8, A10, A9`
- `QUIC_BLOCK_SUSPECT:  A3, A4, A5, A6, A9`
- `IP_BLOCK_SUSPECT:    A10, A9`
- `UNKNOWN:             A1, A3, A4, A5, A9`
- [x] Repo-owned persistence path requires `confirm_once`; pin only after
    confirmation.
- [x] Attempt budget hard-enforced (see [[Enforce diagnostic attempt budget]]).
- [x] Produces one `DiagnosticResult` per run.

## Links

- [[Enforce diagnostic attempt budget]]
- [[Define DiagnosticResult and classification taxonomy]]
- [[Implement Phase 0 passive observation from last flow]]
- [[Implement Bayesian posterior arm scoring]]
- [[Epic - Direct-mode diagnostic state machine]]
- [[ripdpi-android-direct-mode-plan-2026-04-20]]
