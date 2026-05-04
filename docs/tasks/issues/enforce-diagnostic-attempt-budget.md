---
title: Enforce diagnostic attempt budget
type: task
status: backlog
area: service
priority: high
owner: unassigned
parent: epic-privacy-preserving-strategy-learner
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-04-20
---

- [ ] #task Enforce diagnostic attempt budget #repo/RIPDPI #area/service #status/backlog ⏫

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
