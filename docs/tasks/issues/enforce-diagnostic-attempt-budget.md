---
title: Enforce diagnostic attempt budget
type: task
status: done
area: service
priority: high
owner: unassigned
parent: epic-privacy-preserving-strategy-learner
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-05-15
---

- [x] #task Enforce diagnostic attempt budget #repo/RIPDPI #area/service #status/done ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `enforce-diagnostic-attempt-budget`
- **Verify:** `just test-module core:service`
- **Scope (only modify these + this file + the ledger):** `core/service/**`, `native/rust/crates/ripdpi-diagnostics-runner/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Strict budget caps per diagnostic run:

```text
max_active_arms = 5
max_elapsed_ms  = 6000
max_probe_bytes = 65536
stop_on_first_stable_success = true
```

## Plan reference

[[ripdpi-android-direct-mode-plan-2026-04-20]] §5 attempt budget.

## Acceptance criteria

- [ ] Orchestrator respects all four caps; breaching any one stops the
    run.
- [ ] "Stable success" = first-pass success + one confirmation request
    (Phase 4 `confirm_once`).
- [ ] Budget is observable via diagnostics — users/debugging see which cap
    fired.
- [ ] Unit tests cover each cap firing first, and the interaction with
    `confirm_once`.

## Links

- [[Implement direct-mode diagnostic orchestrator Phases 1-4]]
- [[Epic - Privacy-preserving strategy learner]]
- [[ripdpi-android-direct-mode-plan-2026-04-20]]
