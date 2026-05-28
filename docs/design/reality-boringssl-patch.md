# Reality protocol — BoringSSL key_share patch design

**Status: implemented as of 2026-05-16.** Audit findings C1, C2, H1 are landed across commits `c1437b65` (C1+C2 crypto primitive), `0155564c` (BoringSSL vendor patch), and the H1 follow-up (Rust callback glue + frozen-vector test + Go reference vector). The interoperable handshake now flows through:

1. `ripdpi-vless::reality::connect_reality_tls_inner` builds the SSL connector.
2. `ripdpi-vless::reality_hook::install_reality_client_hello_hook` installs the BoringSSL `client_hello_cb` on the SSL_CTX.
3. Inside `ssl_add_client_hello` (vendored patch in `native/rust/vendor/boring-sys/`), the callback fires after the body is serialized but before `add_message` consumes it.
4. The callback reads the X25519 key_share private key via the patched `SSL_handshake_get_x25519_private_key`, pulls client_random out of `msg[6..38]`, and calls into `ripdpi-vless::reality_seal::seal_session_id` to produce the AES-256-GCM sealed session_id.
5. The 32 sealed bytes overwrite `msg[39..71]` before the transcript hash absorbs them.

Cross-implementation oracle: `test-lab/reality-vector/main.go` reproduces the seal in pure Go (mirroring xray-core `reality.go`) and prints the same 32 bytes that the Rust frozen-vector test asserts on.

Remaining work that is intentionally **not** part of H1 itself:

- Native `.so` size baselines will need to be updated once a real Android build runs through the patched boring-sys. Gated by the project's hook-enforced `*baseline*` policy.
- `test-lab/` integration test that brings up a real Xray-core Reality server in docker-compose and asserts the full handshake completes against it. Tracked in the "Integration test" section below.

Archived pre-implementation design context follows. In that section, "current" describes the pre-H1 implementation that existed before the commits named above, not the current source tree.

## Background — archived verified non-interoperability

Three independent bugs in the pre-H1 Rust implementation, verified against xray-core upstream (`github.com/XTLS/Xray-core/transport/internet/reality/reality.go`) and the XTLS/REALITY package (`tls.go`):

1. **Wrong HKDF salt**. Pre-H1: `client_random[20..]` (last 12 B). Spec: `Random[:20]` (first 20 B). Cite: xray-core/transport/internet/reality/reality.go:167 `hkdf.New(sha256.New, uConn.AuthKey, hello.Random[:20], []byte("REALITY")).Read(uConn.AuthKey)`.
2. **Wrong cipher**. Pre-H1: AES-128-ECB on first 16 B of `session_id`. Spec: **AES-256-GCM** with the full 32-byte AuthKey, nonce = `Random[20:]` (last 12 B), AAD = the entire raw ClientHello with the session_id slot zeroed, plaintext = `SessionId[:16]`. Cite: xray-core/transport/internet/reality/reality.go:170 `aead := crypto.NewAesGcm(uConn.AuthKey)` and :174 `aead.Seal(hello.SessionId[:0], hello.Random[20:], hello.SessionId[:16], hello.Raw)`. The GCM output (16 B ciphertext + 16 B tag) fills the entire 32-byte `SessionId`.
3. **Architectural blocker — wrong ECDH partner key**. Pre-H1: `EphemeralSecret::random()` generated an independent X25519 keypair. Spec: client MUST ECDH with the **TLS 1.3 client `key_share` extension private key**. Cite: xray-core/.../reality.go:156-163 `ecdhe := uConn.HandshakeState.State13.KeyShareKeys.Ecdhe; uConn.AuthKey, _ = ecdhe.ECDH(publicKey)`. The server side reads the client's key_share extension public key from the ClientHello (`XTLS/REALITY/tls.go:215-217` `for _, keyShare := range hs.clientHello.keyShares { if keyShare.group == X25519 ... peerPub = keyShare.data }`) and ECDHs with its static Reality private key. The two sides arrive at the same AuthKey only when the client's ECDH used the TLS key_share private key.

The pre-H1 Rust code generated a separate ephemeral X25519 keypair with no relationship to the TLS key_share, so the server's AuthKey could never match the client's. Patching salt and cipher alone did not enable interop.

## Constraint — BoringSSL exposes nothing useful

Verified by reading the vendored BoringSSL headers (`native/rust/vendor/boring-sys/deps/boringssl/include/openssl/ssl.h`) and source (`ssl/ssl_key_share.cc`, `ssl/internal.h`):

- No public C accessor returns the X25519 private key of an active TLS 1.3 client `key_share` entry.
- `X25519KeyShare::private_key_[32]` is a C++ `private` member at `ssl/ssl_key_share.cc:193`; only `SerializePrivateKey` / `DeserializePrivateKey` virtual methods touch it, and they are C++-internal (used by `handoff.cc` for server-side offloading).
- The `boring` v5.1 crate exposes no wrapper for these.
- `SSL_set1_client_key_shares` (in `bindings.rs`) lets the caller choose key_share groups but BoringSSL still generates the ephemeral key internally.
- `SSL_PRIVATE_KEY_METHOD` operates on the certificate authentication key, not the ephemeral key_share — not applicable.
- `SSL_CTX_set_msg_callback` fires at the right moment but only as `const uint8_t *` — cannot mutate the ClientHello.
- `SSL_CTX_add_client_custom_ext` is not exposed in this BoringSSL build.

