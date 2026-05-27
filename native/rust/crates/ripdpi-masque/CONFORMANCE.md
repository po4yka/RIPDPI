# MASQUE Conformance Audit and Test Plan

## Normative References Read

- RFC 9298, Proxying UDP in HTTP: CONNECT-UDP client configuration, Extended CONNECT request and response requirements, Context ID semantics, UDP payload limits, and Proxy-Status guidance.
- RFC 9297, HTTP Datagrams and the Capsule Protocol: HTTP/3 Datagram quarter-stream-ID framing, SETTINGS_H3_DATAGRAM, Capsule TLV framing, DATAGRAM capsules, Capsule-Protocol header semantics, and H3_DATAGRAM_ERROR.
- RFC 9220, Bootstrapping WebSockets with HTTP/3: HTTP/3 Extended CONNECT `:protocol` registration and semantics inherited from RFC 8441.
- RFC 9114, HTTP/3: HTTP/3 request and response pseudo-header validity, CONNECT stream behavior, SETTINGS handling, DATA frame behavior, and HTTP/3 error handling.
- Reference-only cross-check: `github.com/quic-go/masque-go`; do not copy implementation because the RFCs are normative and licensing differs.

## Current Test Classification

- Real behavior tests: `connect_udp_path_percent_encodes_ipv6_hosts` covers RFC 9298 IPv6 percent-encoding for the default path shape; `new_client_starts_with_not_attempted_quic_snapshot` covers local telemetry initialization; `masque_config_accepts_ech_and_boring_h2_backend_can_apply_it` covers local ECH/TLS builder compatibility; `parse_proxy_origin_preserves_request_path_and_query` covers endpoint path/query preservation; `decode_udp_payload_requires_context_zero` covers one negative Context ID case but only for a one-byte context; `parse_target_supports_domain_and_ipv6_authorities` covers target authority parsing; `apply_request_headers_adds_geohash_without_auth` covers vendor header behavior; `cloudflare_mtls_auth_rejection_does_not_require_privacy_pass_challenge` and `privacy_pass_challenge_requires_private_token_header` cover local response classification.
- Provider-stub fixture tests: `privacy_pass_provider_fetch_caches_spare_headers` and `privacy_pass_provider_non_success_is_permission_denied` exercise `start_provider_stub`, which is an HTTP/1.1 JSON fixture for deployer-supplied Privacy Pass tokens, not an RFC 9298 CONNECT-UDP proxy and not an interop oracle.
- Auth parser tests: the eight tests in `auth.rs` cover static bearer/preshared header generation, Cloudflare mTLS header omission, and PrivateToken challenge parsing; they do not prove CONNECT-UDP interoperability.
- Migration vocabulary tests: the five tests in `migration.rs` cover local telemetry string stability only.
- Provider adapter tests: the four tests in `provider_adapter.rs` cover metadata on trait stubs and explicitly do not exercise request construction, auth application, TLS client certificate behavior, retry policy, or relay traffic.
- Missing conformance coverage: there are no byte-level golden vectors for QUIC varints, Capsule TLV encoding/decoding, HTTP/3 Datagram quarter-stream-ID overflow, DATAGRAM capsule fallback, response Capsule-Protocol validation, Proxy-Status/error mapping, h2 CONNECT-UDP fallback, bearer/mTLS generic provider behavior against a CONNECT-UDP proxy, or full client tunnel E2E against a conformant RFC 9298 proxy.

## Audit Gaps

