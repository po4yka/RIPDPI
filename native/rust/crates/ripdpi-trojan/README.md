# ripdpi-trojan

Trojan outbound client library for RIPDPI relay-core integration.

## Protocol Notes

- Source of truth: the Trojan protocol page at `https://trojan-gfw.github.io/trojan/protocol.html`; reimplementations are cross-checks only.
- Reference-only cross-checks used for planning: `cfal/shoes` `src/trojan_handler.rs` and `p4gefau1t/trojan-go` `tunnel/trojan/packet.go`.
- A Trojan client first completes a real TLS handshake; all Trojan protocol bytes are then sent inside the TLS stream.
- The first bytes after TLS are `hex(SHA224(password))`, exactly 56 lowercase ASCII hex characters, followed by `CRLF`, the Trojan request, another `CRLF`, and then optional payload bytes in the same stream.
- The Trojan request is SOCKS5-like: `CMD ATYP DST.ADDR DST.PORT`, where `CMD` is `0x01` for CONNECT or `0x03` for UDP ASSOCIATE, `ATYP` is `0x01` for IPv4, `0x03` for domain name, or `0x04` for IPv6, and `DST.PORT` is two bytes in network byte order.
- Domain addresses use a single length byte followed by the domain bytes; this library must reject domains longer than 255 bytes before writing a frame.
- For UDP ASSOCIATE, each datagram inside the TLS stream is framed as `ATYP DST.ADDR DST.PORT Length CRLF Payload`, where `Length` is two bytes in network byte order.
- The independently verified SHA224 vector used by the request-codec tests is password `123456789` -> `9b3e61bf29f17c75572fae2e86e17809a4513d07c8a18152acf34521`; the protocol spec mandates `hex(SHA224(password))` but does not publish this vector.

## Non-Goals

- Browser-identical TLS fingerprint parity is out of scope. A BoringSSL-backed Chrome-like ClientHello is acceptable; exact Chromium parity is not required.
- Trojan-Go extensions are out of scope, including mux, WebSocket transport, and gRPC transport.

## Current State

- `src/lib.rs` has `hash_password`, `encode_addr`, `build_request`, `build_request_frame`, and `TrojanClient::write_request` for an already-established stream.
- Request tests pin the independently verified `123456789` SHA224 vector and golden CONNECT frames for IPv4, domain, and IPv6 targets.
- UDP ASSOCIATE datagram encoding/decoding is implemented with golden vectors for IPv4, domain, and IPv6 targets plus CRLF, length, truncation, and trailing-byte validation.
- `TrojanClient::connect_tcp` and `TrojanClient::connect_udp_associate` open TCP sockets, build BoringSSL TLS clients from `ripdpi-tls-profiles`, send SNI, use the configured ALPN profile, verify certificates, support an extra PEM root for fixtures or pinned deployments, and send Trojan requests inside TLS.
- `local-network-fixture::TrojanLoopback` provides an offline TLS Trojan fixture that observes SNI/ALPN, validates the password hash and CONNECT/UDP ASSOCIATE framing, pipes TCP payloads to a local echo target, and echoes UDP payloads through stream datagram packets.
- `ripdpi-relay-tls-transports` adapts `ripdpi-trojan` into relay-core with `TrojanSessionFactory` and stream-datagram UDP sessions; `ripdpi-relay-core` has `RelayBackendConfig::Trojan`, `RelayKind::Trojan`, `RelayBackend::Trojan`, a transport registration row, flattened Trojan config fields, and fixture-backed TCP/UDP tests.
- Kotlin keeps the existing `ProxyUriCodec.parseTrojan()` parser, projects confirmed Trojan imports into the default relay profile, persists `trojanPassword` through `RelayCredentialRecord`, emits `RelayKindTrojan`, and includes Trojan fields in `ResolvedRipDpiRelayConfig`.
- `ResolvedRipDpiRelayConfig` / Rust `FlatResolvedRelayRuntimeConfig` schema version is `6`; the version is pinned by `NativeConfigSchemaVersionTest` plus relay-core schema tests.
- Trojan is in the relay-core native descriptor table with TCP and UDP capability true.

## Test Plan

Run `cargo test -p ripdpi-trojan -p ripdpi-relay-core trojan` for focused Trojan coverage. Broader relay changes should also run relay-core descriptor/schema tests and the Kotlin relay import/runtime/schema tests that cover `RelayKindTrojan`, `trojanPassword`, and `ResolvedRipDpiRelayConfig`.
