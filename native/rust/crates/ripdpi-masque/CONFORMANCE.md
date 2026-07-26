# MASQUE Conformance Audit and Test Plan

## Normative References Read

- RFC 9298, Proxying UDP in HTTP: CONNECT-UDP client configuration, Extended CONNECT request and response requirements, Context ID semantics, UDP payload limits, and Proxy-Status guidance.
- RFC 9297, HTTP Datagrams and the Capsule Protocol: HTTP/3 Datagram quarter-stream-ID framing, SETTINGS_H3_DATAGRAM, Capsule TLV framing, DATAGRAM capsules, Capsule-Protocol header semantics, and H3_DATAGRAM_ERROR.
- RFC 9220, Bootstrapping WebSockets with HTTP/3: HTTP/3 Extended CONNECT `:protocol` registration and semantics inherited from RFC 8441.
- RFC 9114, HTTP/3: HTTP/3 request and response pseudo-header validity, CONNECT stream behavior, SETTINGS handling, DATA frame behavior, and HTTP/3 error handling.
- Reference-only cross-check: `github.com/quic-go/masque-go`; do not copy implementation because the RFCs are normative and licensing differs.

## Current Test Classification

- Real behavior tests: `connect_udp_path_percent_encodes_ipv6_hosts` covers RFC 9298 IPv6 percent-encoding for the default path shape; `new_client_starts_with_not_attempted_quic_snapshot` covers local telemetry initialization; `masque_config_accepts_ech_and_boring_h2_backend_can_apply_it` covers local ECH/TLS builder compatibility; `parse_proxy_origin_derives_connect_udp_base_path` covers endpoint parsing and CONNECT-UDP base-path derivation; `decode_udp_payload_requires_context_zero` covers one negative Context ID case but only for a one-byte context; `parse_target_supports_domain_and_ipv6_authorities` covers target authority parsing; `apply_request_headers_does_not_add_proprietary_geohash` covers legacy vendor field compatibility without emitting proprietary routing headers; `cloudflare_mtls_auth_rejection_does_not_require_privacy_pass_challenge` and `privacy_pass_challenge_requires_private_token_header` cover local response classification.
- Provider-stub fixture tests: `privacy_pass_provider_fetch_caches_spare_headers` and `privacy_pass_provider_non_success_is_permission_denied` exercise `start_provider_stub`, which is an HTTP/1.1 JSON fixture for deployer-supplied Privacy Pass tokens, not an RFC 9298 CONNECT-UDP proxy and not an interop oracle.
- Auth parser tests: the eight tests in `auth.rs` cover static bearer/preshared header generation, Cloudflare mTLS header omission, and PrivateToken challenge parsing; they do not prove CONNECT-UDP interoperability.
- Migration vocabulary tests in `migration.rs` cover local telemetry string stability only.
- Provider adapter tests: `provider_adapter.rs` covers generic/self-hosted provider IDs, static bearer and preshared auth header construction, Privacy Pass retry eligibility, TLS client certificate requirements, and ignoring the legacy Cloudflare geohash field. These tests still do not exercise relay traffic against a CONNECT-UDP proxy.
- H3 TCP request-shape fixture: `MasqueH3ClassicConnectFixture` is a UDP/QUIC-only, ALPN-`h3` oracle that recognizes only RFC 9114 classic CONNECT for its fixture-owned target. It rejects scheme, path, Extended CONNECT protocol, and Capsule-Protocol fields and exposes no TCP/H2 listener, so fallback cannot hide a malformed H3 request. Conformant requests receive `501 Not Implemented`; positive H3 TCP tunneling remains intentionally absent while the production mode is unsupported.
- H3 CONNECT-UDP fixture: `MasqueH3ConnectUdpFixture` is a real Quinn/H3 DATAGRAM server that validates the Extended CONNECT request, request-stream association, URI expansion, and Capsule-Protocol header, then echoes Context ID zero payloads. Production-client tests pin its self-signed root, cover the exact DATAGRAM size boundary, and preserve the flow after typed `TooLarge`. Remaining gaps are bearer/mTLS generic-provider E2E and cross-implementation provider testing.

