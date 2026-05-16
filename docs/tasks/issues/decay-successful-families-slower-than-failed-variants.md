---
title: Decay successful families slower than failed variants
type: task
status: done
area: service
priority: medium
owner: unassigned
parent: epic-privacy-preserving-strategy-learner
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-05-16
---

- [x] #task Decay successful families slower than failed variants #repo/RIPDPI #area/service #status/done 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `decay-successful-families-slower-than-failed-variants`
- **Verify:** `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-runtime-strategy`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-runtime-strategy/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Decay `ArmStats` so successful families retain their prior longer than
failed exact variants. Otherwise a single failure can wipe out
accumulated learning.

## Plan reference

[[ripdpi-android-direct-mode-plan-2026-04-20]] §5 (successful families
decay more slowly than failed exact variants).

## Acceptance criteria

- [ ] Separate decay half-lives for wins and losses; wins decay slower.
- [ ] Decay applies per-arm at periodic intervals, not on every update
    (cheap).
- [ ] Unit tests: with a 50/50 history, repeated additional losses
    gradually decrease score without zeroing it immediately.

## Work log

- 2026-05-16: Implemented `apply_decay(elapsed_ms: u64)` on `ComboStats` in
  `native/rust/crates/ripdpi-runtime-strategy/src/strategy_evolver/types/stats.rs`.
  Added `WIN_HALF_LIFE_MS = 7_200_000` (2 h) and `LOSS_HALF_LIFE_MS = 3_600_000` (1 h)
  constants (2:1 ratio). `ArmStats` in the spec maps to `ComboStats` in code — no rename
  needed, documented here. Four unit tests added to `strategy_evolver::tests`:
  `apply_decay_zero_elapsed_is_idempotent`, `apply_decay_50_50_history_at_loss_half_life_shows_asymmetry`,
  `apply_decay_repeated_losses_decrease_score_without_zeroing`, `apply_decay_no_op_on_zero_attempts`.
  `cargo nextest run -p ripdpi-runtime-strategy` exit 0; 144/144 tests pass.

## Links

- [[Epic - Privacy-preserving strategy learner]]
- [[Define NetProfile HostProfile and ArmStats]]
- [[ripdpi-android-direct-mode-plan-2026-04-20]]
