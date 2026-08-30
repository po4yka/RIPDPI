# ripdpi-naiveproxy

`ripdpi-naiveproxy` is a subprocess helper managed by `NaiveProxyManager.kt`. It remains a binary crate and must not be moved into `relay-core`, `RelayBackend` / `RelayKind`, `libripdpi-relay.so`, or the `RelayNativeConfig` schema.

**Upstream:** `ripdpi-tls-profiles` for the TLS profile catalog. **Downstream:** Android service code launches the helper as a subprocess through `NaiveProxyManager.kt`; no Rust relay-core crate depends on it.

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
- The subprocess CLI contract is `--listen`, `--server`, `--server-port`, `--server-name`, optional `--credentials-stdin`, and optional `--path`; Android sends paired `naiveUsername` / `naivePassword` as two base64 lines on stdin so credentials never appear in process argv.
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

## Test Coverage

Current focused Rust and Kotlin coverage is:

- Padding codec: `padding_frame_encodes_big_endian_length_and_zero_padding`, `padding_frame_splits_payload_larger_than_u16_max`, `padding_decoder_handles_fragmented_header_payload_and_padding`, `padding_decoder_switches_to_plain_after_eight_frames`, `padding_encoder_switches_to_plain_after_eight_frames`, and `padding_vectors_match_spec_golden`.
- HTTP/2 CONNECT framing and padding negotiation: `h2_connect_request_sends_naive_headers`, `h2_connect_rejects_request_padding_outside_spec_range`, `h2_connect_response_without_padding_disables_payload_padding`, `h2_connect_response_with_padding_reply_enables_variant1`, and `h2_connect_rejects_response_padding_outside_spec_range`.
- End-to-end helper behavior against `local-network-fixture::NaiveH2PaddingFixture`: `socks5_tunnel_round_trip_reaches_target_via_https_proxy`, `socks5_client_round_trip_over_h2_naive_padding_fixture`, `http_front_connect_round_trip_over_h2_naive_padding_fixture`, and `helper_reconnects_after_upstream_h2_stream_failure`.
- CLI/config contract: `config_parses_final_cli_contract`, `config_rejects_partial_auth`, Kotlin `NaiveProxyRuntimePolicyTest.manager command arguments do not expose credentials in argv`, Kotlin `NaiveProxyRuntimePolicyTest.manager writes naive credentials to stdin payload`, and the native `probe_line_*` tests in `main.rs`.
- Android service-side parser and runtime policy: `NaiveProxyProbeParserTest` covers the `RIPDPI-PROBE` JSON parser and schema-range helper; `NaiveProxyManagerPreflightTest` covers mandatory schema-1 preflight, incompatible-helper refusal, launch ordering, repeat starts, and `relay_compatibility` telemetry; `NaiveProxyRuntimePolicyTest` covers restart decisions for clean exits, terminal auth/config failures, DNS backoff, and retryable connect/runtime/helper failures.

`NaiveProxyManager` runs `--probe` before every main helper launch. Only schema `1` is accepted; a helper without probe support is rejected because the APK-bundled binary is freshly extracted on every start and has no legitimate schema-0 compatibility state.

## Verification Gates

Each implementation slice must end green before commit: `cargo fmt --check`, `cargo test -p ripdpi-naiveproxy`, the relevant fixture tests, `cargo clippy -p ripdpi-naiveproxy --all-targets -- -D warnings`, and any touched Kotlin test such as `./gradlew :core:service:testDebugUnitTest`. Final completion also requires `cargo deny check`.
