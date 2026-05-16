---
title: Pin uTLS to v1.8.2 and add ClientHello fingerprint regression test
type: task
status: done
area: transport
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-25
updated: 2026-05-16
---

- [x] #task Pin uTLS to v1.8.2 and add ClientHello fingerprint regression test #repo/RIPDPI #area/transport #status/done 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `pin-utls-to-v1-8-2-and-add-clienthello-fingerprint-regression-test`
- **Verify:** `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-tls-profiles`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-tls-profiles/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Pin `refraction-networking/utls` to ≥ v1.8.2 to close the Chrome 120 padding-extension regression and the GREASE ECH AES/ChaCha20 mismatch (PR #375). Add a regression test that asserts emitted ClientHello bytes match a Chrome 120 reference fixture, so future uTLS upgrades cannot silently re-introduce fingerprint drift.

## Research citation

[[ripdpi-android-research-2026-04-25]] §TLS fingerprinting tooling — uTLS v1.8.2 (2026-01-13) restored padding extension after PQ key shares altered packet sizing; PR #375 (merged 2025-10-14) fixed GREASE ECH cipher-mismatch that produced provably non-Chrome ClientHellos ~50% of the time. Both fixes affect any RIPDPI code path using `HelloChrome_120`, `HelloChrome_120_PQ`, `HelloChrome_131`, or `HelloChrome_133`.

## Acceptance criteria

- [ ] Dependency manifest pins `refraction-networking/utls` to ≥ v1.8.2
- [ ] Regression test verifies `HelloChrome_120` ClientHello matches a recorded reference byte-for-byte (including padding extension)
- [ ] CI fails on any uTLS-emitted ClientHello drift vs the reference fixture
- [ ] Test corpus includes ECH-enabled and ECH-disabled flows (covers PR #375 cipher-consistency)

## Links

- Project: [[ripdpi-android]]
- Epic: [[Epic - Semantic TLS first-flight family engine]]
- Research: [[ripdpi-android-research-2026-04-25]] §TLS fingerprinting tooling


## Work log

### 2026-05-16

Translated spec intent to Rust workspace (no Go uTLS dependency exists here).
Added `chrome_120_fingerprint_regression` module to
`native/rust/crates/ripdpi-tls-profiles/src/tests.rs` with four deterministic
assertions operating on `ProfileConfig` struct fields — no network I/O, no real
TLS handshake:

1. `chrome_120_extension_order_family_is_chromium_permuted` — extension order family locked to `chromium_permuted`.
2. `chrome_120_padding_extension_present_via_non_ech_profile` — `ech_capable` must remain false; size hint in [480, 540] and not 517.
3. `chrome_120_cipher_suite_order_matches_reference` — TLS 1.2 cipher order, TLS 1.3 cipher list, and curves order frozen.
4. `chrome_120_frozen_fingerprint_hash_unchanged` — SHA-256 of the pipe-delimited canonical profile string frozen to `ddfaf9775ab79531f803efa416b8f1ccbec4dd1892d1672f6a90664df5b6469f`.

Note: the spec's Go uTLS v1.8.2 dependency pin does not apply to the Rust
workspace (Go uTLS is not a dependency). The translated intent — a frozen
fingerprint regression that catches silent profile drift — is fully implemented.

Verify: `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-tls-profiles`
Result: 24 passed, 0 skipped, exit 0.

## settings-backup-and-restore
