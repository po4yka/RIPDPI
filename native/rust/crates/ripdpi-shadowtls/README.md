# ripdpi-shadowtls

**Responsibility:** the ShadowTLS v3 relay transport — stream camouflage that
wraps proxied traffic behind a real TLS handshake to a cover host.
**Layer:** L7 — relay transports.

## Stable identifiers / contracts

Selected by `relay_kind = "shadowtls_v3"`. The ShadowTLS v3 handshake and HMAC
framing are fixed protocol contracts that must interoperate with external
ShadowTLS servers.

## Dependency direction

**Upstream:** none internal (`tokio`, `rustls`). **Downstream:**
`ripdpi-relay-core`.

## Non-root fallback

No privileged operations — runs fully on non-rooted devices. See
[`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md).

## Extension checklist

1. Add ShadowTLS features behind the existing frame/handshake types.
2. Never change the v3 handshake or HMAC scheme — it is an interop contract.

---
See [`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md),
[`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md),
and [`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md).
