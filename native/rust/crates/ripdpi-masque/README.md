# ripdpi-masque

**Responsibility:** the MASQUE proxy transport — HTTP/3 (with HTTP/2 fallback) CONNECT-UDP, standard generic/self-hosted provider authentication, provider adapters, and RFC conformance tests.
**Layer:** L7 — relay transports.

## Stable identifiers / contracts

Selected by `relay_kind = "masque"`. The MASQUE / CONNECT-UDP exchange is governed by RFC 9298 and RFC 9297. HTTP/3 to HTTP/2 fallback is reported in telemetry; provider auth failures are classified by cause. Current auth modes include bearer/token, preshared, Privacy Pass provider retry, and Cloudflare mTLS/geohash metadata paths.

## Dependency direction

**Upstream:** `ripdpi-hysteria2`, `ripdpi-tls-profiles` (`quinn`, `boring`, `reqwest`, `rustls`). **Downstream:** `ripdpi-relay-core`.

## Non-root fallback

No privileged operations — runs fully on non-rooted devices. See [`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md).

## Extension checklist

1. Add MASQUE provider adapters or auth modes behind the existing request/response types.
2. Preserve configured endpoint paths for both H3 and H2.

## Conformance

The current conformance audit and hardening test plan is in [`CONFORMANCE.md`](CONFORMANCE.md). `start_provider_stub` tests are deployer token-provider fixtures and are not a substitute for a conformant RFC 9298 CONNECT-UDP proxy fixture.

## Non-goals

- iCloud Private Relay provider integration.
- CONNECT-IP from RFC 9484.
- MASQUE server/proxy role, except for local test fixtures used to validate the client.

---
See [`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md), [`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md), and [`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md).
