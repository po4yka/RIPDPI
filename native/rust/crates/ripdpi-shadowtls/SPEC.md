# SPEC — `ripdpi-shadowtls`

## Scope

ShadowTLS v3 client. Disguises an arbitrary upstream stream as a TLS session to a cover hostname.

## Upstream

- ihciah/shadow-tls (https://github.com/ihciah/shadow-tls)
- Pin recorded in `SPEC_VERSION.md`

## Handshake

ShadowTLS v3 uses HKDF-derived authentication and HMAC-tagged framing. Modules:

- `handshake.rs` — handshake exchange
- `hmac.rs` — HMAC computation
- `frames.rs` — framed data layer
- `stream.rs` — async stream wrapper around the framed layer
- `client.rs` — top-level client

The TLS cover handshake is performed against the configured upstream server name; the cover certificate is observed but not validated by client trust roots (this is the obfuscation point).

## Known divergences from upstream

- v2 is unsupported pending policy decision; see `docs/tasks/issues/add-shadowtls-v2-compatibility-or-document-v3-only.md`.
- No in-tree server; see `docs/tasks/issues/add-shadowtls-loopback-test-server-for-soak-runs.md`.

## Non-goals

- v2 wire support.
- Server-side implementation.
