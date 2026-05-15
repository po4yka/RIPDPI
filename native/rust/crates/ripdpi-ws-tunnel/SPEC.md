# SPEC — `ripdpi-ws-tunnel`

## Scope

WebSocket tunnel for Telegram MTProto traffic, plus a generic HTTP/1.1
Upgrade transport variant that deliberately skips RFC 6455 framing.

## Standards / upstream

- RFC 6455 — WebSocket protocol (used via `tokio-tungstenite`)
- Telegram MTProto 2.0 obfuscated2 — see
  https://core.telegram.org/mtproto
- Pin recorded in `SPEC_VERSION.md`

## Tunnel flow

1. Client opens a TCP socket (protected via VPN `protect` socket when
   under VPN mode).
2. TLS to `kws{dc}.web.telegram.org` (or to a configured fake-SNI
   cover hostname; cert validation is then disabled — see
   `docs/tasks/issues/gate-fake-sni-cert-bypass-behind-allow-insecure-flag-with-telemetry.md`).
3. WSS upgrade to `/apiws` with the `Sec-WebSocket-Protocol: binary`
   subprotocol.
4. First 64 bytes of the relayed stream are validated MTProto
   obfuscated2 init bytes; remaining bytes are forwarded as WS frames.

## MTProto init

`mtproto.rs` performs AES-256-CTR keystream extraction over the
64-byte init to extract DC number and tag. Allowed protocol tags:
`0xdd 0xdd 0xdd 0xdd`, `0xee 0xee 0xee 0xee`, `0xef 0xef 0xef 0xef`.

Recognized encrypted prefixes: TLS, GET, HEAD, OPTIONS, POST, plus
padded-intermediate, intermediate, abridged.

## Telegram DC IP table

Production DCs 1-5 are mapped from IPv4 addresses in `dc.rs`. IPv6 is
returned as passthrough; see
`docs/tasks/issues/add-ipv6-telegram-dc-classification-to-ws-tunnel.md`.

Quarterly review obligation for the v4 table:
`docs/tasks/issues/refresh-telegram-dc-ipv4-range-table-and-add-quarterly-review.md`.

## HTTPUpgrade (non-RFC-6455 variant)

`httpupgrade.rs` provides an alternative transport that uses HTTP/1.1
`Upgrade` semantics without the WebSocket framing. No `Sec-WebSocket-*`
headers, no length framing — the upgraded connection carries raw bytes.

## Non-goals

- Telegram MTProto proxy server implementation.
- MTProto-2.0 framing decryption beyond DC identification.
