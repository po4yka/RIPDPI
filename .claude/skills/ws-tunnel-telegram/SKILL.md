---
name: ws-tunnel-telegram
description: Telegram MTProto WebSocket tunnel logic, DC routing, target classification, and TLS fingerprints.
---

# WebSocket Tunnel for Telegram (ripdpi-ws-tunnel)

## 1. Purpose

middlebox/DPI blocks Telegram by detecting MTProto signatures on direct TCP to known DC
IPs. The WS tunnel wraps MTProto inside WSS to `kws*.web.telegram.org`, so DPI sees
standard TLS + HTTP Upgrade rather than raw MTProto.

`WsTunnelMode` (in `ripdpi-config/src/model.rs`, `AdaptiveConfig::ws_tunnel_mode`):
- `Off` -- disabled (default)
- `Always` -- every Telegram connection uses WS tunnel immediately
- `Fallback` -- try desync first; escalate to WS tunnel only on failure

## 2. Architecture

```
App -> TUN -> ripdpi-proxy -> handshake pipeline
  1. detect_telegram_dc(target_ip)          -- dc.rs: dc_from_ip
  2. classify_target(target_ip)             -- lib.rs: WsTunnelDecision::Tunnel(dc)
  3. read 64-byte MTProto init from client  -- ws_tunnel.rs: read_mtproto_seed
  4. classify_mtproto_seed(init)            -- mtproto.rs: ValidatedMtproto{dc}
  5. resolve_ws_tunnel_addr(dc)             -- ws_bootstrap.rs: encrypted DNS
  6. open_ws_tunnel(dc, resolved_addr)      -- connect.rs: TLS + WS handshake
  7. ws_relay(client, ws, seed_request)     -- relay.rs: bidirectional relay
```

Runtime entry points (`ripdpi-runtime/src/runtime/handshake/ws_tunnel.rs`):
- `should_ws_tunnel_first()` -- `WsTunnelMode::Always` + known Telegram IP
- `should_ws_tunnel_fallback()` -- `WsTunnelMode::Fallback` + known Telegram IP
- `run_ws_tunnel()` / `run_ws_tunnel_with_seed()` -- classify, resolve, relay

`WsTunnelResult` enum: `ValidatedMtproto`, `NotMtproto`, `UnmappableDc`,
`ShortInit`, `BootstrapFailed`, `WsOpenOrRelayFailed`.

## 3. Telegram DC Database (`dc.rs`)

**Types:** `TelegramDc` (number/raw/class), `TelegramDcClass` (Production/Test/MediaOrCdn).
`TelegramDc::from_raw()`: 1-5 = Production, 10001-10005 = Test, -1..-5 = MediaOrCdn.
`is_tunnelable()` returns `false` for MediaOrCdn (no WS gateway).

**IP ranges** (`dc_from_ip`): maps IPv4 by first two octets:

| Prefix   | 3rd octet | DC | Prefix   | 3rd octet | DC |
|----------|-----------|----|----------|-----------|----|
| 149.154  | 160-163   | 1  | 91.108   | 56-59     | 5  |
| 149.154  | 164-167   | 2  | 91.108   | 8-11      | 3  |
| 149.154  | 168-171   | 3  | 91.108   | 12-15     | 4  |
| 149.154  | 172-175   | 1  | 91.108   | fallback  | 2  |
| 149.154  | fallback  | 2  | 91.105.* | --        | 2  |
|          |           |    | 185.76.* | --        | 2  |

Unrecognized subnets within Telegram prefixes fall back to DC2 intentionally --
unknown-but-Telegram traffic should be tunneled rather than leaked as raw MTProto.
IPv6 always returns `None`.

**WS endpoints:** `ws_host()` -> `kws{n}.web.telegram.org` (or `kws{n}-test.*`),
`ws_url()` -> `wss://kws{n}.web.telegram.org/apiws`. MediaOrCdn returns `None`.

## 4. MTProto Handling (`mtproto.rs`)

