# SPEC — `ripdpi-shadowtls`

## Scope

ShadowTLS v3 client. Disguises an arbitrary upstream stream as a TLS session to a cover hostname.

## Upstream

- ihciah/shadow-tls (https://github.com/ihciah/shadow-tls)
- Pin recorded in `SPEC_VERSION.md`

## Handshake

ShadowTLS v3 uses HMAC-SHA1 authentication (truncated to 4 bytes) and HMAC-tagged framing. The server XOR key is SHA-256(password || ServerRandom). Modules:

- `handshake.rs` — handshake exchange
- `hmac.rs` — HMAC computation
- `frames.rs` — framed data layer
- `stream.rs` — async stream wrapper around the framed layer
- `client.rs` — top-level client

The client switches to ShadowTLS framing after the cover ServerHello, before a
Certificate flight is sent or processed; this is not a normal certificate-
validated TLS session.

## Known divergences from upstream

- v2 is intentionally unsupported; `docs/architecture/shadowtls-version-policy.md` records the v3-only decision and the version-mismatch classifier surface.
- The feature-gated in-tree `ShadowTlsLoopback` is a test fixture, not a production server or external-interop oracle.
- The outer ClientHello is plain rustls: it has no ALPN and carries the rustls JA3 and JA4 fingerprint. `tls_fingerprint_profile` applies only to the inner REALITY layer, not to this ClientHello.
- A cover HelloRetryRequest is rejected with `ShadowTlsFailureKind::CoverHelloRetry`. The client rewrites the session id, so rustls cannot continue the handshake. The cover server must accept an X25519 key share.
- The client never sends a TLS Finished. After its ClientHello and the
  compatibility ChangeCipherSpec, its next record is an HMAC data frame, sent
  as soon as the first server application-data record arrives. A real TLS 1.3
  client, and the upstream client, which completes the handshake through the
  server before it sends data frames, send a Finished record (53 bytes with a
  SHA-256 suite) after the whole server flight. A passive observer can tell
  this client apart by that record length and ordering. Closing the gap needs
  a completed handshake on the client side.

## Non-goals

- v2 wire support.
- Server-side implementation.
