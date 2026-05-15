---
title: Add Miri test pass for VLESS Reality FFI unsafe blocks
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

- [ ] #task Add Miri test pass for VLESS Reality FFI unsafe blocks #repo/RIPDPI #area/testing #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-miri-test-pass-for-vless-reality-ffi-unsafe-blocks`
- **Verify:** `cargo +nightly miri test -p ripdpi-vless --features miri-stubs`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-vless/**`, `scripts/ci/**`, `docs/tasks/GOAL_LEDGER.md`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Add a Miri-friendly test path for `ripdpi-vless::reality` so the
unsafe FFI to BoringSSL (`SSL_set_client_random`, `SSL_SESSION_new`,
`SSL_SESSION_set1_id`, `SSL_set_session`, `SSL_SESSION_free`,
`SSL_get_SSL_CTX`) can be exercised under Miri via stubs, catching
provenance, UB, and aliasing bugs.

## Context

Miri cannot run real BoringSSL FFI, but the *Rust-side* glue around
those calls — pointer construction, lifetime sequencing, refcount
discipline on `SSL_SESSION_*` — can be tested with a `miri-stubs`
feature that replaces the `extern "C"` symbols with safe Rust mocks
asserting call shape and order.

This is the most concentrated unsafe surface in the workspace.

## Acceptance criteria

- [ ] A `miri-stubs` feature in `ripdpi-vless` swaps the six FFI
    declarations for safe Rust stubs that record call sequences.
- [ ] At least one test under `#[cfg(miri)]` exercises the full
    Reality session_id construction path against the stubs.
- [ ] CI grows a `miri-vless` lane (nightly toolchain, allowed to
    fail initially) that runs the stub-backed test.
- [ ] Miri report is clean (no UB) for the stub path.

## Definition of done

- A future change that introduces UB in the FFI glue is caught by
  the Miri lane before merge.

## Links

- [[pin-boringssl-symbols-with-build-time-existence-check]]
- [[audit-reality-ssl-session-drop-paths-for-leak-and-double-free]]
- [[rust-soundness-policy]]
