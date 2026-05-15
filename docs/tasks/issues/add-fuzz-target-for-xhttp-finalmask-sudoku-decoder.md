---
title: Add fuzz target for xHTTP FinalMask Sudoku decoder
type: task
status: backlog
area: testing
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-05-15
---

- [ ] #task Add fuzz target for xHTTP FinalMask Sudoku decoder #repo/RIPDPI #area/testing #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-fuzz-target-for-xhttp-finalmask-sudoku-decoder`
- **Verify:** `cd native/rust/fuzz && cargo +nightly fuzz build finalmask_decoder`
- **Scope (only modify these + this file + the ledger):** `native/rust/fuzz/**`, `docs/tasks/GOAL_LEDGER.md`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

`ripdpi-xhttp/src/finalmask/masks.rs` decodes attacker-influenced
padding bytes via a Sudoku-based mask. Add a `cargo-fuzz` target for
the decoder.

## Context

FinalMask is custom obfuscation, not a standardized format. The
decoder operates on every inbound payload byte and exits early on
malformed input. A panic, infinite loop, or out-of-bounds access in
the Sudoku table walk crashes the relay worker.

## Acceptance criteria

- [ ] A new `finalmask_decoder` fuzz target under
    `native/rust/fuzz/fuzz_targets/`.
- [ ] Initial corpus drawn from `finalmask::tests` happy-path bytes
    plus random low-entropy seeds.
- [ ] CI runs the target on the same nightly schedule as the other
    fuzz lanes.
- [ ] The fuzz README links the target to `finalmask/masks.rs` and
    `finalmask/spec.rs`.

## Definition of done

- Target builds and runs at least one bounded CI cycle without crash.

## Links

- [[add-fuzz-targets-for-vless-wire-and-mtproto-init]]
