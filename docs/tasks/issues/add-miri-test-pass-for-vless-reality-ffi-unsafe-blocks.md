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

Add a Miri-friendly test path for `ripdpi-vless::reality_hook` so the unsafe FFI to the patched BoringSSL (three symbols: `SSL_handshake_get_x25519_private_key`, `SSL_CTX_set_client_hello_cb`, `SSL_get_SSL_CTX`) plus the `extern "C" fn` callback (`reality_client_hello_cb`) can be exercised under Miri via stubs, catching provenance, UB, and aliasing bugs.

## Context

Miri cannot run real BoringSSL FFI, but the *Rust-side* glue around those calls — pointer construction in `install_reality_client_hello_hook`, the `Box::into_raw` / `Box::from_raw` round-trip in `RealityHookGuard::drop`, the panic trap via `catch_unwind` in `reality_client_hello_cb`, and the `std::slice::from_raw_parts_mut` view over `msg` inside `reality_client_hello_cb_inner` — can be tested with a `miri-stubs` feature that replaces the `extern "C"` declarations with safe Rust mocks asserting call shape and order.

This is the most concentrated unsafe surface in the workspace; the 2026-05-16 H1 follow-up reduced it from 6 BoringSSL symbols + manual SSL_SESSION lifetime management to 3 BoringSSL symbols + an RAII-guarded callback, so the Miri surface is smaller now but the need for it is unchanged.

## Acceptance criteria

- [ ] A `miri-stubs` feature in `ripdpi-vless` swaps the three FFI declarations for safe Rust stubs that record call sequences and can simulate "no X25519 key share" + "key share present" cases.
- [ ] At least one test under `#[cfg(miri)]` exercises the full callback round-trip: install hook → simulate `ssl_add_client_hello` calling the cb → guard drop reclaims the box.
- [ ] CI grows a `miri-vless` lane (nightly toolchain, allowed to fail initially) that runs the stub-backed test.
- [ ] Miri report is clean (no UB) for the stub path.

## Definition of done

- A future change that introduces UB in the FFI glue is caught by the Miri lane before merge.

## Links

- [[pin-boringssl-symbols-with-build-time-existence-check]]
- [[rust-soundness-policy]]
- `docs/design/reality-boringssl-patch.md` — H1 design + status
- `docs/architecture/reality-ssl-session-drop-audit.md` — historical audit of the previous (now-deleted) SSL_SESSION FFI surface
