---
title: Add fuzz target for xHTTP FinalMask Sudoku decoder
type: task
status: doing
area: testing
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-05-31
---

## Summary

`ripdpi-xhttp/src/finalmask/masks.rs` decodes attacker-influenced padding bytes via a Sudoku-based mask. Add a `cargo-fuzz` target for the decoder.

## Context

FinalMask is custom obfuscation, not a standardized format. The decoder operates on every inbound payload byte and exits early on malformed input. A panic, infinite loop, or out-of-bounds access in the Sudoku table walk crashes the relay worker.

## Acceptance criteria

- [x] (partial, 2026-05-15) A new `finalmask_spec` fuzz target under `native/rust/fuzz/fuzz_targets/finalmask_spec.rs`. Covers `FinalmaskSpec::from_config` through the new `__fuzz_parse_finalmask_spec` entry point in `ripdpi-xhttp`, which includes Sudoku-seed parsing, header/trailer hex decoders, and rand-range parsing. **Remaining work:** the byte-stream decoder path (`TcpInboundMask`) needs its own target that constructs a Sudoku table first and then feeds arbitrary cipher bytes; the spec-side parser is the higher- value entry point and shipped first.
- [ ] (original) A new `finalmask_decoder` fuzz target under `native/rust/fuzz/fuzz_targets/`.
- [ ] Initial corpus drawn from `finalmask::tests` happy-path bytes plus random low-entropy seeds.
- [ ] CI runs the target on the same nightly schedule as the other fuzz lanes.
- [ ] The fuzz README links the target to `finalmask/masks.rs` and `finalmask/spec.rs`.

## Definition of done

- Target builds and runs at least one bounded CI cycle without crash.

## Links

- [[add-fuzz-targets-for-vless-wire-and-mtproto-init]]
