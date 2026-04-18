# MASQUE Current State

This note describes the current MASQUE implementation in RIPDPI and the validation work that still remains.

## Implemented Transport Surface

RIPDPI currently ships:

- HTTP/3 `CONNECT` for TCP
- HTTP/3 `CONNECT-UDP`
- HTTP/2 TCP fallback
- bearer auth
- preshared auth via `Proxy-Authorization: Preshared ...`
- `privacy_pass` retry flow driven by a deployer-supplied token provider
- Cloudflare-direct `cloudflare_mtls` with client certificates and optional `sec-ch-geohash`

Primary implementation points:

- `native/rust/crates/ripdpi-masque/src/lib.rs`
- `native/rust/crates/ripdpi-masque/src/auth.rs`
- `core/service/src/main/kotlin/com/poyka/ripdpi/services/UpstreamRelaySupervisor.kt`
- `core/service/src/main/kotlin/com/poyka/ripdpi/services/MasquePrivacyPassProvider.kt`

## Current Hardening State

The Cloudflare-direct path now includes the interoperability fixes that were previously tracked as follow-up work:

- configured MASQUE endpoint paths and queries are preserved for both HTTP/3 and HTTP/2 request construction
- non-HTTPS MASQUE URLs are rejected before native startup
- Cloudflare mTLS auth failures are classified as identity or certificate rejection instead of being misreported as Privacy Pass retry paths
- successful HTTP/3 to HTTP/2 fallback is recorded in runtime telemetry

This moved MASQUE from basic plumbing to rollout-ready transport behavior.

## Android and Editor Behavior

The app and service layer currently:

- resolve deployer-supplied Privacy Pass provider settings
- expose provider readiness to the editor UI
- hide unsupported auth modes when build or provider inputs are unavailable
- reject stale stored profiles before native launch when requirements are not met
- normalize imported client certificate and private key PEM for `cloudflare_mtls`

## Current Validation Model

Coverage exists at several layers:

- native unit tests for request construction, auth header generation, challenge parsing, provider retry flow, and fallback behavior
- service tests for feature gating and preflight rejection paths
- relay-core tests for config bridging and capability validation
- field-validation baseline report in [relay-masque-field-validation.md](relay-masque-field-validation.md)

The remaining work is validation breadth, not transport design.

## Remaining Work

What remains is limited to staged rollout confidence:

- broader provider and endpoint sampling for `cloudflare_mtls`
- continued verification that HTTP/3 to HTTP/2 fallback telemetry is sufficient for rollout decisions
- additional field validation across representative network conditions

The deployer-supplied `privacy_pass` provider flow remains distinct from the Cloudflare-direct mTLS path.
