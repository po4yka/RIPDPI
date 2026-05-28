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

## Summary

Add `cargo-fuzz` harnesses for `ripdpi-vless::wire::read_response`, `wire::parse_target`, and `ripdpi-ws-tunnel::mtproto::decrypt_init_packet`. Both parse adversary-controlled bytes on the network path.

## Context

`native/rust/fuzz/` already exists. The VLESS response header parser and MTProto obfuscated2 init decryptor both consume server-influenced or attacker-influenced bytes. A panic in either crashes the relay worker.

## Acceptance criteria

- [x] (partial, 2026-05-15) `mtproto_init` fuzz target shipped under `native/rust/fuzz/fuzz_targets/mtproto_init.rs`, covers `classify_mtproto_seed`, `decrypt_init_packet`, `extract_dc_from_init`. Compiles cleanly (cargo check passes). `vless_response` still owed (async `read_response` needs a runtime-wrapped Cursor harness); `vless_target_parse` deferred because `parse_target` is a private helper — the existing `vless_request_header` target already drives it transitively via `encode_request`.
- [ ] (original) Three new fuzz targets under `native/rust/fuzz/fuzz_targets/`: `vless_response`, `vless_target_parse`, `mtproto_init`.
- [ ] Each target seeds a small corpus drawn from existing unit-test inputs.
- [ ] CI runs each target for a short bounded duration (e.g. 60s) on a nightly schedule, and uploads crashes to the standard artifacts location.
- [ ] The fuzz README links each target to the function under test.

## Definition of done

- Three targets build cleanly with `cargo +nightly fuzz build`.
- Initial corpus does not produce a crash within the bounded CI duration.

## Links

- [[add-fuzz-target-for-xhttp-finalmask-sudoku-decoder]]
- [[rust-soundness-policy]]
