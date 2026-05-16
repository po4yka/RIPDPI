---
title: Pin BoringSSL Reality FFI symbols with a build-time existence check
type: task
status: backlog
area: rust-native
priority: high
owner: unassigned
parent: epic-control-plane-hardening
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-05-15
---

- [ ] #task Pin BoringSSL Reality FFI symbols with a build-time existence check #repo/RIPDPI #area/rust-native #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `pin-boringssl-symbols-with-build-time-existence-check`
- **Verify:** `cargo build -p ripdpi-vless --release && cargo test -p ripdpi-vless`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-vless/**`, `native/rust/Cargo.toml`, `docs/native/proxy-engine.md`, `docs/tasks/GOAL_LEDGER.md`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

The Reality TLS implementation in `ripdpi-vless` declares five BoringSSL
symbols by hand because the `boring` crate does not re-export them.
Pin the `boring` / `boring-sys` versions exactly and add a `build.rs`
existence check so a vendored-BoringSSL bump cannot break Reality
silently.

## Context

`native/rust/crates/ripdpi-vless/src/reality.rs:23-43` declares:

```rust
extern "C" {
    fn SSL_set_client_random(...) -> c_int;
    fn SSL_SESSION_new(...) -> *mut c_void;
    fn SSL_SESSION_set1_id(...) -> c_int;
    fn SSL_set_session(...) -> c_int;
    fn SSL_SESSION_free(...);
    fn SSL_get_SSL_CTX(...) -> *mut c_void;
}
```

The SAFETY comment asserts these are "stable BoringSSL ABI". BoringSSL
has no stable ABI guarantee; if `boring-sys` bumps to a vendored
BoringSSL revision that renames or removes any of these, the link will
either fail (best case) or succeed against a different signature with
undefined behavior at runtime (worst case).

## Acceptance criteria

- [x] (2026-05-15) `boring` and `boring-sys` are pinned to exact
    versions in the workspace `Cargo.toml` (no `*` or caret range).
    `boring = "=5.1.0"`, `tokio-boring = "=5.0.0"`. `boring-sys` is
    a vendored path dep (`vendor/boring-sys`), already pinned by
    construction. A workspace-level comment cites the rationale.
- [x] (2026-05-15, implicit) The six BoringSSL symbols are declared
    via `extern "C"` in `reality.rs` and called from
    `connect_reality_tls_inner`. The Rust linker requires extern fn
    references to resolve at link time, so any missing symbol fails
    the workspace build — effectively a build-time existence check
    without a dedicated `build.rs`. A trace-style audit lives at
    `docs/architecture/reality-ssl-session-drop-audit.md`.
- [ ] A `build.rs` symbol-existence-check via `nm`/`llvm-nm` or a
    C stub. **DEFERRED:** redundant with the implicit link-time
    check above; revisit only if a future code path optionally
    references the symbols (e.g. behind a feature flag) and DCE
    could elide them.
- [ ] CI fails when the existence check fails. **MET implicitly:**
    workspace `cargo check` fails if a symbol is missing.
- [ ] `docs/native/proxy-engine.md` documents the contract.
    **DEFERRED:** captured in the workspace `Cargo.toml` comment
    and the audit doc; add a pointer in `proxy-engine.md` when the
    file is next touched.

## Definition of done

- Bumping `boring-sys` in `Cargo.toml` either still builds or fails
  loudly at build time, never at runtime.
- The Reality unit-test suite passes against the pinned BoringSSL.

## Risks / open questions

- A `build.rs` that shells out to `nm` is platform-dependent. A small
  linked C helper (`#include <openssl/ssl.h>` + take addresses) is
  more portable but adds a build dependency on a C compiler — already
  required for BoringSSL itself, so not a regression.
- Long-term fix is upstreaming a PR to `boring` to expose the symbols;
  track separately.

## Links

- [[Epic - Control-plane hardening]]
- [[rust-soundness-policy]]