The proxy intercepts all TCP to Telegram IPs, but not all is MTProto (HTTPS API,
media downloads, etc). The 64-byte obfuscated2 init has a distinctive structure
for reliable classification without false positives.

**Pipeline:** `classify_mtproto_seed(seed)` ->
1. `has_valid_encrypted_prefix(init)` -- rejects `0xef` first byte, TLS prefix,
   HTTP methods (GET/HEAD/POST/OPTI), transport tags (0xdddddddd/eeeeeeee/efefefef)
2. `decrypt_init_packet(init)` -- AES-256-CTR: key=[8..40], IV=[40..56]
3. `has_allowed_protocol_tag(decrypted)` -- bytes [56..60]: `[0xdd;4]`/`[0xee;4]`/`[0xef;4]`
4. DC extraction from bytes [60..64] as `i32` LE -> `TelegramDc::from_raw()`

**Results:** `ValidatedMtproto{dc}` (tunnelable), `NotMtproto` (prefix/tag fail),
`UnmappableDc{raw_dc, dc}` (valid MTProto but media/CDN or unknown DC).

## 5. VPN Socket Protection (`protect.rs`)

On Android, outgoing sockets must be "protected" to prevent VPN routing loops.
`protect_socket(socket, path)` sends the socket FD via `SCM_RIGHTS` over a Unix
socket to the Java VpnService which calls `VpnService.protect(fd)`. Compiled only
on linux/android; returns `Unsupported` elsewhere. Path flows from
`RuntimeConfig::process::protect_path` -> `WsTunnelConfig::protect_path` ->
`connect_tcp_socket()`.

## 6. TLS Backend (`connect.rs`)

Two backends via `chrome-fingerprint` feature flag:
- **Default (rustls):** `WebSocket<StreamOwned<ClientConnection, TcpStream>>`,
  webpki-roots, ALPN `http/1.1`, explicit handshake loop
- **chrome-fingerprint (BoringSSL):** `WebSocket<SslStream<TcpStream>>`,
  Chrome-native ClientHello defeating JA3/JA4 fingerprinting

### Why two backends (2026 perspective)

The `ripdpi-tls-profiles` crate (wrapping `boring`) is the fingerprint-sensitive path. The rustls path is the standard-TLS path where fingerprinting isn't a concern (DNS-over-TLS bootstrap, host-verification-only targets). Rationale as of April 2026:

