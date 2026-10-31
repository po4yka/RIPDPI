# ripdpi-naiveproxy

`ripdpi-naiveproxy` is a subprocess helper managed by `NaiveProxyManager.kt`. It remains a binary crate and must not be moved into `relay-core`, `RelayBackend` / `RelayKind`, `libripdpi-relay.so`, or the `RelayNativeConfig` schema.

**Upstream:** `ripdpi-tls-profiles` for the TLS profile catalog and `ripdpi-subprocess-protect` for socket protection. **Downstream:** Android service code launches the helper as a subprocess through `NaiveProxyManager.kt`; no Rust relay-core crate depends on it.

## Implementation Target

The target is a NaiveProxy-compatible client helper with a local SOCKS5 and HTTP proxy front listener, authenticated HTTP/2 CONNECT upstream tunnels, byte-exact NaiveProxy payload padding, and restart-safe lifecycle behavior through the existing Android subprocess supervisor.

This crate intentionally does not aim for a TLS or HTTP/2 fingerprint identical to Chromium. Upstream NaiveProxy gets that property by reusing Chromium networking; this Rust helper uses rustls with AWS-LC and ECH GREASE, and Chromium-identical fingerprinting is a non-goal.

## Normative References

- klzgrad/naiveproxy README, `Padding protocol, an informal specification`: payload padding, HEADERS padding, opt-in behavior, Fast Open restriction.
- klzgrad/naiveproxy source at tag `v154.0.8037.49-5`, commit `d04efe1ea4477c2db24a7d0e2f068b3bee931e45` (see `SPEC_VERSION.md`): `src/net/tools/naive/naive_padding_framer.{h,cc}`, `naive_padding_socket.cc`, `naive_proxy_delegate.cc`, `http_proxy_server_socket.cc`, and `naive_protocol.{h,cc}`.
- Cross-check only: `cfal/shoes` and `SagerNet/sing-box protocol/naive`. These are not implementation sources for this BSD-3-Clause crate.

## Implementation Status

- Upstream tunnels use HTTP/2 CONNECT over TLS. The helper offers only `h2` ALPN and rejects a server that negotiates another protocol or none before it sends the HTTP/2 preface. It tries each resolved server address in turn. Tunnels use `:method = CONNECT`, `:authority = target`, `Proxy-Authorization`, `padding`, and `padding-type-request: 1, 0` headers.
- Payload padding uses the NaiveProxy Variant1 frame format. As upstream does, an explicit `padding-type-reply` decides (`1` is Variant1, `0` is plain, other values fail the tunnel); without it, a `padding` response header (the legacy Go forwardproxy reply) selects Variant1. Otherwise the tunnel stays plain HTTP/2 proxy interop.
- The local front listener requires per-session credentials: RFC 1929 for SOCKS5 and Basic Proxy-Authorization for HTTP/1.x CONNECT. Rejected clients cannot open an upstream connection. Both front protocols use the same HTTP/2 CONNECT upstream path. The HTTP front serves only `CONNECT` and answers any other method with 405; every in-app client uses the SOCKS5 front.
- The subprocess CLI contract is `--listen`, `--server`, `--server-port`, `--server-name`, required `--credentials-stdin`, and optional `--path`. Stdin has four base64 lines: upstream username, upstream password, local username, and local password. The upstream pair can be empty; the local pair is required. Each local value is 1–255 UTF-8 bytes, and the local username cannot contain a colon. The complete stdin payload is limited to 16 KiB. Credentials never appear in process argv.
- `RIPDPI-PROBE` reports `socks5-listener`, `http-front-listener`, `h2-connect-upstream`, `naive-padding`, `structured-error`, `ready-signal`, and `local-listener-auth` capability tags. Android rejects helpers that do not advertise local authentication.
- Regular E2E coverage is offline and deterministic through `local-network-fixture::NaiveH2PaddingFixture`, including SOCKS5 round trip, HTTP CONNECT front round trip, Basic auth propagation, CONNECT padding ranges, payload padding, and reconnect after an upstream H2 stream failure.
- Tunnels share pooled HTTP/2 connections, as upstream does. A connection takes no new streams after 10 minutes or 5 minutes without a new stream (upstream Android defaults); its open tunnels run on. HTTP/2 keepalive (10 s interval, 5 s timeout) runs while any stream is open and closes a silently dead connection; an open that fails on a closed connection retries once on a pooled connection. No payload is sent before CONNECT succeeds, so nothing replays. A refused CONNECT, a slow target, or an invalid target never retires a connection that has answered. A connection carries at most the server's `SETTINGS_MAX_CONCURRENT_STREAMS` streams (open tunnels plus unanswered CONNECTs; hyper's default of 100 until the server's SETTINGS arrive); further tunnels open another pooled connection instead of queueing behind that limit. The connection receive window is 16 MiB (stream window: hyper's 2 MiB), so a few tunnels whose clients stop reading cannot stall the others. The local front accepts at most 250 tunnels.
- While the VPN protects sockets, the server hostname resolves through protected DoH (the same resolver path as the in-process relays) and never falls back to system DNS. Without protection it uses system DNS.
- The optional `--path` value is sent as an `x-naive-path` CONNECT header. The pinned klzgrad/forwardproxy server ignores it; it is for servers that route on it.
- Known traffic differences from the Chromium client: no cover-site preamble requests, no padded RST_STREAM on stream close, no HTTP/3 or Fast Open, and flow-control windows (16 MiB connection, 2 MiB stream) that differ from Chromium's. Chromium-identical TLS/H2 fingerprinting remains a non-goal; the helper uses rustls with AWS-LC and `h2` ALPN.
- Independent interop evidence: the `independent-naiveproxy-tcp`, `-auth-rejection`, `-tls-rejection`, and `-target-refusal` acceptance scenarios run the helper against the pinned Caddy + forwardproxy server with real local destinations (`test-lab/acceptance/peers/README.md`).

