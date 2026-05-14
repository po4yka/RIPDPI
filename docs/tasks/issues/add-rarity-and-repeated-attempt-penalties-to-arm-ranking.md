---
title: Add rarity and repeated-attempt penalties to arm ranking
type: task
status: backlog
area: service
priority: medium
owner: unassigned
parent: epic-privacy-preserving-strategy-learner
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-04-20
---

- [ ] #task Add rarity and repeated-attempt penalties to arm ranking #repo/RIPDPI #area/service #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-rarity-and-repeated-attempt-penalties-to-arm-ranking`
- **Verify:** `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-runtime-strategy`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-runtime-strategy/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

`rarity_penalty`: high for rare, distinctive wire images — protects
against accumulation-based detection. `repeated_attempt_penalty`: grows
when we keep hammering the same host with failures — protects against
pattern pinning and battery burn.

## Plan reference

[[ripdpi-android-direct-mode-plan-2026-04-20]] §5.

## Acceptance criteria

- [ ] Rarity is computed from local-observed arm frequency, not a preset
    label.
- [ ] Penalty resets appropriately when the network profile changes (new
    observation window).
- [ ] Repeated-attempt penalty is per `(host, NetProfile)`, not global.
- [ ] Unit tests: rare arm wins tie-break only when posterior is high
    enough to justify it; repeated-attempt penalty caps after N
    consecutive failures.

## Links

- [[Implement Bayesian posterior arm scoring]]
- [[Epic - Privacy-preserving strategy learner]]
- [[ripdpi-android-direct-mode-plan-2026-04-20]]
