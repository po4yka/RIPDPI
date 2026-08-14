---
id: TST-1786264762917272
title: Add Hysteria 2 Salamander obfuscation conformance fixtures
kind: chore
status: blocked
area: testing
priority: medium
owner: unassigned
parent: EPC-1786264762917455
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-05-15
updated: 2026-07-26
spec_reason: test-only
status_detail: externally-gated — harness shipped; the 8 conformance goldens need real apernet/hysteria byte vectors at a pinned tag, which are not available locally and cannot be fabricated
---

## Summary

Salamander (`ripdpi-hysteria2/src/salamander.rs`) is the proprietary QUIC obfuscation layer that changes with upstream Hysteria 2 releases. Capture wire-level conformance fixtures so silent upstream divergence is caught by golden tests, not by user reports.

## Context

Salamander is XOR-style obfuscation keyed by a server-supplied key. Reference vectors derived from apernet/hysteria's tests pin behavior to a specific upstream tag.

## Acceptance criteria

- [ ] At least eight obfuscation goldens under `contract-fixtures/hysteria2/<upstream-tag>/salamander/` covering the boundary cases from apernet/hysteria's own tests. **DEFERRED:** upstream byte vectors needed; cannot fabricate conformant fixtures without them. Tracked behind tag-protocol-contract-fixtures-by-upstream-version (closed task).
- [x] (partial, 2026-05-15) `salamander::tests` parses each golden and asserts byte equality on encode and decode. **Regression-boundary fixture shipped** as `salamander_keystream_pinned_for_known_key_and_salt`: pins the blake2b256 keystream derivation for a known (key, salt) and asserts decode of a synthetic ciphertext round-trips to the plaintext. Catches accidental algorithm swaps; not an upstream- conformance check.
- [ ] Goldens carry the upstream tag in the directory name. **DEFERRED:** see first criterion.

## Definition of done

- A deliberate 1-byte change to the Salamander codepath fails a golden test naming the upstream tag it was captured against.

## Links

- `contract-fixtures/`
- `docs/native/upstream-spec-watch-runbook.md`

## Work log

- 2026-06-05: Fixture harness exists (`salamander::tests::upstream_salamander_fixtures_decode_cleanly`) and one synthetic `.bin` file is present at `contract-fixtures/hysteria2/v2/salamander/746f702d736563726574/hello-zero-salt.bin`; the eight upstream-conformance goldens from apernet/hysteria test vectors are still missing — criteria 1 and 3 remain deferred pending sourcing of real upstream byte vectors.
- 2026-06-11: **Confirmed externally-gated; harness shipped, no new goldens.** Searched the whole worktree — zero vendored apernet/hysteria test vectors or captured datagrams are available locally, so real conformance goldens cannot be added without fabricating them (which would only test our impl against itself, exactly what the existing synthetic + `salamander_keystream_pinned_for_known_key_and_salt` already do). Verified the harness is sound and passes today (`salamander::tests` — 3 tests green: roundtrip, keystream-pinned, fixture-walker). Status set to `blocked` (externally-gated). Sourcing path: capture vectors from `apernet/hysteria` at a `SPEC_VERSION.md`-pinned tag per `docs/native/upstream-spec-watch-runbook.md`, then drop them under `contract-fixtures/hysteria2/<tag>/salamander/<key-hex>/` (governed by `golden-bless-discipline.md`). Criteria 1 & 3 remain blocked on that external input.
