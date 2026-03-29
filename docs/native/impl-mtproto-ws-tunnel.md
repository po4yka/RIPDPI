# Implementation: MTProto WebSocket Tunnel (Telegram-Specific)

## Status

Implemented in the existing native proxy stack. This feature does **not** use a separate tunnel crate or Android surface area; it is wired through the current `ripdpi-ws-tunnel`, `ripdpi-runtime`, and Android proxy telemetry layers.

## Scope

- Telegram-only
- MTProto-only
- No new protobuf, JSON, or JNI fields
- Existing user-facing settings remain `ws_tunnel_enabled` / `ws_tunnel_mode`

## Current Architecture

```
Telegram client
  -> SOCKS5 / HTTP CONNECT / transparent proxy
  -> Known Telegram DC IP prefilter
  -> Read first 64 bytes
  -> Validate MTProto obfuscated2 init
  -> Normalize raw DC ID
  -> Open WSS to Telegram Web gateway
  -> Relay MTProto bytes over WebSocket binary frames
```

### Native Components

- `native/rust/crates/ripdpi-ws-tunnel/src/dc.rs`
  Defines normalized Telegram DC handling, host/url mapping, and Telegram IP classification.
- `native/rust/crates/ripdpi-ws-tunnel/src/mtproto.rs`
  Validates the obfuscated2 init packet, decrypts the MTProto transport header, verifies the protocol tag, and normalizes the raw DC ID.
- `native/rust/crates/ripdpi-ws-tunnel/src/connect.rs`
  Opens the WSS connection with `Sec-WebSocket-Protocol: binary`.
- `native/rust/crates/ripdpi-ws-tunnel/src/relay.rs`
  Sends the 64-byte init as the first binary frame, forwards any already-consumed remainder, then relays the rest of the stream bidirectionally.
- `native/rust/crates/ripdpi-runtime/src/runtime/handshake/ws_tunnel.rs`
  Applies runtime policy: only known Telegram targets are eligible, and only validated MTProto seeds are tunneled.
- `native/rust/crates/ripdpi-runtime/src/runtime/handshake/connect_relay.rs`
  Integrates `Always` and `Fallback` WS modes into the normal connect/relay path and preserves seed bytes for desync fallback when possible.

## MTProto Validation Rules

The WS tunnel path is taken only when the first 64 bytes pass all of the following checks:

1. The encrypted prefix does not match blocked HTTP/TLS/reserved signatures.
2. The obfuscated2 init decrypts successfully using the key/IV embedded in the init.
3. The decrypted transport tag is one of Telegram's allowed MTProto tags.
4. The decrypted raw DC ID normalizes to a tunnelable Telegram DC.

### Raw DC Normalization

- `1..=5` -> production DC1-DC5
- `10001..=10005` -> test DC1-DC5
- `-1..=-5` -> media/CDN-style DC, recognized but **not** tunnelable in v1
- Anything else -> unmappable, fall back to normal desync/passthrough

### Gateway Mapping

- Production: `wss://kws{dc}.web.telegram.org/apiws`
- Test: `wss://kws{dc}-test.web.telegram.org/apiws`
- Media/CDN or unknown encodings: no WSS mapping, fall back

## Runtime Behavior

### `ws_tunnel_mode = always`

- Only attempted for known Telegram DC IP ranges.
- The proxy sends the protocol success reply, reads the first 64 bytes, and validates the MTProto init.
- If validation succeeds, the connection is tunneled over WSS.
- If validation fails, the already-consumed bytes are reused as the desync `seed_request` and the normal relay path continues.

### `ws_tunnel_mode = fallback`

- The normal desync path runs first.
- WS escalation is attempted only when the runtime still has the first request bytes available or can safely read them after a connect failure.
- Non-MTProto and non-tunnelable DC results remain pure desync failures.

## DNS Bootstrap

`ripdpi-runtime::ws_bootstrap` resolves the final Telegram WebSocket hostname through the active encrypted DNS context when possible. If encrypted DNS bootstrap fails, the runtime falls back to normal hostname resolution during the WSS connect attempt.

The DNS helper now shares the same Telegram DC host mapping as the transport crate so production and test gateways stay consistent across runtime dialing and diagnostics probes.

## Telemetry

The Android proxy telemetry observer records WS-specific events without changing the top-level runtime snapshot schema:

- Telegram DC detected
- WS tunnel escalation attempted
- WS tunnel escalation success/failure

These are emitted as native event/log entries only.

## Test Coverage

- `ripdpi-ws-tunnel`
  Covers DC normalization, gateway host mapping, MTProto init validation, blocked-prefix rejection, protocol-tag validation, and relay behavior for consumed seed bytes.
- `ripdpi-runtime`
  Covers MTProto validation before WS usage, non-MTProto fallback, unmappable DC handling, and short-init preservation.
- `ripdpi-runtime::ws_bootstrap`
  Covers shared prod/test host mapping and encrypted-DNS bootstrap behavior.
- `ripdpi-android`
  Covers Telegram DC and WS escalation telemetry events without schema changes.

## Notes

- This feature is intentionally conservative: recognized but non-tunnelable DC classes do not guess a gateway.
- The user-facing UI still says "WS tunnel" for continuity, but the helper text now clarifies that the feature applies to Telegram MTProto traffic only.
