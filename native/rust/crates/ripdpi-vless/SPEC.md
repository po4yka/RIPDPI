# SPEC — `ripdpi-vless`

## Scope

Client implementation of the VLESS application protocol, the REALITY TLS handshake, and the XTLS-Vision flow addon. Used both standalone (TCP → REALITY → VLESS) and chained (over an existing transport via `connect_over`).

## Upstream

- xray-core (https://github.com/XTLS/Xray-core)
- Pin recorded in `SPEC_VERSION.md`

## VLESS request wire format

| Field | Size | Notes |
|---|---|---|
| Version | 1 byte | `0x01` |
| UUID | 16 bytes | Subscriber ID |
| AddonsLen | 1 byte | Length of `Addons` blob |
| Addons | var | Vision flow string, etc. |
| Command | 1 byte | `0x01` = TCP CONNECT (UDP unsupported) |
| Port | 2 bytes | Big-endian |
| AddrType | 1 byte | `0x01` IPv4, `0x02` domain, `0x03` IPv6 |
| Address | var | IPv4: 4 bytes; IPv6: 16 bytes; domain: 1-byte len + UTF-8 |

See `wire.rs` for the encoder; the response header is one version byte + one addons-length byte + variable addons.

## REALITY TLS handshake

1. Generate ephemeral X25519 keypair.
2. ECDH shared secret with the server's static public key.
3. Derive auth key via HKDF-SHA256 with `client_random[20..]` as salt.
4. Encrypt and inject the session_id into the ClientHello.
5. Disable standard cert verification (REALITY uses its own auth model).

Six BoringSSL FFI symbols are declared locally (see `reality.rs` extern block). Pinning policy in `docs/tasks/issues/pin-boringssl-symbols-with-build-time-existence-check.md`.

## XTLS-Vision

Vision is an addon string carried in the request `Addons` field. It controls TLS-in-TLS detection avoidance via a stream wrapper (`VisionStream` in `vision.rs`).

## Known divergences from upstream

- VLESS-mux protocol may lag upstream framing tweaks; see `docs/tasks/issues/add-vless-mux-conformance-tests-against-xray-core.md`.
- UDP forwarding (`Command = 0x02`) is not implemented; only TCP CONNECT.
- Some flow variants (e.g. `xtls-rprx-vision-udp443`) are not covered; see `docs/tasks/issues/add-vless-flow-xtls-rprx-vision-udp443-support.md`.

## Non-goals

- Server-side VLESS implementation.
- UDP-over-VLESS until upstream stabilizes the wire.
