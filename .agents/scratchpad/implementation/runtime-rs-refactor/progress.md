# Runtime Refactor Progress

## Current Step: 3 (complete)

## Active Wave

| Step | Code Task | Runtime Task Key | Status |
|------|-----------|-----------------|--------|
| 3 | task-03-extract-protocol-dispatch-and-handshake.code-task.md | pdd:runtime-rs-refactor:step-03:handshake | complete |

## Completed Steps

| Step | Description | Lines Moved | Commit |
|------|-------------|-------------|--------|
| 1 | Expand characterization tests | +14 unit +5 e2e | 5da5b73 |
| 2 | Scaffold modules + state/listener extraction | state.rs (80), listeners.rs (138) | 5da5b73 |
| 3 | Extract protocol dispatch and handshake | handshake.rs (556) | pending |

## Step 3 Evidence

### Extracted to handshake.rs (556 lines, 477 code + 79 tests)
- `handle_client` - protocol dispatch entry point (pub(super))
- `handle_transparent`, `handle_socks4`, `handle_socks5`, `handle_http_connect`, `handle_shadowsocks` - protocol handlers
- `handle_socks5_udp_associate` - UDP associate flow (delegates to super::udp_associate_loop)
- `negotiate_socks5`, `read_socks5_request`, `read_socks4_request`, `read_until_nul`, `read_http_connect_request` - request parsers
- `resolve_name` - hostname resolution (pub(super) for UDP path in Step 5)
- `HandshakeKind`, `DelayConnect` - protocol enums
- `maybe_delay_connect`, `send_success_reply`, `read_blocking_first_request` - delayed-connect logic
- `read_shadowsocks_request`, `parse_shadowsocks_target` - Shadowsocks parsing

### Tests moved to handshake::tests (4 tests)
- `send_success_reply_emits_protocol_specific_payloads`
- `read_socks5_request_reads_domain_target`
- `parse_shadowsocks_target_handles_ipv4_and_resolved_domain_targets`
- `parse_shadowsocks_target_respects_ipv6_and_resolve_flags`

### Verification
- runtime.rs: 2877 -> 2361 lines (-516)
- `cargo test -p ripdpi-runtime`: 77 unit + 10 e2e = 87 pass, 0 fail
- `cargo fmt -p ripdpi-runtime --check`: clean
- No new warnings in changed files

## Step 1 Evidence

### Unit tests added (14 new in runtime::tests)
- `udp_packet_round_trip_preserves_ipv6_sender_and_payload` - IPv6 codec round-trip
- `udp_packet_round_trip_empty_payload` - empty payload preservation
- `udp_packet_parse_rejects_malformed_packets` - truncated/fragmented/unknown-atyp
- `tls_record_tracker_inactive_without_partial_timeout` - disabled-by-config
- `tls_record_tracker_inactive_for_non_tls_request` - non-TLS request detection
- `tls_record_tracker_multi_record_observation` - multi-record state machine
- `socks4_success_reply_byte_sequence` - byte-level SOCKS4 reply
- `socks4_failure_reply_byte_sequence` - SOCKS4 reject reply
- `socks5_success_reply_preserves_bind_address` - bind address in SOCKS5 reply
- `socks5_error_reply_carries_error_code` - error code propagation
- `http_connect_success_reply_is_200_ok` - HTTP CONNECT success format
- `http_connect_failure_reply_is_503` - HTTP CONNECT failure format
- `failure_trigger_mask_covers_all_detection_classes` - all FailureClass -> detect mask mapping
- `failure_penalizes_strategy_for_expected_classes` - penalization classification

### E2E tests added (5 new in network_e2e)
- `socks5_delay_connect_replies_before_first_payload_and_round_trips` - delayed-connect flow
- `socks5_udp_multi_flow_same_socket_different_targets` - UDP multi-flow from same socket
- `upstream_silent_drop_fault_is_classified_end_to_end` - fault classification
- `socks5_connect_reply_contains_bound_address` - SOCKS5 reply byte structure
- `http_connect_reply_format_is_200_ok_with_crlf` - HTTP CONNECT reply format
