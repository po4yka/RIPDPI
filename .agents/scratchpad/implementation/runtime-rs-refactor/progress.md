# Runtime Refactor Progress

## Current Step: 8

## Active Wave

| Runtime Task ID | Runtime Task Key | Code Task File | Status |
|----------------|-----------------|----------------|--------|
| task-1773640556-371e | pdd:runtime-rs-refactor:step-08:thin-runtime-and-final-verification | tasks/task-08-thin-runtime-and-final-verification.code-task.md | completed |

## Completed Steps

| Step | Description | Lines Moved | Commit |
|------|-------------|-------------|--------|
| 1 | Expand characterization tests | +14 unit +5 e2e | 5da5b73 |
| 2 | Scaffold modules + state/listener extraction | state.rs (80), listeners.rs (138) | 5da5b73 |
| 3 | Extract protocol dispatch and handshake | handshake.rs (556) | c0400e1 |
| 4 | Extract routing, adaptive, retry glue | routing.rs (502), adaptive.rs (135), retry.rs (166) | (step 4 finalized) |
| 5 | Extract UDP associate flow handling | udp.rs (406) | (pending commit) |
| 6 | Extract desync execution helpers | desync.rs (463) | (pending commit) |
| 7 | Extract relay and first-response handling | relay.rs (634) | (pending commit) |

## Step 4 Evidence

### Extracted modules
- routing.rs (502 lines): `select_route*`, `connect_target*`, route success/failure, cache/autolearn flush
- adaptive.rs (135 lines): adaptive glue helpers
- retry.rs (166 lines): retry pacing glue, `build_retry_selection_penalties`

### Verification
- runtime.rs: 2361 -> 1635 lines (-726)
- runtime/ directory: state.rs (80), listeners.rs (138), handshake.rs (556), routing.rs (502), adaptive.rs (135), retry.rs (166)
- `cargo test -p ripdpi-runtime`: 77 unit + 10 e2e = 87 pass, 0 fail
- Lock ordering retry -> adaptive preserved in retry.rs `build_retry_selection_penalties`
- rustfmt clean, no warnings

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

### Verification
- runtime.rs: 2877 -> 2361 lines (-516)
- `cargo test -p ripdpi-runtime`: 77 unit + 10 e2e = 87 pass, 0 fail
- `cargo fmt -p ripdpi-runtime --check`: clean

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
