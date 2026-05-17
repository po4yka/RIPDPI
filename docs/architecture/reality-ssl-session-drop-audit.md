# Reality SSL_SESSION Drop-Path Audit

> Status: **SUPERSEDED 2026-05-16** — the H1 follow-up commit (see `docs/design/reality-boringssl-patch.md`) replaced the `SSL_SESSION_new`/`SSL_set_session`/`SSL_SESSION_free` codepath in `ripdpi-vless::reality::connect_reality_tls_inner` with the BoringSSL `client_hello_cb` patch. The session-id slot is now written directly into the serialized ClientHello buffer by the callback in [`crate::reality_hook`], so no `SSL_SESSION` object is ever allocated on the Reality path. **The audit subject below no longer exists in the code; the analysis is preserved for historical context only.**
>
> The new unsafe surface lives in `crates/ripdpi-vless/src/reality_hook.rs` and consists of three BoringSSL symbols (`SSL_handshake_get_x25519_private_key`, `SSL_CTX_set_client_hello_cb`, `SSL_get_SSL_CTX`) plus one `extern "C" fn` callback. The callback traps panics with `catch_unwind` and the boxed callback state is owned by an RAII `RealityHookGuard` whose `Drop` reclaims the box — structurally satisfying the "RAII wrapper" recommendation that this audit's Recommendation section was advocating.
>
> Authored: 2026-05-15. Tracking task: `docs/tasks/issues/audit-reality-ssl-session-drop-paths-for-leak-and-double-free.md` (now effectively closed: the audited code has been removed rather than wrapped).

## Scope

`ripdpi-vless::reality::connect_reality_tls_inner` calls into BoringSSL to allocate, configure, and dispose of an `SSL_SESSION` object during the Reality TLS handshake. This audit reviews every codepath through that block for leak and double-free.

The relevant excerpt (post 3b238a29 "Vision buffer-boundary bug, typed Reality FFI"):

```rust
let sid_len = u32::try_from(session_id.len()).map_err(|_| io::Error::other("session_id too large"))?;
unsafe {
    let ssl_ctx = SSL_get_SSL_CTX(ssl_handle.cast_const());
    let sess = SSL_SESSION_new(ssl_ctx.cast_const());
    if sess.is_null() {
        return Err(io::Error::other("SSL_SESSION_new failed"));
    }
    let id_ret = SSL_SESSION_set1_id(sess, session_id.as_ptr(), sid_len);
    let set_ret = SSL_set_session(ssl_handle, sess);
    SSL_SESSION_free(sess);
    if id_ret != 1 || set_ret != 1 {
        return Err(io::Error::other("Reality session_id injection failed"));
    }
}
```

## Refcount accounting

| Operation | refcount delta |
|---|---|
| `SSL_SESSION_new` | +1 (owned by caller) |
| `SSL_set_session` | +1 (attached to `SSL`, dropped on `SSL_free`) |
| `SSL_SESSION_free` | -1 (releases the caller's reference) |

After the block:

- If `set_session` succeeded: the `SSL` holds the only reference; freed automatically on `SSL_free` (the `boring::ssl::Ssl` `Drop` impl).
- If `set_session` failed: the `SSL_SESSION_free` call decremented the refcount to 0 and the session is gone. No leak.
- If `set_session` succeeded but `set1_id` failed earlier: the SSL holds an attached session with no session_id set. The error path returns; `Ssl::drop` cleans up the SSL and its attached session.

## Codepath enumeration

1. **Happy path** — both returns are 1 → session freed by `SSL_free` later. No leak, no double-free.
2. **`SSL_SESSION_new` returns null** → early return; nothing to free. No leak.
3. **`SSL_SESSION_set1_id` returns 0** → `set_session` still attempted, then session freed, then error returned. The SSL may carry an attached session with an empty ID, but it will be freed on `SSL_free`. No leak.
4. **`SSL_set_session` returns 0** → session not attached; manual `SSL_SESSION_free` releases the only reference. No leak.
5. **Panic between `SSL_SESSION_new` and `SSL_SESSION_free`** — there are no panic-inducing operations between those two calls (no allocation, no slice-bounds dereference; only raw FFI). If tokio cancellation could fire, it would have to be in a yield point, but the unsafe block contains no `.await`. **No leak in practice.**
6. **Task cancellation outside the unsafe block** — `?` early return on `u32::try_from` happens *before* `SSL_SESSION_new`, so no leak.

## Verdict

The current code is **leak-safe and free of double-free** in all identified codepaths. The 3b238a29 "typed Reality FFI" commit strengthened this by replacing `*mut c_void` with distinct opaque handle types (`SslHandle`, `SslCtxHandle`, `SslSessionHandle`), making accidental cross-mixing of pointer kinds a compile-time error.

## Recommendation

Although current code is correct, an RAII wrapper would make the safety property *structural* rather than incidental:

```rust
struct ScopedSession(*mut SslSessionHandle);

impl ScopedSession {
    unsafe fn new(ctx: *const SslCtxHandle) -> Option<Self> {
        let p = unsafe { SSL_SESSION_new(ctx) };
        if p.is_null() { None } else { Some(Self(p)) }
    }
    fn as_ptr(&self) -> *mut SslSessionHandle { self.0 }
}

impl Drop for ScopedSession {
    fn drop(&mut self) {
        unsafe { SSL_SESSION_free(self.0) };
    }
}
```

With this wrapper:

- Any future code edit that adds an early-return path between `SSL_SESSION_new` and the manual `SSL_SESSION_free` cannot leak.
- The unsafe surface shrinks: callers handle only the `ScopedSession` API, not the raw FFI symbols.
- The two FFI calls (`new`/`free`) move to two clearly-paired call sites, easier to audit.

**Priority:** medium. The current code is correct; the RAII wrapper is a robustness improvement against future edits. Track separately under the same task; do not block release on this.

## ASan-based regression test

A future `cargo +nightly build -Zsanitizer=address` lane could catch a regression, but BoringSSL's allocator interactions with LSan/ASan are fragile and the lane is not free of false positives. Implementing this is deferred.

## Owner

Native-transport / VLESS owner picks up the RAII wrapper as a follow-up if Reality is touched again.