Conclusion: there is no patch-free path on BoringSSL. Either patch the vendored BoringSSL or replace it with a TLS stack that exposes key shares (e.g., a `rustls` fork).

## Recommended path — patch vendored BoringSSL

Two new symbols plus one callback API. Total surface: 5 BoringSSL files, ~80 lines C/C++, plus ~130 lines Rust glue.

### New BoringSSL APIs

`include/openssl/ssl.h`:

```c
// Returns the 32-byte X25519 private key from the first X25519 key_share
// entry on |ssl|. Valid only between ssl_setup_key_shares() and the call to
// add_message() inside ssl_add_client_hello. Returns 1 on success, 0 if no
// X25519 share exists. |out| must be a caller-allocated 32-byte buffer.
OPENSSL_EXPORT int SSL_handshake_get_x25519_private_key(
    const SSL *ssl, uint8_t out[32]);

// Callback fired inside ssl_add_client_hello after the ClientHello body is
// serialized to |msg| but before add_message() consumes it. The callback may
// mutate |msg| in place. |msg_len| includes the 4-byte handshake header.
typedef int (*SSL_client_hello_cb_fn)(SSL *ssl, uint8_t *msg, size_t msg_len,
                                      void *arg);
OPENSSL_EXPORT void SSL_CTX_set_client_hello_cb(SSL_CTX *ctx,
                                                SSL_client_hello_cb_fn cb,
                                                void *arg);
OPENSSL_EXPORT void SSL_set_client_hello_cb(SSL *ssl,
                                            SSL_client_hello_cb_fn cb,
                                            void *arg);
```

### Hook point

Inside `ssl_add_client_hello` (`ssl/handshake_client.cc:218`), the order is:

1. `ssl_setup_key_shares` (line 437) — X25519 keypair created in `hs->key_shares[0]->private_key_`.
2. `ssl_add_client_hello` (line 440) — serializes the body into a local `Array<uint8_t> msg`.
3. **New callback fires here** — callback reads key_share private key, computes Reality session_id, patches `msg[39..71]`.
4. `add_message` appends `msg` to the record flight AND updates the transcript hash (`s3_both.cc:140-142`).

Steps 3 must run before step 4 so the Reality-patched session_id enters the transcript hash, matching xray-core behaviour. `ssl_setup_key_shares` runs only once in the no-HRR path; on `HelloRetryRequest` (`tls13_client.cc:321`) it runs again with the server-selected group, and the callback fires twice — the design must support re-entry.

### Files touched in vendored BoringSSL

1. `ssl/ssl_key_share.cc` — add `virtual bool CopyPrivateKeyBytes(uint8_t out[32], size_t *out_len) const { return false; }` to `SSLKeyShare`; override in `X25519KeyShare` to memcpy 32 bytes; add `SSL_handshake_get_x25519_private_key` free function.
2. `ssl/internal.h` — `SSLKeyShare` virtual addition; new `SSL_CTX` fields `client_hello_cb` and `client_hello_cb_arg`.
3. `ssl/handshake_client.cc` — invoke `client_hello_cb` at the end of `ssl_add_client_hello` body assembly, before `add_message`.
4. `ssl/ssl_lib.cc` — setters for the new SSL/SSL_CTX fields.
5. `include/openssl/ssl.h` — public declarations of the three new symbols.

### Rust-side architecture

New module `ripdpi-vless/src/reality_hook.rs`. Extends the existing `extern "C"` block in `reality.rs` with two new symbols:

```rust
fn SSL_handshake_get_x25519_private_key(ssl: *const Ssl, out: *mut u8) -> c_int;
fn SSL_set_client_hello_cb(ssl: *mut Ssl, cb: RealityHelloCb, arg: *mut c_void);
```

Sequence (replacing `connect_reality_tls_inner`):

1. `boring::rand::rand_bytes(&mut client_random)` — 32 bytes.
2. Build `SslContextBuilder`, set `SslVerifyMode::NONE`.
3. `connector.configure().into_ssl(server_name)`.
4. `SSL_set_client_random(ssl, client_random)`.
5. Allocate boxed `RealityCallbackState { client_random, server_pubkey, short_id }`, leak to raw pointer.
6. `SSL_set_client_hello_cb(ssl, reality_client_hello_cb, state_ptr)`.
7. `tokio_boring::SslStreamBuilder::new(ssl, stream).connect().await`.

Inside `reality_client_hello_cb` (synchronous `extern "C" fn`):

