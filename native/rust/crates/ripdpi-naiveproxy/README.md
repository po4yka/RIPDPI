# `ripdpi-naiveproxy`

`ripdpi-naiveproxy` is a subprocess helper managed by `NaiveProxyManager.kt`. It remains a binary crate and must not be moved into `relay-core`, `RelayBackend` / `RelayKind`, `libripdpi-relay.so`, or the `RelayNativeConfig` schema.

## Implementation Target

The target is a NaiveProxy-compatible client helper with a local SOCKS5 and HTTP proxy front listener, authenticated HTTP/2 CONNECT upstream tunnels, byte-exact NaiveProxy payload padding, and restart-safe lifecycle behavior through the existing Android subprocess supervisor.

This crate intentionally does not aim for a TLS or HTTP/2 fingerprint identical to Chromium. Upstream NaiveProxy gets that property by reusing Chromium networking; this Rust helper may approximate the TLS ClientHello with the workspace BoringSSL stack, but Chromium-identical fingerprinting is a non-goal.

## Normative References

- klzgrad/naiveproxy README, `Padding protocol, an informal specification`: payload padding, HEADERS padding, opt-in behavior, Fast Open restriction.
- klzgrad/naiveproxy source at upstream commit `d9d09c9cc55ed40f5ee725d046884dcb84f57589`: `src/net/tools/naive/naive_padding_framer.{h,cc}`, `naive_padding_socket.cc`, `naive_proxy_delegate.cc`, `http_proxy_server_socket.cc`, and `naive_protocol.{h,cc}`.
- Cross-check only: `cfal/shoes` and `SagerNet/sing-box protocol/naive`. These are not implementation sources for this BSD-3-Clause crate.

## Implementation Status

- Upstream tunnels use HTTP/2 CONNECT over TLS with `h2` ALPN, `:method = CONNECT`, `:authority = target`, `Proxy-Authorization`, `padding`, and `padding-type-request: 1` headers.
- Payload padding uses the NaiveProxy Variant1 frame format and is enabled only when the client sent request padding and the upstream response includes `padding` plus `padding-type-reply: 1`; otherwise the tunnel remains plain HTTP/2 proxy interop.
- The local front listener accepts SOCKS5 and HTTP/1.x CONNECT on the same socket by sniffing the first byte, then relays both front protocols through the same HTTP/2 CONNECT upstream path.
- The subprocess CLI contract is `--listen`, `--server`, `--server-port`, `--server-name`, optional paired `--username` / `--password`, and optional `--path`; Android `NaiveProxyManager.kt` has a unit test locking the emitted argument vector for `naiveUsername`, `naivePassword`, and `naivePath`.
- `RIPDPI-PROBE` reports `socks5-listener`, `http-front-listener`, `h2-connect-upstream`, `naive-padding`, `structured-error`, and `ready-signal` capability tags.
- Regular E2E coverage is offline and deterministic through `local-network-fixture::NaiveH2PaddingFixture`, including SOCKS5 round trip, HTTP CONNECT front round trip, Basic auth propagation, CONNECT padding ranges, payload padding, and reconnect after an upstream H2 stream failure.
- Chromium-identical TLS/H2 fingerprinting remains a non-goal. The current implementation uses the workspace rustls/AWS-LC TLS stack with `h2` ALPN; future BoringSSL ClientHello approximation can be added without changing the subprocess architecture.

## Padding Protocol Notes

- Payload padding is negotiated per CONNECT tunnel. The client sends a `padding` header on the CONNECT request; payload padding is active only if the server response also includes `padding`. Upstream source also negotiates `padding-type-request` / `padding-type-reply`; Variant1 has wire value `1`, and `0` means no padding.
- Variant1 payload frame format is three bytes of header followed by payload bytes and zero padding bytes: two-byte big-endian `original_data_size`, one-byte `padding_size`, `original_data`, then `padding_size` zero bytes.
- `kFirstPaddings = 8`: only the first eight reads and first eight writes on each bidirectional stream are framed; later bytes pass through unframed.
- `padding_size` per payload frame is uniformly distributed in `[0, 255]`. `original_data_size` is at most `65535`; larger writes must be split into multiple frames.
- CONNECT request HEADERS padding uses a `padding` header whose value length is uniformly distributed in `[16, 32]`. CONNECT response HEADERS padding uses `[30, 62]`.
- The `padding` header value should use non-Huffman-coded, pseudo-random-enough symbols; upstream fills the first up-to-16 characters from a 17-symbol non-indexed HPACK-friendly alphabet and fills the remainder with the last symbol.
- The first CONNECT to a server must not use Fast Open because server padding support is unknown until the first response.
- HTTP Basic auth is carried in `Proxy-Authorization: Basic <base64(username:password)>`.

