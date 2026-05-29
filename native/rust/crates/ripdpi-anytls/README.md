# ripdpi-anytls

AnyTLS client/outbound implementation for RIPDPI.

This crate implements the client side only and is wired into relay-core through `ripdpi-relay-tls-transports` as `RelayBackend::AnyTls` / `RelayBackendConfig::AnyTls`. The relay descriptor marks AnyTLS as TCP and UDP capable.

**Upstream:** `ripdpi-tls-profiles` for the BoringSSL/TLS client path. **Downstream:** `ripdpi-relay-tls-transports`, then `ripdpi-relay-core`.

Non-goals are AnyTLS server/inbound mode and non-TLS transport substrates.

## Wired and Verified

AnyTLS is wired end to end and the wire format is pinned against `anytls-go` at the commit recorded in `SPEC_VERSION.md` (`2012ef89768409f45437f1c06a7af5f6eea402ad`). In AnyTLS the padding scheme is the protocol -- a divergent default scheme silently degrades the transport to a detectable Trojan-over-TLS -- so every padding and framing constant is pinned to the upstream source rather than to this implementation's own output:

- **Default padding scheme** (`DEFAULT_PADDING_SCHEME`) is byte-for-byte identical to `anytls-go`'s `proxy/padding` default (`stop=8` plus the eight per-packet size rules). `tests/padding.rs` pins the raw bytes, `stop`, and the negotiation token `padding-md5 = 75cff2ad89aadf5e257059ee571ebe11`. That MD5 is an independent known-answer value (computed outside this crate over the exact scheme string), not the crate's own output fed back into the assertion, so a wrong scheme cannot pass by self-consistency -- a real `anytls-go` server compares this exact token.
- **Frame codec** (`tests/frame.rs`) pins the 7-byte big-endian header (`cmd(1) | stream_id(4) | length(2)`) and all eleven command discriminants (`cmdWaste=0` ... `cmdServerSettings=10`) to the literal protocol values, including `cmdWaste`/`cmdSYN`/`cmdUpdatePaddingScheme` encode/decode and `v=2` client-settings ordering with the `padding-md5` field.
- **First-packet auth** (`tests/tls_session.rs`) pins the `anytls-go` shape: 32-byte `SHA256(password)`, a big-endian `uint16` padding-0 length, then padding-0 bytes drawn from the default scheme's packet-0 rule. Bad passwords are never reported as a successful stream.
- **Session behaviour** is fixture-verified: SYN/SYNACK multiplexing over one TLS session, per-stream open rejection, `cmdUpdatePaddingScheme` persistence across sessions to the same server, heart request/response, `cmdAlert` session close, and sing-box `udp-over-tcp/2` magic-target UDP framing.
- **relay-core** resolves `RelayKind::AnyTls` to `RelayBackend::AnyTls` through `build_anytls`, exercised by the `AnyTlsLoopback` fixture in `relay_runtime_builds_anytls_backend_and_connects_tcp_fixture`, `relay_runtime_builds_anytls_udp_over_tcp_fixture`, and the AnyTLS chain-hop entry/exit tests. `ResolvedRipDpiRelayConfig` schema version is `6`.
- **Android** imports `anytls://` URIs via `ProxyUriCodec.parseAnyTls()` into `ProxyProfile.AnyTls` and resolves them as `RelayKindAnyTls`, covered by the Kotlin import, resolver, descriptor-drift, and native-config schema tests.

Cross-interop against a live `anytls-go` binary remains a nightly oracle (network plus the upstream CLI required) per `SPEC_VERSION.md`; the offline fixture and the upstream-pinned constants above are the per-PR gate.
