---
title: Add Hysteria 2 Salamander obfuscation conformance fixtures
type: task
status: backlog
area: testing
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-05-16
---

- [ ] #task Add Hysteria 2 Salamander obfuscation conformance fixtures #repo/RIPDPI #area/testing #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-hysteria2-salamander-obfuscation-conformance-fixtures`
- **Verify:** `cargo test -p ripdpi-hysteria2`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-hysteria2/**`, `contract-fixtures/**`, `docs/tasks/GOAL_LEDGER.md`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Salamander (`ripdpi-hysteria2/src/salamander.rs`) is the proprietary QUIC obfuscation layer that changes with upstream Hysteria 2 releases. Capture wire-level conformance fixtures so silent upstream divergence is caught by golden tests, not by user reports.

## Context

Salamander is XOR-style obfuscation keyed by a server-supplied key. Reference vectors derived from apernet/hysteria's tests pin behavior to a specific upstream tag.

## Acceptance criteria

- [ ] At least eight obfuscation goldens under `contract-fixtures/hysteria2/<upstream-tag>/salamander/` covering the boundary cases from apernet/hysteria's own tests. **DEFERRED:** upstream byte vectors needed; cannot fabricate conformant fixtures without them. Tracked behind [[tag-protocol-contract-fixtures-by-upstream-version]].
- [x] (partial, 2026-05-15) `salamander::tests` parses each golden and asserts byte equality on encode and decode. **Regression-boundary fixture shipped** as `salamander_keystream_pinned_for_known_key_and_salt`: pins the blake2b256 keystream derivation for a known (key, salt) and asserts decode of a synthetic ciphertext round-trips to the plaintext. Catches accidental algorithm swaps; not an upstream- conformance check.
- [ ] Goldens carry the upstream tag in the directory name. **DEFERRED:** see first criterion.

## Definition of done

- A deliberate 1-byte change to the Salamander codepath fails a golden test naming the upstream tag it was captured against.

## Links

- `contract-fixtures/`
- `docs/native/upstream-spec-watch-runbook.md`
