# ripdpi-ws-tunnel

**Responsibility:** the MTProto WebSocket tunnel for Telegram traffic — routes
MTProto through the official `kws{dc}.web.telegram.org` web gateways with
obfuscated2 framing and DC normalization.
**Layer:** L7 — relay transports.

This is a **Telegram-specific** tunnel, distinct from the `relay_kind`
transports — it is gated by the `ws_tunnel_enabled` / `ws_tunnel_mode` settings
(Always / Fallback), not by `relay_kind`.

## Stable identifiers / contracts

`ws_tunnel_enabled` / `ws_tunnel_mode` / `ws_tunnel_fake_sni` settings; the
MTProto obfuscated2 framing; the `AvoidsBlocked517ByteClientHello` ClientHello
invariant; the Telegram DC IP database. See the `ws-tunnel-telegram` skill.

## Dependency direction

**Upstream:** `ripdpi-tls-profiles` (`boring`, `tokio`). **Downstream:**
`ripdpi-ws-bootstrap`.

## Non-root fallback

No privileged operations — runs fully on non-rooted devices. See
[`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md).

## Extension checklist

1. Changes to DC routing, obfuscated2 classification, or the ClientHello
   fingerprint go through the `ws-tunnel-telegram` skill.
2. Preserve the `AvoidsBlocked517ByteClientHello` invariant.

---
See [`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md),
[`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md),
and [`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md).
