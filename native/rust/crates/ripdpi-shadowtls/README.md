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

## Test server (`test-server` feature)

The `test-server` feature compiles `ShadowTlsLoopback` (`src/loopback.rs`): an
in-process loopback server that completes the v3 handshake with this crate's
client and HMAC-echoes application data, for soak and round-trip tests. It is a
**dev/test fixture, NOT a production ShadowTLS server implementation** — it emits
a self-signed cover ServerHello and does not proxy to a real cover host. Never
enable `test-server` in a release build. The back-to-back-handshake soak case is
`#[ignore]` by default; run it with
`cargo nextest run -p ripdpi-shadowtls --run-ignored all`.

## Extension checklist

1. Add ShadowTLS features behind the existing frame/handshake types.
2. Never change the v3 handshake or HMAC scheme — it is an interop contract.

---
See [`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md),
[`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md),
and [`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md).
