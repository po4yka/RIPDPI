# MASQUE Field Validation Report

This note records the repo-owned validation baseline for the MASQUE rollout
track. It complements the implementation/status note in
[relay-masque-status.md](relay-masque-status.md).

## Snapshot

- Review date: `2026-04-18`
- Scope owner: Track S integration validation
- Transport scope: HTTP/3 `CONNECT`, HTTP/3 `CONNECT-UDP`, HTTP/2 TCP fallback
- Auth scope: `bearer`, `preshared`, `privacy_pass`, `cloudflare_mtls`
- Telemetry scope: QUIC migration snapshot / H3-to-H2 fallback reason capture

## Verified Repo-Owned Matrix

| Surface | Evidence |
| --- | --- |
| request path and query preserved for MASQUE origin parsing | `ripdpi-masque::tests::parse_proxy_origin_preserves_request_path_and_query` |
| UDP path generation percent-encodes IPv6 authorities | `ripdpi-masque::tests::connect_udp_path_percent_encodes_ipv6_hosts` |
| Cloudflare-direct geohash header is emitted without static auth headers | `ripdpi-masque::tests::apply_request_headers_adds_geohash_without_auth` |
| Cloudflare mTLS rejection stays classified as client-identity failure, not Privacy Pass retry | `ripdpi-masque::tests::cloudflare_mtls_auth_rejection_does_not_require_privacy_pass_challenge` |
| Privacy Pass retry path rejects malformed challenges | `ripdpi-masque::tests::privacy_pass_challenge_requires_private_token_header` |
| Privacy Pass provider results are cached and replayed correctly | `ripdpi-masque::tests::privacy_pass_provider_fetch_caches_spare_headers` |
| Privacy Pass provider non-success is surfaced as permission failure | `ripdpi-masque::tests::privacy_pass_provider_non_success_is_permission_denied` |
| QUIC migration snapshot starts in a conservative `not_attempted` state | `ripdpi-masque::tests::new_client_starts_with_not_attempted_quic_snapshot` |
| H3-to-H2 fallback telemetry reason is retained in the snapshot model | `ripdpi-masque::tests::quic_migration_snapshot_records_http2_fallback_reason` |

## Rollout Interpretation

The repo now has validation for the parts Track S can own locally:

- auth-mode correctness and classification boundaries
- Cloudflare-direct header behavior
- path/query preservation for H3 and H2 request construction
- fallback telemetry shape for staged rollout decisions

What still depends on operator or lab execution is endpoint diversity, not client
construction. Those runs should record:

- MASQUE endpoint hostname
- auth mode
- whether H3 succeeded directly or H2 fallback was used
- fallback reason from the QUIC migration snapshot
- whether Cloudflare-direct `sec-ch-geohash` was enabled
- whether the target network needed transport-specific overrides

## Exit Condition

Track S treats the MASQUE rollout slice as complete once field runs use this
report shape instead of ad hoc notes and the in-repo tests continue to pin the
telemetry/auth behavior above.
