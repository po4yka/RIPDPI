---
title: Add integration tests per diagnostic result class
type: task
status: done
area: diagnostics
priority: medium
owner: unassigned
parent: epic-direct-mode-diagnostic-state-machine
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-05-29
---

- [x] #task Add integration tests per diagnostic result class #repo/RIPDPI #area/diagnostics #status/done 🔼

## Summary

Integration tests that drive the full diagnostic end-to-end in a controlled environment, one per `DiagnosticResult` variant and one per transport class. Uses the shared failure-injection harness.

## Plan reference

ripdpi-android-direct-mode-plan-2026-04-20 Phases 1–4.

## Progress

Verified 2026-05-29. A prerequisite landed alongside the tests: the
`DirectModeOrchestrator` now emits a non-null `OrchestratorResult.verdict`
(`deriveOrchestratorVerdict` maps the finished Phase 4 execution to exactly one
`DirectModeVerdictResult` with a structured reason and transport class). The new
`DiagnosticResultClassIntegrationTest` drives Phases 1–4 end to end per result
class on a deterministic harness (frozen clock + `ScriptedArmExecutor`, no real
network or sleeps).

## Acceptance criteria

- [x] `TRANSPARENT_WORKS` scenarios: one per class (DNS_BLOCK, SNI_TLS_SUSPECT, QUIC_BLOCK_SUSPECT resolved via A3–A8).
- [x] `OWNED_STACK_ONLY` scenarios: IP_BLOCK_SUSPECT resolved only by A9/A10; transparent arms confirmed failing (SNI scenario runs A3–A8 to failure before A10 wins).
- [x] `NO_DIRECT_SOLUTION` scenario: all arms fail within budget (plus an unconfirmed-owned-stack-pin gating scenario).
- [x] Attempt budget enforced in every scenario (no test exceeds the configured caps).
- [x] Tests are deterministic via the harness's fake clock and scripted network.

## Links

- Implement direct-mode diagnostic orchestrator Phases 1-4 (closed task)
- Add orchestration failure-injection harness
- [[Epic - Direct-mode diagnostic state machine]]
- ripdpi-android-direct-mode-plan-2026-04-20
