---
title: Audit Reality SSL_SESSION drop paths for leak and double-free
type: task
status: backlog
area: rust-native
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-05-15
---

- [ ] #task Audit Reality SSL_SESSION drop paths for leak and double-free #repo/RIPDPI #area/rust-native #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `audit-reality-ssl-session-drop-paths-for-leak-and-double-free`
- **Verify:** `cargo test -p ripdpi-vless --release && cargo +nightly build -p ripdpi-vless -Zsanitizer=leak --target $(rustc -vV | sed -n 's/host: //p')`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-vless/**`, `docs/tasks/GOAL_LEDGER.md`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

`reality.rs` constructs `SSL_SESSION` objects via `SSL_SESSION_new`,
attaches them with `SSL_set_session` (which increments the refcount),
and is responsible for `SSL_SESSION_free` on the constructed handle.
Audit every codepath including handshake-failure, connect-cancel, and
panic-unwind branches for leak and double-free.

## Context

Refcount-managed FFI handles are the classic source of UB in unsafe
Rust glue. Reality combines this with `SSL_set_client_random` and
custom session-id encoding, so misuse cascades silently.

## Acceptance criteria

- [ ] A `ScopedSession` RAII wrapper owns the `SSL_SESSION` pointer
    with `Drop` calling `SSL_SESSION_free` exactly once.
- [ ] Every call to `SSL_SESSION_new` in `reality.rs` is replaced
    with a `ScopedSession::new` call.
- [ ] A unit test deliberately panics mid-handshake (under a
    `cfg(test)` injection point) and asserts no leak via address
    sanitizer.
- [ ] A short note in `docs/native/proxy-engine.md` documents the
    refcount discipline.

## Definition of done

- LeakSanitizer (or AddressSanitizer) reports no leak under the test
  suite for `ripdpi-vless`.

## Links

- [[pin-boringssl-symbols-with-build-time-existence-check]]
- [[add-miri-test-pass-for-vless-reality-ffi-unsafe-blocks]]
- [[rust-soundness-policy]]
