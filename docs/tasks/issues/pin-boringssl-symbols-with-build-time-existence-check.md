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

## Summary

The Reality TLS implementation in `ripdpi-vless` declares three BoringSSL symbols by hand because the `boring` crate does not re-export them. Pin the `boring` / `boring-sys` versions exactly and add a `build.rs` existence check so a vendored-BoringSSL bump cannot break Reality silently.

**Two of the three symbols (`SSL_handshake_get_x25519_private_key`, `SSL_CTX_set_client_hello_cb`) are part of the H1 vendor patch — they do not exist in upstream BoringSSL. A `boring-sys` bump must either carry the same patch forward or this task's existence check must fail loudly so the breakage surfaces before the link.**

## Context

`native/rust/crates/ripdpi-vless/src/reality_hook.rs` declares (post the 2026-05-16 H1 follow-up):

```rust
extern "C" {
    // Patched-in by the H1 BoringSSL vendor patch
    // (commit 0155564c). Not present in upstream BoringSSL.
    fn SSL_handshake_get_x25519_private_key(ssl: *const SslHandle, out: *mut u8) -> c_int;
    fn SSL_CTX_set_client_hello_cb(ctx: *mut SslCtxHandle, cb: RealityHelloCb, arg: *mut c_void);

    // Stock BoringSSL — stable accessor.
    fn SSL_get_SSL_CTX(ssl: *const SslHandle) -> *mut SslCtxHandle;
}
```

The SAFETY comment asserts these are "stable BoringSSL ABI" for the third symbol only; the first two are part of the vendored patch we maintain. BoringSSL has no stable ABI guarantee, and the patched symbols have no stability guarantee at all. If `boring-sys` bumps to a vendored BoringSSL revision that does not carry the H1 patch forward, or that renames any of the three symbols, the link will either fail (best case) or succeed against a different signature with undefined behavior at runtime (worst case).

## Acceptance criteria

- [x] (2026-05-15) `boring` and `boring-sys` are pinned to exact versions in the workspace `Cargo.toml` (no `*` or caret range). `boring = "=5.1.0"`, `tokio-boring = "=5.0.0"`. `boring-sys` is a vendored path dep (`vendor/boring-sys`), already pinned by construction. A workspace-level comment cites the rationale.
- [x] (2026-05-15, implicit) The original BoringSSL symbols were declared via `extern "C"` in `reality.rs` and called from `connect_reality_tls_inner`. The current H1 ClientHello hook uses the three patched symbols documented in `docs/design/reality-boringssl-patch.md`; the Rust linker requires extern fn references to resolve at link time, so any missing symbol fails the workspace build — effectively a build-time existence check without a dedicated `build.rs`.
- [ ] A `build.rs` symbol-existence-check via `nm`/`llvm-nm` or a C stub. **DEFERRED:** redundant with the implicit link-time check above; revisit only if a future code path optionally references the symbols (e.g. behind a feature flag) and DCE could elide them.
- [ ] CI fails when the existence check fails. **MET implicitly:** workspace `cargo check` fails if a symbol is missing.
- [ ] `docs/native/proxy-engine.md` documents the contract. **DEFERRED:** captured in the workspace `Cargo.toml` comment and `docs/design/reality-boringssl-patch.md`; add a pointer in `proxy-engine.md` when the file is next touched.

## Definition of done

- Bumping `boring-sys` in `Cargo.toml` either still builds or fails loudly at build time, never at runtime.
- The Reality unit-test suite passes against the pinned BoringSSL.

## Risks / open questions

- A `build.rs` that shells out to `nm` is platform-dependent. A small linked C helper (`#include <openssl/ssl.h>` + take addresses) is more portable but adds a build dependency on a C compiler — already required for BoringSSL itself, so not a regression.
- Long-term fix is upstreaming a PR to `boring` to expose the symbols; track separately.

## Links

- [[Epic - Control-plane hardening]]
- [[rust-soundness-policy]]
