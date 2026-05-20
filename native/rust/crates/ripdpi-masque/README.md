# ripdpi-masque

**Responsibility:** the MASQUE proxy transport — HTTP/3 (with HTTP/2 fallback)
CONNECT-UDP, Privacy Pass authentication, and the Cloudflare provider adapter.
**Layer:** L7 — relay transports.

## Stable identifiers / contracts

Selected by `relay_kind = "masque"`. The MASQUE / CONNECT-UDP exchange and the
Privacy Pass token flow are fixed protocol contracts. HTTP/3→HTTP/2 fallback is
reported in telemetry; Cloudflare auth failures are classified by cause.

## Dependency direction

**Upstream:** `ripdpi-hysteria2`, `ripdpi-tls-profiles` (`quinn`, `boring`,
`reqwest`, `rustls`). **Downstream:** `ripdpi-relay-core`.

## Non-root fallback

No privileged operations — runs fully on non-rooted devices. See
[`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md).

## Extension checklist

1. Add MASQUE provider adapters or auth modes behind the existing request/
   response types.
2. Preserve configured endpoint paths for both H3 and H2.

---
See [`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md),
[`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md),
and [`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md).
