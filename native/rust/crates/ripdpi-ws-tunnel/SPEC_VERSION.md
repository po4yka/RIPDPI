# Spec Version

This crate has two distinct wire surfaces with different upstream sources.

## RFC-anchored surfaces

- **WebSocket framing:** RFC 6455 (via `tokio-tungstenite`)
- **HTTP/1.1 Upgrade (non-RFC-6455 variant):** custom, see `httpupgrade.rs`

## Telegram MTProto path

The Telegram-specific MTProto obfuscated2 init handling is pinned against
Telegram's published protocol description rather than a reference
implementation.

- **Upstream repo:** https://core.telegram.org/mtproto (Telegram protocol description)
- **Upstream tag:** n/a (no upstream tags; protocol is documentation-only)
- **Upstream commit:** n/a (not a git source)
- **Telegram DC IP table source:** https://core.telegram.org (data center listing)
- **Last reviewed:** 2026-05-15
- **Owner:** unassigned

## Scope

This crate implements:

- WSS connection to `kws{dc}.web.telegram.org/apiws` with optional
  fake-SNI cover
- 64-byte MTProto obfuscated2 init decryption + DC extraction
- WS tunnel relay for production Telegram DCs 1-5
- An HTTPUpgrade transport variant that deliberately skips RFC 6455
  framing (see `transport.rs`, `httpupgrade.rs`)

## Drift policy

- RFC 6455 is stable; drift not expected.
- Telegram DC IP table is rotated by Telegram; reviewed quarterly per
  `docs/tasks/issues/refresh-telegram-dc-ipv4-range-table-and-add-quarterly-review.md`.
- IPv6 DC support is a tracked gap: see
  `docs/tasks/issues/add-ipv6-telegram-dc-classification-to-ws-tunnel.md`.
