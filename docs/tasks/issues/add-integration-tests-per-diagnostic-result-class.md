---
title: Add integration tests per diagnostic result class
type: task
status: backlog
area: diagnostics
priority: medium
owner: unassigned
parent: epic-direct-mode-diagnostic-state-machine
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-05-16
---

- [ ] #task Add integration tests per diagnostic result class #repo/RIPDPI #area/diagnostics #status/backlog 🔼

## Work log

- 2026-05-16: Dropped orphaned blocker reference 'UNRESOLVED-POY-129' (file does not exist); reclassified to backlog.

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-integration-tests-per-diagnostic-result-class`
- **Verify:** `just test-module core:diagnostics`
- **Scope (only modify these + this file + the ledger):** `core/diagnostics/**`, `native/rust/crates/ripdpi-diagnostics-runner/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Integration tests that drive the full diagnostic end-to-end in a controlled environment, one per `DiagnosticResult` variant and one per transport class. Uses the shared failure-injection harness.

## Plan reference

[[ripdpi-android-direct-mode-plan-2026-04-20]] Phases 1–4.

## Acceptance criteria

- [ ] `TRANSPARENT_WORKS` scenarios: one per class (DNS_BLOCK, SNI_TLS_SUSPECT, QUIC_BLOCK_SUSPECT resolved via A3–A8).
- [ ] `OWNED_STACK_ONLY` scenarios: IP_BLOCK_SUSPECT resolved only by A9/A10; transparent arms confirmed failing.
- [ ] `NO_DIRECT_SOLUTION` scenario: all arms fail within budget.
- [ ] Attempt budget enforced in every scenario (no test exceeds the configured caps).
- [ ] Tests are deterministic via the harness's fake clock and scripted network.

## Links

- [[Implement direct-mode diagnostic orchestrator Phases 1-4]]
- [[Add orchestration failure-injection harness]]
- [[Epic - Direct-mode diagnostic state machine]]
- [[ripdpi-android-direct-mode-plan-2026-04-20]]