## Implemented Transport Surface

The crate currently ships HTTP/3 CONNECT-UDP and HTTP/2 classic CONNECT for TCP, plus bearer auth, preshared auth through `Proxy-Authorization: Preshared ...`, deployer-supplied Privacy Pass retry, generic/self-hosted provider adapter metadata, TLS client-certificate auth for the `cloudflare_mtls` compatibility mode, and a legacy Cloudflare geohash config field that the generic adapter ignores rather than emitting proprietary `sec-ch-geohash` headers. Owned MASQUE outbounds can carry ECH config: HTTP/3 CONNECT-UDP applies rustls ECH and HTTP/2 TCP applies the same ECHConfigList through the BoringSSL fingerprinted TLS path. HTTP/3 TCP is explicitly rejected before any network dial because the pinned H3 encoder always emits scheme and path fields and therefore cannot encode RFC 9114 classic CONNECT.

The current hardening surface is pinned by tests for CONNECT-UDP base-path derivation, non-HTTPS URL rejection before native startup, Cloudflare mTLS auth classification, Privacy Pass challenge parsing, explicit H2 classic-CONNECT request shape, H3 TCP rejection before QUIC dialing, strict H3 fixture rejection of the pinned encoder's malformed classic-CONNECT shape, real H3 CONNECT-UDP round-trip with pinned certificate verification, DATAGRAM boundary handling, Quinn PMTUD black-hole recovery on IPv4/IPv6, relay-core failure telemetry propagation, and ECH retry/error surfacing. Android service/editor behavior around provider readiness, explicit H2 selection, typed H3 TCP rejection, and stale profile rejection is owned by `core/service` tests.

## QUIC Migration Telemetry Vocabulary

`record_quic_migration_status(status, reason)` writes the snapshot returned by `quic_migration_snapshot()`. These strings are telemetry export fields and should not change without updating consumers and tests. `src/migration.rs` defines typed helpers for the stable vocabulary while preserving the older string-taking API.

| `status` string | Meaning |
| --- | --- |
| `not_attempted` | Initial state; no migration or fallback yet. |
| `http2_selected` | HTTP/2 classic CONNECT was selected explicitly for TCP. |
| `http2_fallback` | Client fell back to HTTP/2 after the H3 attempt failed or timed out. |
| `failed` | Migration or fallback ultimately failed; cooldown engaged. |
| `reverted` | Migration attempted, then rolled back; cooldown engaged. |
| `path_validated_*` | Post-handshake path validation succeeded for a specific event. |

| `reason` prefix when `status == "http2_fallback"` | Trigger |
| --- | --- |
| `http3_connect_failed_<inner>` | H3 CONNECT attempt rejected with an inner-error tag from `classify_attempt_failure`. |
| `http3_connect_timed_out` | H3 CONNECT attempt exceeded the per-attempt timeout. |
| `http3_connect_failed` | Generic H3 CONNECT failure when no inner classification is available. |

| `reason` when `status == "failed"` | Trigger |
| --- | --- |
| `masque_h3_tcp_unsupported` | TCP requested HTTP/3, which is rejected before dialing until a conformant RFC 9114 classic-CONNECT encoder is available. |
| `http2_connect_failed` | Explicitly selected HTTP/2 classic CONNECT failed. |
| `<udp-specific tags>` | UDP-session migration failures emitted from `udp.rs`; see callsites. |

## Audit Gaps

