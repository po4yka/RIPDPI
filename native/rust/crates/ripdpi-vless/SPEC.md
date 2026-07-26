# SPEC — `ripdpi-vless`

## Scope

Client implementation of the VLESS application protocol, the REALITY TLS handshake, and the XTLS-Vision flow addon. Used both standalone (TCP → REALITY → VLESS) and chained (over an existing transport via `connect_over`).

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
| Command | 1 byte | `0x01` = TCP CONNECT (UDP unsupported) |
| Port | 2 bytes | Big-endian |
| AddrType | 1 byte | `0x01` IPv4, `0x02` domain, `0x03` IPv6 |
| Address | var | IPv4: 4 bytes; IPv6: 16 bytes; domain: 1-byte len + UTF-8 |

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

## Known divergences from upstream

- VLESS mux supports only the SagerNet sing-mux version-0 carrier with the yamux inner protocol. `smux` and `h2mux` are rejected explicitly; they are distinct protocols and must never be coerced to yamux.
- UDP forwarding (`Command = 0x02`) is not implemented; only TCP CONNECT.
- `VlessRealityConfig::flow` supports empty flow, `xtls-rprx-vision`, and `xtls-rprx-vision-udp443`; Android profile UX may expose only a subset.

## Non-goals

- Server-side VLESS implementation.
- UDP-over-VLESS until upstream stabilizes the wire.
