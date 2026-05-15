---
title: Add ShadowTLS loopback test server for soak runs
type: task
status: backlog
area: testing
priority: low
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-05-15
---

- [ ] #task Add ShadowTLS loopback test server for soak runs #repo/RIPDPI #area/testing #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-shadowtls-loopback-test-server-for-soak-runs`
- **Verify:** `cargo test -p ripdpi-shadowtls --release -- soak`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-shadowtls/**`, `docs/tasks/GOAL_LEDGER.md`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

`ripdpi-shadowtls` only ships a client. Add a minimal loopback
test-server (under `tests/` or behind a `test-server` feature) so
soak tests, fuzz harnesses, and future server-side conformance
work have a controlled counterpart.

## Context

Without an in-tree server, every test path that needs a peer must
rely on a real upstream or skip. A small reference server also
makes round-trip golden capture trivial.

## Acceptance criteria

- [ ] A `test-server` feature compiles a minimal HKDF/HMAC handshake
    server.
- [ ] At least one soak test (`#[ignore]` by default) drives N
    handshakes back-to-back through the test server and asserts no
    leak.
- [ ] The README notes that this server is **not** a production
    implementation.

## Definition of done

- Test server compiles and is exercised by at least one soak case.

## Links

- [[add-shadowtls-v2-compatibility-or-document-v3-only]]
