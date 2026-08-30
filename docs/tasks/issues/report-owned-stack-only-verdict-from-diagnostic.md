---
id: DGN-1786264762917717
title: Report OWNED_STACK_ONLY verdict from diagnostic
kind: feature
status: review
area: diagnostics
priority: medium
owner: unassigned
parent: null
blocked_by: []
spec_mode: required
openspec_change: dgn-1786264762917717-report-owned-stack-only-verdict-from-diagnostic
created: 2026-04-20
updated: 2026-08-30
status_detail: Structured transparent-mode rejection implemented and locally verified; awaiting exact-SHA remote CI before archival.
---

## Summary

When transparent arms (A3–A8) all fail but an owned-stack arm (A9/A10) works, the diagnostic returns `OWNED_STACK_ONLY`. Surface that as a real verdict, not a failure — "open this host inside the RIPDPI browser" is a legitimate outcome.

## Plan reference

ripdpi-android-direct-mode-plan-2026-04-20 §4 and `classify_success(arm)` in Phase 4.

## Current status

Verified 2026-05-28 against the current diagnostics and policy code:

- `DirectModeOutcome.OWNED_STACK_ONLY` exists in the shared transport-policy model and round-trips through the versioned envelope.
- `DirectModePolicySupport` derives `outcome = OWNED_STACK_ONLY` and `DirectModeReasonCode.OWNED_STACK_REQUIRED` from owned-stack-only diagnostic signals.
- The diagnostics UI now treats `OWNED_STACK_ONLY` as a real outcome and offers a direct action to open the authority in the RIPDPI browser.
- Session-row projections carry the launch URL and owned-stack-only flag so remediation can be derived from persisted diagnostic output.
- The orchestrator emits the final verdict: `deriveOrchestratorVerdict` returns `OWNED_STACK_ONLY` (reason `OWNED_STACK_REQUIRED`) when owned-stack succeeds and transparent fails, and `DirectModeOrchestrator.run()` sets `OrchestratorResult.verdict` from it (`OrchestratorTypes.kt:95-115`, `DirectModeOrchestrator.kt:58-69`; verified 2026-06-11). This matches criteria 1–3 below being done.
- Remaining work is solely the transparent-mode handoff (criterion 4): third-party transparent traffic still needs a structured "not supported in transparent mode" result rather than a silent failure.

## Acceptance criteria

- [x] Diagnostic orchestrator emits `OWNED_STACK_ONLY` when the winning arm is A9 or A10 and no transparent arm succeeded.
- [x] UI/diagnostics surface: "Transparent mode: no / Owned-stack mode: yes" with a direct action to open the URL in the in-app browser.
- [x] Persisted policy sets `outcome = OWNED_STACK_ONLY` on the `TransportPolicy` when owned-stack-only diagnostic evidence is present.
- [ ] Third-party apps hitting this host in transparent mode get a structured "not supported in transparent mode" result, not a silent failure.

## Work log

- 2026-06-05: Criteria 1–3 verified done: `deriveOrchestratorVerdict` in `OrchestratorTypes.kt` emits `OWNED_STACK_ONLY` verdict; `DiagnosticsUiCoreSupport.kt` surfaces `ownedStackOnly` flag + browser launch URL; `DirectModePolicySupport.kt` persists `outcome=OWNED_STACK_ONLY`. Criterion 4 (structured "not supported in transparent mode" result for third-party apps) has no implementation found — remains open.

## Links

- Implement direct-mode diagnostic orchestrator Phases 1-4 (closed task)
- Epic - Owned-stack mode with Android 17 ECH
- ripdpi-android-direct-mode-plan-2026-04-20
