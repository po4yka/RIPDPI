---
title: Add fuzz targets for VLESS wire and MTProto init parsing
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

- [ ] #task Add fuzz targets for VLESS wire and MTProto init parsing #repo/RIPDPI #area/testing #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-fuzz-targets-for-vless-wire-and-mtproto-init`
- **Verify:** `cd native/rust/fuzz && cargo +nightly fuzz build vless_wire mtproto_init`
- **Scope (only modify these + this file + the ledger):** `native/rust/fuzz/**`, `docs/tasks/GOAL_LEDGER.md`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Add `cargo-fuzz` harnesses for `ripdpi-vless::wire::read_response`,
`wire::parse_target`, and `ripdpi-ws-tunnel::mtproto::decrypt_init_packet`.
Both parse adversary-controlled bytes on the network path.

## Context

`native/rust/fuzz/` already exists. The VLESS response header parser
and MTProto obfuscated2 init decryptor both consume server-influenced
or attacker-influenced bytes. A panic in either crashes the relay
worker.

## Acceptance criteria

- [ ] Three new fuzz targets under `native/rust/fuzz/fuzz_targets/`:
    `vless_response`, `vless_target_parse`, `mtproto_init`.
- [ ] Each target seeds a small corpus drawn from existing unit-test
    inputs.
- [ ] CI runs each target for a short bounded duration (e.g. 60s) on
    a nightly schedule, and uploads crashes to the standard artifacts
    location.
- [ ] The fuzz README links each target to the function under test.

## Definition of done

- Three targets build cleanly with `cargo +nightly fuzz build`.
- Initial corpus does not produce a crash within the bounded CI
  duration.

## Links

- [[add-fuzz-target-for-xhttp-finalmask-sudoku-decoder]]
- [[rust-soundness-policy]]