- `provider_adapter.rs` is only trait plus metadata stubs. It still advertises Cloudflare-direct and Privacy Pass as in-tree adapters instead of a production generic/self-hosted adapter for a user-configured RFC 9298 endpoint with bearer or TLS client certificate auth.
- `auth.rs` still branches directly on `MasqueAuthMode`, so adapter decisions are not the authority for auth header construction, TLS client certificate requirements, challenge classification, or retry policy.
- `h3/datagram.rs` only treats the Context ID as one byte and returns `Vec<u8>`. RFC 9298 Section 4 and Section 5 require QUIC varint Context IDs, Context ID zero for UDP payloads, unknown Context ID drop/buffer behavior, and the 65527-byte UDP payload limit.
- `udp.rs` emits a single zero byte for Context ID zero and does not enforce the RFC 9298 Section 5 65527-byte maximum before sending.
- The HTTP/3 Datagram quarter-stream-ID layer is delegated to `h3-datagram`, but the crate has no local boundary tests proving RFC 9297 Section 2.1 behavior for request stream association, oversize quarter-stream-ID rejection, or short varint rejection.
- `connect_h3_transport` enables datagrams only for UDP flows, but RFC 9297 Section 3 notes the privacy value of always sending SETTINGS_H3_DATAGRAM when supported. The hardening plan should verify the underlying h3 builder sends SETTINGS_H3_DATAGRAM value 1 and that incoming values other than 0 or 1 fail as H3_SETTINGS_ERROR.
- `attempt_h3_connect_udp` sends the required Extended CONNECT pseudo-headers and `capsule-protocol: ?1`, but it does not validate that successful responses also include a true Capsule-Protocol header and do not carry prohibited Capsule Protocol content headers.
- `h2.rs` only implements TCP CONNECT fallback. There is no h2 CONNECT-UDP fallback using Extended CONNECT plus Capsule DATAGRAM frames, so HTTP Datagram fallback over reliable DATA frames is missing.
- `response.rs` classifies non-2xx status only by auth mode. It does not parse Proxy-Status for RFC 9298 Section 3.1 failure details such as DNS errors or prohibited destinations.
- `url.rs` supports the default `/.well-known/masque/udp/{target_host}/{target_port}/` derivation and path preservation, but not general RFC 9298 URI templates with query variables such as `?h={target_host}&p={target_port}`.
- The crate has no conformant local-network-fixture RFC 9298 proxy. Existing `start_provider_stub` tests prove only the deployer token-provider callback path.

## TDD Plan

- Slice 1, Capsule TLV framing: add failing golden-vector tests for QUIC varint encode/decode, DATAGRAM capsule type 0x00 encode/decode, unknown capsule skipping, truncated capsule error, redundant length self-consistency, and zero-length value handling. RFC coverage: RFC 9297 Sections 1.1, 3.2, 3.3, and 3.5.
- Slice 2, HTTP Datagram layer: add failing tests for HTTP/3 Datagram quarter-stream-ID encode/decode, maximum legal value `2^60 - 1`, oversize value mapping to H3_DATAGRAM_ERROR 0x33, short varint mapping to H3_DATAGRAM_ERROR 0x33, Context ID varint zero payload round-trip, unknown Context ID drop behavior, and 65527-byte UDP payload limit. RFC coverage: RFC 9297 Sections 2.1, 2.1.1, and 5.2; RFC 9298 Sections 4 and 5.
- Slice 3, CONNECT-UDP request/response and h2 fallback: add failing tests that the H3 request has `:method = CONNECT`, `:protocol = connect-udp`, non-empty `:scheme` and expanded `:path`, proxy `:authority`, and `capsule-protocol: ?1`; add failing tests that successful responses must be 2xx and include a true Capsule-Protocol header; add h2 fallback tests that encode/decode UDP payloads as DATAGRAM capsules on DATA frames. RFC coverage: RFC 9298 Sections 2, 3, 3.4, and 3.5; RFC 9297 Sections 3.2 through 3.5; RFC 9220 Section 3; RFC 9114 Sections 4.1.2, 4.3, and 4.4.
- Slice 4, generic provider adapter: replace the trait-only stubs with a generic/self-hosted adapter that owns endpoint policy, bearer auth header construction, TLS client certificate requirements, and auth retry classification; add tests that commercial providers are not special-cased and that Cloudflare/iCloud proprietary auth is not implemented. RFC coverage: RFC 9298 Sections 2 and 7 plus RFC 6750 for bearer auth; TLS client certificate behavior remains standard TLS configuration, not a MASQUE extension.
- Slice 5, conformant RFC 9298 proxy fixture and full E2E: add an offline local-network-fixture proxy that accepts Extended CONNECT CONNECT-UDP, validates the URI template expansion, returns 2xx plus Capsule-Protocol, opens a connected UDP socket to the target, relays HTTP Datagrams and DATAGRAM capsules, emits Proxy-Status on controlled failures, and backs a full `MasqueUdpRelay` round-trip test. RFC coverage: RFC 9298 Sections 3.1, 3.4, 3.5, and 5; RFC 9297 Sections 2.1 and 3.5.
- Nightly cross-interop: add a non-required CI/nightly job that exercises the generic client against `quic-go/masque-go` as a reference implementation, with the RFCs winning all conflicts. This is not a substitute for the local conformant fixture.

## Non-Goals

- Commercial-relay provider adapters such as iCloud Private Relay and Cloudflare proprietary flows are out of scope because their auth and enrollment are not specified by RFC 9298.
- CONNECT-IP from RFC 9484 is out of scope.
- MASQUE server/proxy role in production code is out of scope; the only server-like code planned here is a test fixture for client conformance.