## Padding Protocol Notes

- Payload padding is negotiated per CONNECT tunnel. The client sends a `padding` header on the CONNECT request; the response is decided as described above. Variant1 has wire value `1`, and `0` means no padding.
- Variant1 payload frame format is three bytes of header followed by payload bytes and zero padding bytes: two-byte big-endian `original_data_size`, one-byte `padding_size`, `original_data`, then `padding_size` zero bytes.
- `kFirstPaddings = 8`: only the first eight reads and first eight writes on each bidirectional stream are framed; later bytes pass through unframed.
- `padding_size` per payload frame is uniformly distributed in `[0, 255]`. `original_data_size` is at most `65535`; larger writes must be split into multiple frames.
- CONNECT request HEADERS padding uses a `padding` header whose value length is uniformly distributed in `[16, 32]`. CONNECT response HEADERS padding uses `[30, 62]`.
- The `padding` header value should use non-Huffman-coded, pseudo-random-enough symbols; upstream fills the first up-to-16 characters from a 17-symbol non-indexed HPACK-friendly alphabet and fills the remainder with the last symbol.
- The first CONNECT to a server must not use Fast Open because server padding support is unknown until the first response.
- HTTP Basic auth is carried in `Proxy-Authorization: Basic <base64(username:password)>`.

## Test Coverage

Current focused Rust and Kotlin coverage is:

- Padding codec: `padding_frame_encodes_big_endian_length_and_zero_padding`, `padding_frame_splits_payload_larger_than_u16_max`, `padding_decoder_handles_fragmented_header_payload_and_padding`, `padding_decoder_switches_to_plain_after_eight_frames`, `padding_encoder_switches_to_plain_after_eight_frames`, and `padding_vectors_match_spec_golden`.
- HTTP/2 CONNECT framing and padding negotiation: `h2_connect_request_sends_naive_headers`, `h2_connect_rejects_request_padding_outside_spec_range`, `h2_connect_response_padding_negotiation_follows_upstream_order`, and `upstream_without_h2_alpn_is_rejected_before_the_h2_preface`.
- Session pool: `tunnels_share_one_upstream_connection`, `invalid_target_keeps_the_shared_connection`, `rejected_connect_keeps_the_shared_connection`, `abandoned_slow_connect_keeps_a_verified_connection`, `session::tests::sessions_are_reused_until_idle_or_age_retires_them`, and `session::tests::concurrent_opens_share_one_new_connection_and_discard_replaces_it`, `session::tests::a_full_connection_spills_new_streams_to_another`, `connect_tunnel::tests::server_stream_limit_reaches_the_pool`, and `connect_tunnel::tests::unread_streams_do_not_stall_a_sibling_on_the_shared_connection`.
- Protected resolution: `protected_upstream_hostname_never_uses_system_dns`.
- End-to-end helper behavior against `local-network-fixture::NaiveH2PaddingFixture`: `socks5_tunnel_round_trip_reaches_target_via_https_proxy`, `socks5_client_round_trip_over_h2_naive_padding_fixture`, `http_front_connect_round_trip_over_h2_naive_padding_fixture`, and `helper_reconnects_after_upstream_h2_stream_failure`.
- CLI/config contract: `config_parses_final_cli_contract`, `config_rejects_partial_auth`, Kotlin `NaiveProxyRuntimePolicyTest.manager command arguments do not expose credentials in argv`, Kotlin `NaiveProxyRuntimePolicyTest.manager writes naive credentials to stdin payload`, and the native `probe_line_*` tests in `main.rs`.
- Android service-side parser and runtime policy: `NaiveProxyProbeParserTest` covers the `RIPDPI-PROBE` JSON parser and schema-range helper; `NaiveProxyManagerPreflightTest` covers mandatory schema-1 preflight, incompatible-helper refusal, launch ordering, repeat starts, and `relay_compatibility` telemetry; `NaiveProxyRuntimePolicyTest` covers exit-code restart decisions; `SubprocessRelayReadinessSignalTest` covers that a stream failure does not make a later crash terminal and that `RIPDPI-UPSTREAM-OK` clears it.

`NaiveProxyManager` runs `--probe` before every main helper launch. Only schema `1` is accepted; a helper without probe support is rejected because the APK-bundled binary is freshly extracted on every start and has no legitimate schema-0 compatibility state.

## Verification Gates

Each implementation slice must end green before commit: `cargo fmt --check`, `cargo test -p ripdpi-naiveproxy`, the relevant fixture tests, `cargo clippy -p ripdpi-naiveproxy --all-targets -- -D warnings`, and any touched Kotlin test such as `./gradlew :core:service:testDebugUnitTest`. Final completion also requires `cargo deny check`.