## TDD Test Plan

Slice 1, padding codec encode/decode plus golden byte vectors:

- `padding_frame_encodes_big_endian_length_and_zero_padding`: cite Variant1 frame format and `padding_size` zero bytes. Use a deterministic padding size and compare bytes against a hand-derived vector, not a blessed implementation output.
- `padding_frame_splits_payload_larger_than_65535`: cite `original_data_size <= 65535`; input length `65536` must produce a `65535` frame followed by a second frame.
- `padding_decoder_handles_fragmented_header_payload_and_padding`: cite framer read state from upstream C++ source; feed one byte at a time and assert emitted payload only after enough bytes arrive.
- `padding_decoder_switches_to_plain_after_eight_frames`: cite `kFirstPaddings = 8`; after eight framed reads, the next bytes are returned as plain payload.
- `padding_encoder_switches_to_plain_after_eight_frames`: cite `kFirstPaddings = 8`; after eight framed writes, encoded output must be unframed.
- `padding_vectors_match_spec_golden`: use `golden-test-support` with vectors whose expected bytes are manually derived from the spec constants and fixed padding sizes; blessing is allowed only after the manual vector file is reviewed.

Slice 2, HTTP/2 CONNECT framing:

- `h2_connect_request_sends_naive_headers`: cite CONNECT request HEADERS padding `[16, 32]`, `Proxy-Authorization`, `padding`, and `padding-type-request = 1`.
- `h2_connect_response_without_padding_disables_payload_padding`: cite opt-in rule requiring `padding` on both request and response; response status 200 without `padding` must tunnel plain bytes.
- `h2_connect_response_with_padding_reply_enables_variant1`: cite response `padding` and `padding-type-reply = 1`; payload streams must wrap first eight reads/writes with Variant1.
- `first_connect_does_not_fastopen_payload`: cite Fast Open ban; fixture must observe no DATA payload before the 200 response headers.
- `h2_connect_auth_failure_maps_to_auth_error`: cite HTTP Basic auth; 407 response must produce the existing auth failure class.

Slice 3, full client tunnel vs fixture:

- Add `local-network-fixture` HTTP/2 CONNECT+padding server with deterministic response padding and a target echo service.
- `socks5_client_round_trip_over_h2_naive_padding`: cite SOCKS5 front listener and Variant1 payload padding; assert echo bytes and server-observed frame counts.
- `http_front_connect_round_trip_over_h2_naive_padding`: cite HTTP front listener scope; assert local HTTP CONNECT succeeds and tunnels echo bytes.
- `fixture_rejects_missing_padding_header_for_naive_mode`: cite padding opt-in and server-side missing-padding behavior; assert client reports a structured CONNECT/protocol failure rather than silently downgrading when the fixture requires Naive padding.
- `plain_h2_proxy_interop_without_response_padding`: cite interoperability with regular HTTP/2 proxies; assert regular H2 proxy response without `padding` still works with plain payload.

Slice 4, CLI/config:

- `config_parses_final_cli_contract`: cite fixed Kotlin/native subprocess contract; parse `--listen`, `--server`, `--server-port`, `--server-name`, optional `--username`, `--password`, and `--path`.
- `config_rejects_partial_auth`: cite HTTP Basic requires username and password together.
- `NaiveProxyManagerTest.builds_helper_arguments_for_final_contract`: Kotlin test must change in the same commit as any native CLI contract change involving `naiveUsername`, `naivePassword`, or `naivePath`.
- `probe_reports_h2_and_naive_padding_capabilities`: keep `RIPDPI-PROBE` aligned with implemented capability tags once the helper actually supports HTTP/2 and padding.

Slice 5, lifecycle/reconnect:

- `runtime_restarts_transient_dns_connect_runtime_failures`: cite existing `NaiveProxyRuntime` restart policy; DNS/connect/runtime failures remain restartable within budget.
- `runtime_does_not_restart_auth_or_http_connect_rejections`: cite current terminal failure classes and auth/CONNECT semantics.
- `helper_reconnects_after_upstream_h2_stream_failure`: fixture closes one HTTP/2 stream; next local client connection must establish a fresh tunnel without wedging the listener.
- `helper_shutdown_closes_front_listener_and_upstream_tasks`: subprocess stop must leave no accepted-client task running and no hung `waitForExit`.

## Verification Gates

Each implementation slice must end green before commit: `cargo fmt --check`, `cargo test -p ripdpi-naiveproxy`, the relevant fixture tests, `cargo clippy -p ripdpi-naiveproxy --all-targets -- -D warnings`, and any touched Kotlin test such as `./gradlew :core:service:testDebugUnitTest`. Final completion also requires `cargo deny check`.
