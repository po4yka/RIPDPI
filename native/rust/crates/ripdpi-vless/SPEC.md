# SPEC — `ripdpi-vless`

## Scope

Client implementation of the VLESS application protocol, the REALITY TLS handshake, the XTLS-Vision flow addon, and XUDP datagrams over a VLESS Mux carrier. TCP is used standalone (TCP → REALITY → VLESS) and chained (over an existing transport via `connect_over`); each SOCKS5 UDP association owns one dedicated Reality/TCP XUDP carrier.

## Upstream

- xray-core (https://github.com/XTLS/Xray-core)
- Pin recorded in `SPEC_VERSION.md`

## VLESS request wire format

| Field | Size | Notes |
|---|---|---|
| Version | 1 byte | `0x00` |
| UUID | 16 bytes | Subscriber ID |
| AddonsLen | 1 byte | Length of `Addons` blob |
| Addons | var | Vision flow string, etc. |
| Command | 1 byte | `0x01` TCP, `0x02` direct UDP, `0x03` Mux |
| Port | 2 bytes | Big-endian; omitted for Mux |
| AddrType | 1 byte | `0x01` IPv4, `0x02` domain, `0x03` IPv6; omitted for Mux |
| Address | var | IPv4: 4 bytes; IPv6: 16 bytes; domain: 1-byte len + UTF-8; omitted for Mux |

See `wire.rs` for the encoder; the response header is one version byte + one addons-length byte + variable addons.

## REALITY TLS handshake

1. Build a BoringSSL client connection with a vendored ClientHello callback hook.
2. The callback reads the TLS 1.3 X25519 key_share private key via `SSL_handshake_get_x25519_private_key`.
3. It derives the REALITY auth key with HKDF-SHA256 using `client_random[..20]` as salt.
4. It seals the 32-byte `session_id` with AES-256-GCM, using `client_random[20..32]` as nonce and the raw ClientHello as AAD.
5. It patches the serialized ClientHello before BoringSSL adds it to the transcript.
6. The patched handshake driver synchronizes BoringSSL's internal session ID with the serialized replacement before processing ServerHello.
7. The REALITY-only connector advertises Ed25519 because the authenticated server flight uses an ephemeral Ed25519 certificate and CertificateVerify signature.
8. Standard certificate verification is disabled because REALITY uses its own authentication model.

The vendored BoringSSL hook surface is declared in `reality_hook.rs`;
`reality_seal.rs` owns the HKDF/AES-GCM sealing logic. The exact BoringSSL pin
lives in `native/rust/Cargo.toml`; `tests/reality_hook_vector.json` and
`scripts/ci/check_reality_boring_vector.py` are the executable compatibility
oracles.

## XTLS-Vision

Vision is an addon string carried in the request `Addons` field. It controls TLS-in-TLS detection avoidance via a stream wrapper (`VisionStream` in `vision.rs`).

The VLESS response header is validated lazily on the first downlink read because xray-core flushes it with the first outbound payload. When Vision emits or receives `Direct`, subsequent bytes use the transport beneath the outer REALITY TLS stream; `End` stops padding without removing that TLS layer. The outer TLS reader is bounded to one complete record so it cannot consume coalesced post-`Direct` raw bytes before the splice transition.

## XUDP over VLESS Mux

Vision UDP uses VLESS command `0x03` (`Mux`) without a VLESS destination. It does not use the Sager/yamux carrier and never opens a direct UDP egress. The first XUDP datagram carries session ID `0`, status `New`, option `Data`, network `UDP`, destination, and one random 8-byte GlobalID stable for the association. Later datagrams use `Keep` and repeat their destination, allowing one association to reach multiple targets. Downlink `Keep` frames may omit the source only after an earlier frame established it; `KeepAlive` carries no datagram.

XUDP addresses use port-before-address ordering for IPv4, domain, and IPv6. Metadata is limited to 512 bytes and payloads to 7,526 bytes (`8192 - 666`). Empty, oversized, malformed, and truncated datagrams fail explicitly. A single reader task owns the stream read half and publishes complete datagrams through a bounded 32-item channel, so cancelling `recv_from()` cannot desynchronize the carrier.

`xtls-rprx-vision` rejects UDP port 443 locally. `xtls-rprx-vision-udp443` permits it at the client boundary, but deployment policy may still block QUIC independently.

## Known divergences from upstream

- VLESS mux supports only the SagerNet sing-mux version-0 carrier with the yamux inner protocol. `smux` and `h2mux` are rejected explicitly; they are distinct protocols and must never be coerced to yamux.
- Direct VLESS UDP (`Command = 0x02`) is not implemented; UDP ASSOCIATE uses XUDP over Mux.
- `VlessRealityConfig::flow` supports empty flow, `xtls-rprx-vision`, and `xtls-rprx-vision-udp443`; Android profile UX may expose only a subset.

## Non-goals

- Server-side VLESS implementation.
- Direct VLESS UDP command `0x02` and split TCP/UDP egress.