- `provider_adapter.rs` now provides a generic/self-hosted adapter for user-configured RFC 9298 endpoints with bearer, preshared, Privacy Pass, or TLS client certificate auth modes. Relay traffic coverage still comes from the client tests, not a conformant CONNECT-UDP proxy fixture.
- `auth.rs` uses the provider adapter for static auth header construction, while Privacy Pass provider fetch/caching and response challenge classification still live in the client/auth/response modules.
- `capsule.rs` implements QUIC-varint capsule framing, HTTP Datagram quarter-stream IDs, CONNECT-UDP Context IDs, and the 65527-byte UDP payload limit with boundary and truncation tests. Cross-implementation validation of those local codecs remains outstanding.
- `connect_h3_transport` enables datagrams only for UDP flows, but RFC 9297 Section 3 notes the privacy value of always sending SETTINGS_H3_DATAGRAM when supported. The hardening plan should verify the underlying h3 builder sends SETTINGS_H3_DATAGRAM value 1 and that incoming values other than 0 or 1 fail as H3_SETTINGS_ERROR.
- `attempt_h3_connect_udp` sends the required Extended CONNECT pseudo-headers and `capsule-protocol: ?1` and rejects successful responses that do not confirm `Capsule-Protocol: ?1`. Cross-provider response-shape coverage remains outstanding.
- `h2.rs` implements TCP classic CONNECT and CONNECT-UDP fallback using Extended CONNECT plus DATAGRAM capsules over DATA frames. The latter has a local round-trip fixture but still needs cross-implementation provider testing.
- `response.rs` preserves a bounded raw `Proxy-Status` value in rejection details; it does not yet parse the structured status parameters into typed failure categories.
- `url.rs` supports the default `/.well-known/masque/udp/{target_host}/{target_port}/` derivation and path preservation, but not general RFC 9298 URI templates with query variables such as `?h={target_host}&p={target_port}`.
- The strict local-network-fixture H3 classic-CONNECT server is a request-shape oracle for the intentionally unsupported TCP mode, not a production proxy or an RFC 9298 CONNECT-UDP proxy. Existing `start_provider_stub` tests prove only the deployer token-provider callback path.

## TDD Plan

- Slices 1–2, Capsule TLV and HTTP Datagram layers: landed in `capsule.rs`, including QUIC-varint vectors, DATAGRAM capsules, quarter-stream-ID boundaries, Context ID zero, truncation errors, and the 65527-byte UDP limit.
- Slice 3, CONNECT-UDP request/response and h2 fallback: landed. Local tests pin the H3 pseudo-header/request shape, 2xx plus `Capsule-Protocol: ?1` response validation, and h2 DATAGRAM-capsule round trips. Cross-implementation/provider validation remains open.
- Slice 4, generic provider adapter: mostly landed. Remaining work is to add integration coverage that proves the adapter-selected auth mode is applied across request construction, Privacy Pass retry handling, TLS client certificate setup, and relay traffic against a CONNECT-UDP proxy. RFC coverage: RFC 9298 Sections 2 and 7 plus RFC 6750 for bearer auth; TLS client certificate behavior remains standard TLS configuration, not a MASQUE extension.
- Slice 5, conformant RFC 9298 proxy fixture and full E2E: extend the offline fixture surface with a CONNECT-UDP proxy that accepts Extended CONNECT, validates the URI template expansion, returns 2xx plus Capsule-Protocol, opens a connected UDP socket to the target, relays HTTP Datagrams and DATAGRAM capsules, emits Proxy-Status on controlled failures, and backs a full `MasqueUdpRelay` round-trip test. RFC coverage: RFC 9298 Sections 3.1, 3.4, 3.5, and 5; RFC 9297 Sections 2.1 and 3.5.
- Nightly cross-interop: add a non-required CI/nightly job that exercises the generic client against `quic-go/masque-go` as a reference implementation, with the RFCs winning all conflicts. This is not a substitute for the local conformant fixture.

## Non-Goals

- Commercial-relay provider adapters such as iCloud Private Relay and Cloudflare proprietary flows are out of scope because their auth and enrollment are not specified by RFC 9298.
- CONNECT-IP from RFC 9484 is out of scope.
- MASQUE server/proxy role in production code is out of scope; the only server-like code planned here is a test fixture for client conformance.
