# SPEC — `ripdpi-ws-tunnel`

## Scope

WebSocket tunnel for Telegram MTProto traffic, plus a generic HTTP/1.1 Upgrade transport variant that deliberately skips RFC 6455 framing.

## Standards / upstream

- RFC 6455 — WebSocket protocol (used via `tokio-tungstenite`)
- Telegram MTProto 2.0 obfuscated2 — see https://core.telegram.org/mtproto
- Pin recorded in `SPEC_VERSION.md`

## Tunnel flow

1. Client opens a TCP socket (protected via VPN `protect` socket when under VPN mode).
2. TLS to `kws{dc}.web.telegram.org`. A configured fake-SNI cover is rejected
   unless `allow_insecure_sni=true` explicitly acknowledges the certificate
   bypass; the insecure mode is never inferred from the hostname alone.
3. WSS upgrade to `/apiws` with the `Sec-WebSocket-Protocol: binary` subprotocol.
4. First 64 bytes of the relayed stream are validated MTProto obfuscated2 init bytes; remaining bytes are forwarded as WS frames.

## MTProto init

`mtproto.rs` performs AES-256-CTR keystream extraction over the 64-byte init to extract DC number and tag. Allowed protocol tags: `0xdd 0xdd 0xdd 0xdd`, `0xee 0xee 0xee 0xee`, `0xef 0xef 0xef 0xef`.

Recognized encrypted prefixes: TLS, GET, HEAD, OPTIONS, POST, plus padded-intermediate, intermediate, abridged.

## Telegram DC IP table

Production DCs 1-5 are mapped from known IPv4 and Telegram IPv6 supernets in
`dc.rs`. Only unmatched IPv6 addresses are returned as passthrough.

Review the v4 table when Telegram publishes data-center range changes; there is no active recurring task note in this tree.

## HTTPUpgrade (non-RFC-6455 variant)

`httpupgrade.rs` provides an alternative transport that uses HTTP/1.1 `Upgrade` semantics without the WebSocket framing. No `Sec-WebSocket-*` headers, no length framing — the upgraded connection carries raw bytes.

## Non-goals

- Telegram MTProto proxy server implementation.
- MTProto-2.0 framing decryption beyond DC identification.