1. Allocate `priv_key: [u8; 32]`; call `SSL_handshake_get_x25519_private_key(ssl, priv_key.as_mut_ptr())`.
2. ECDH: `shared = X25519(priv_key, state.server_pubkey)`.
3. HKDF-SHA256: salt = `state.client_random[..20]`, IKM = `shared`, info = `"REALITY"`, OKM = 32-byte `auth_key`.
4. Build plaintext `session_id_pt[..16]`: `[Version_x, Version_y, Version_z, 0, timestamp_be(4), short_id_padded(8)]`.
5. Build AAD: copy `msg[..msg_len]`, zero `aad[39..71]`.
6. AES-256-GCM seal: nonce = `state.client_random[20..32]`, plaintext = `session_id_pt`, AAD = `aad`, output written to `msg[39..71]` (16 B ciphertext + 16 B tag).
7. Return 1.

Box is freed via the `Drop` of an RAII guard held in the connect closure; the callback never owns the state — it borrows.

### Test strategy

- **Unit**: frozen test vector from a Go reference program living at `test-lab/reality-vector/main.go`. Fixed inputs (priv_key, client_random, server_pubkey, short_id, raw_hello) → expected session_id and expected patched ClientHello bytes. Run on every PR via `cargo test -p ripdpi-vless`.
- **Integration**: extend `test-lab/` with an Xray-core Reality server container. Self-signed cert, known keypair, known shortid, mock origin in existing `test-lab/caddy/`. New CI job `cargo test --test reality_integration` brings the stack up via docker-compose.
- **Fuzz target** (low priority): `reality_seal_fuzz` exercising `seal_reality_session_id(...)` against arbitrary raw_hello bytes — verify no panic, always 32 B output.

### Maintenance risk

Medium-high over 18 months. `X25519KeyShare::private_key_` is a private C++ member with no ABI guarantee. BoringSSL is actively developed; the X25519+Kyber768 hybrid (`ssl/ssl_key_share.cc:197`) shows the key_share layer is still evolving. Each BoringSSL vendor bump requires re-validating the patch.

Mitigations:
- Build-time `static_assert(sizeof(X25519KeyShare) == sizeof(SSLKeyShare) + 32 + 32)` in the patch.
- Frozen test vector catches any silent regression in key extraction.
- Time-box: if a vendor bump breaks the patch and it cannot be reforked within 5 business days, pivot to `rustls` per § Pivot.

## Pivot — `rustls` fork

If the BoringSSL path had been blocked, the fallback was to replace `tokio_boring` with `tokio-rustls` + a `rustls` fork that exposes `ClientHelloPayload::key_shares` private keys. Scope estimate at the time was ~400 LOC across `ripdpi-vless`, `ripdpi-tls-profiles`, `ripdpi-xhttp` (xHTTP transports over Reality). `ripdpi-tls-profiles` currently configures `boring::ssl::SslContextBuilder`; a rustls pivot would rebuild on top of `rustls::ClientConfig` and a `rustls-fingerprint`/`rustls-fork-utls` equivalent. The TLS fingerprint profiles (Chrome/Firefox/Safari/Edge cipher orders, GREASE, extension permutation) would have to be re-validated against the new stack.

## Scope estimate

- BoringSSL C/C++ patch: ~80 LOC, 5 files.
- Rust extern declarations: ~10 LOC in `reality.rs`.
- Rust callback + crypto: ~130 LOC in `reality_hook.rs`.
- Unit test + frozen vector: ~80 LOC Rust + ~60 LOC Go in `test-lab/reality-vector/`.
- Integration test: ~120 LOC plus docker-compose updates.

Total ≈ 470 LOC. Estimated wall-clock: ~10 business days for an engineer familiar with both BoringSSL internals and the RIPDPI build system.

## References

- `native/rust/crates/ripdpi-vless/src/reality_seal.rs` — current C1/C2 AES-256-GCM session_id sealing primitive
- `native/rust/crates/ripdpi-vless/src/reality_hook.rs` — current H1 BoringSSL ClientHello hook glue
- `native/rust/vendor/boring-sys/deps/boringssl/ssl/ssl_key_share.cc:140-194` — `X25519KeyShare`; `private_key_[32]` at L193
- `native/rust/vendor/boring-sys/deps/boringssl/ssl/extensions.cc:2190` — `ssl_setup_key_shares`
- `native/rust/vendor/boring-sys/deps/boringssl/ssl/handshake_client.cc:218-234` — `ssl_add_client_hello`; `Array<uint8_t> msg` finalized before `add_message`
- `native/rust/vendor/boring-sys/deps/boringssl/ssl/handshake_client.cc:671` — `hs->key_shares.clear()` (private key destroyed)
- `native/rust/vendor/boring-sys/deps/boringssl/ssl/s3_both.cc:137` — existing `ssl_do_msg_callback` (read-only, too late for AAD pre-image)
- xray-core upstream client: `github.com/XTLS/Xray-core/transport/internet/reality/reality.go:139-176`
- xray-core upstream server: `github.com/XTLS/REALITY/tls.go:214-251`