- **rustls narrowed the perf gap**: the Q1 2026 Prossimo benchmark showed rustls handshake throughput within ~5% of BoringSSL on x86_64. See <https://www.memorysafety.org/blog/26q1-rustls-performance/>. The historical perf argument for BoringSSL is weak.
- **TLS fingerprint mimicry still requires BoringSSL**: no Rust crate (as of April 2026) offers byte-precise ClientHello construction for JA3/JA4 mimicry. The `ripdpi-tls-profiles` crate wraps `SSL_CTX` at the BoringSSL C level to control extension order, GREASE values, and cipher suite ordering. When a uTLS-in-Rust equivalent lands, this backend can migrate.
- **`aws-lc-rs` not ready on Android**: the FIPS-validated provider for rustls (`rustls-aws-lc-rs`) has open Android cross-compile issues as of Jan 2026 (see <https://github.com/aws/aws-lc-rs/issues/1006>), so switching the default backend isn't viable yet.

### The 517-byte ClientHello invariant

`ripdpi-tls-profiles` enforces an invariant named `AvoidsBlocked517ByteClientHello` across all four profiles (`chrome_stable`, `firefox_stable`, `safari_stable`, `edge_stable`). A ClientHello with exactly 517 bytes total length is known to be flagged by certain DPI implementations (the length sits right at a common buffer boundary that triggers deep-inspection heuristics).

**Rule**: any new profile added to the set must maintain this invariant. The crate's test suite asserts it; do not bypass the assertion. Profile weights: Chrome 65%, Firefox 20%, Safari 10%, Edge 5% — this distribution matches plausible real traffic and must not drift without a corresponding threat-model review.

See the `desync-engine` skill for adjacent QUIC anti-fingerprinting concerns (quinn `pad_to_mtu`, USENIX 2025 GFW research).

**Connection flow:** resolve target (pre-resolved or DNS) -> TCP connect with
optional VPN protect + timeout -> bootstrap timeouts for handshake -> TLS ->
`build_ws_request` (Sec-WebSocket-Protocol: binary) -> tungstenite WS handshake ->
switch to relay timeouts (read=`WS_READ_TIMEOUT` 10ms, write=None).

## 7. Relay Loop (`relay.rs`)

`ws_relay(client, ws, seed_request)` -- bidirectional WS<->TCP.

**Threading:** main thread owns WebSocket (drains outbound queue + reads inbound),
spawned `ripdpi-ws-up` thread reads client TCP into bounded `sync_channel(16)`.

**Init:** send first 64 bytes as WS binary frame, then any `seed_request[64..]`
remainder, then start bidirectional relay.

**Constants:** `CLIENT_READ_TIMEOUT` 250ms, `OUTBOUND_QUEUE_CAPACITY` 16,
`MAX_OUTBOUND_BURST` 8, `OUTBOUND_QUEUE_RETRY_DELAY` 1ms,
`CLOSE_HANDSHAKE_TIMEOUT` 5s.

**Shutdown:** cooperative via `AtomicBool` -- either thread sets it on EOF/error.
`drive_close_handshake()` attempts graceful WS close. Ping/Pong handled
automatically by tungstenite.

## 8. Encrypted DNS Bootstrap (`ws_bootstrap.rs`)

`resolve_ws_tunnel_addr(dc, runtime_context, protect_path)` resolves
`kws{n}.web.telegram.org` via encrypted DNS (DoH/DoT), using
`ProxyRuntimeContext` if available, falling back to default context. Prevents
DNS-based blocking of the WS tunnel endpoint.

## 9. Diagnostics Integration (`ripdpi-monitor/src/telegram.rs`)

The `telegram_availability` probe includes `telegram_ws_tunnel_probe()`:
TLS+WS handshake to DC2 without sending MTProto data. Reports `wsTunnelStatus`,
`wsTunnelRttMs`, `wsTunnelError`. Feeds composite `qualityScore` (DL 3x, UL 2x,
DC 1x, WS 1x). Wrapped in `catch_unwind` for panic recovery.

Telemetry: `on_ws_tunnel_escalation(target, dc, success)` fires on fallback
escalation (`ripdpi-android/src/telemetry.rs`).

## 10. Updating Telegram DC Ranges

1. Edit `dc_from_ip()` in `native/rust/crates/ripdpi-ws-tunnel/src/dc.rs`
2. Add `(octet0, octet1) => match o[2] { ... }` arms
3. Add boundary tests (first and last IP in range)
4. If adding DC6+, update `TelegramDc::from_raw()` range checks
5. Run `cargo test -p ripdpi-ws-tunnel`
6. Update DC endpoints in diagnostics probe target configuration

Static lookup table by design -- must work without network, and DC ranges change
rarely (last: 185.76.0.0/16 added for DC2).

## 11. Common Issues

**TLS handshake timeout:** `connect_timeout` covers TCP connect + TLS/WS bootstrap.
Too short for high-latency networks causes handshake failure. Relay socket uses
separate 10ms read timeout after handshake succeeds.

**Non-tunnelable DC:** Media/CDN DCs (raw < 0) return `UnmappableDc`; runtime
falls back to desync. Expected behavior.

**Bootstrap DNS failure:** `BootstrapFailed` returned; seed request preserved
for desync fallback.

**Relay backpressure:** Outbound queue (16) fills when WS endpoint is slow.
Uplink retries at 1ms intervals; sustained pressure blocks uplink (preferable
to unbounded memory growth).

**No IPv6:** `classify_target` returns `Passthrough` for IPv6. Telegram does not
serve MTProto over IPv6 in the Russian market.
