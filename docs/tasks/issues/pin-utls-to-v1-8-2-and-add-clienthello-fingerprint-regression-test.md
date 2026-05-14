---
title: Pin uTLS to v1.8.2 and add ClientHello fingerprint regression test
type: task
status: backlog
area: transport
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-25
updated: 2026-04-25
---

- [ ] #task Pin uTLS to v1.8.2 and add ClientHello fingerprint regression test #repo/RIPDPI #area/transport #status/backlog 🔼

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


## settings-backup-and-restore
