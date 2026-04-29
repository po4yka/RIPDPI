# MASQUE Runtime

This note describes the current MASQUE implementation in RIPDPI and the
validation surface that gates rollout confidence.

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

The Cloudflare-direct path includes the interoperability fixes required for
normal operation:

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

The repo-owned validation matrix is:

| Surface | Evidence |
| --- | --- |
| request path and query preserved for MASQUE origin parsing | `ripdpi-masque::tests::parse_proxy_origin_preserves_request_path_and_query` |
| UDP path generation percent-encodes IPv6 authorities | `ripdpi-masque::tests::connect_udp_path_percent_encodes_ipv6_hosts` |
| Cloudflare-direct geohash header is emitted without static auth headers | `ripdpi-masque::tests::apply_request_headers_adds_geohash_without_auth` |
| Cloudflare mTLS rejection stays classified as client-identity failure | `ripdpi-masque::tests::cloudflare_mtls_auth_rejection_does_not_require_privacy_pass_challenge` |
| Privacy Pass retry path rejects malformed challenges | `ripdpi-masque::tests::privacy_pass_challenge_requires_private_token_header` |
| Privacy Pass provider results are cached and replayed correctly | `ripdpi-masque::tests::privacy_pass_provider_fetch_caches_spare_headers` |
| Privacy Pass provider non-success is surfaced as permission failure | `ripdpi-masque::tests::privacy_pass_provider_non_success_is_permission_denied` |
| QUIC migration snapshot starts conservatively | `ripdpi-masque::tests::new_client_starts_with_not_attempted_quic_snapshot` |
| H3-to-H2 fallback telemetry reason is retained | `ripdpi-masque::tests::quic_migration_snapshot_records_http2_fallback_reason` |

The remaining work is validation breadth, not transport design.

## Remaining Work

What remains is limited to staged rollout confidence:

- broader provider and endpoint sampling for `cloudflare_mtls`
- continued verification that HTTP/3 to HTTP/2 fallback telemetry is sufficient for rollout decisions
- additional field validation across representative network conditions

The deployer-supplied `privacy_pass` provider flow remains distinct from the Cloudflare-direct mTLS path.
