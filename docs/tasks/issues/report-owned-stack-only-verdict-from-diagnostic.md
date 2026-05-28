---
title: Report OWNED_STACK_ONLY verdict from diagnostic
type: task
status: todo
area: diagnostics
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-05-28
---

- [ ] #task Report OWNED_STACK_ONLY verdict from diagnostic #repo/RIPDPI #area/diagnostics #status/todo 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `report-owned-stack-only-verdict-from-diagnostic`
- **Verify:** `just test-module core:diagnostics`
- **Scope (only modify these + this file + the ledger):** `core/diagnostics/**`, `core/data/runtime-state/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

When transparent arms (A3–A8) all fail but an owned-stack arm (A9/A10) works, the diagnostic returns `OWNED_STACK_ONLY`. Surface that as a real verdict, not a failure — "open this host inside the RIPDPI browser" is a legitimate outcome.

## Plan reference

[[ripdpi-android-direct-mode-plan-2026-04-20]] §4 and `classify_success(arm)` in Phase 4.

## Current status

Verified 2026-05-28 against the current diagnostics and policy code:

- `DirectModeOutcome.OWNED_STACK_ONLY` exists in the shared transport-policy model and round-trips through the versioned envelope.
- `DirectModePolicySupport` derives `outcome = OWNED_STACK_ONLY` and `DirectModeReasonCode.OWNED_STACK_REQUIRED` from owned-stack-only diagnostic signals.
- The diagnostics UI now treats `OWNED_STACK_ONLY` as a real outcome and offers a direct action to open the authority in the RIPDPI browser.
- Session-row projections carry the launch URL and owned-stack-only flag so remediation can be derived from persisted diagnostic output.
- Remaining work still belongs to the transparent-mode handoff: the pure orchestrator currently reports executed owned-stack arms and pin confirmation, but does not emit a final `OWNED_STACK_ONLY` `OrchestratorResult.verdict`; third-party transparent traffic still needs a structured not-supported result.

## Acceptance criteria

- [ ] Diagnostic orchestrator emits `OWNED_STACK_ONLY` when the winning arm is A9 or A10 and no transparent arm succeeded.
- [x] UI/diagnostics surface: "Transparent mode: no / Owned-stack mode: yes" with a direct action to open the URL in the in-app browser.
- [x] Persisted policy sets `outcome = OWNED_STACK_ONLY` on the `TransportPolicy` when owned-stack-only diagnostic evidence is present.
- [ ] Third-party apps hitting this host in transparent mode get a structured "not supported in transparent mode" result, not a silent failure.

## Links

- Implement direct-mode diagnostic orchestrator Phases 1-4 (closed task)
- [[Epic - Owned-stack mode with Android 17 ECH]]
- [[ripdpi-android-direct-mode-plan-2026-04-20]]
