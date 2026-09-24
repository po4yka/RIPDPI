---
name: ws-tunnel-telegram
description: "MTProto-over-WebSocket tunnel for Telegram: DC classification, obfuscated2 detection, TLS backend choice, and the relay loop. Use when touching Telegram DC routing, MTProto classification, or ripdpi-ws-tunnel."
---

# WebSocket Tunnel for Telegram (ripdpi-ws-tunnel)

## 1. Purpose

middlebox/DPI blocks Telegram by detecting MTProto signatures on direct TCP to known DC
IPs. The WS tunnel wraps MTProto inside WSS to `kws*.web.telegram.org`, so DPI sees
standard TLS + HTTP Upgrade rather than raw MTProto.

`WsTunnelMode` lives in `ripdpi-config/src/model/group.rs`; the adaptive runtime setting lives in `model/runtime.rs`:
- `Off` -- disabled (default)
- `Always` -- every Telegram connection uses WS tunnel immediately
- `Fallback` -- try desync first; escalate to WS tunnel only on failure

## 2. Architecture

Three crates now share this pipeline. `ripdpi-ws-transport-port` owns DC
classification and the `WsTransport` trait boundary; `ripdpi-ws-bootstrap`
owns encrypted-DNS resolution of the WS endpoint; `ripdpi-ws-tunnel` owns the
concrete MTProto/TLS/WS implementation and re-exports the transport-port types
so most call sites only need to depend on `ripdpi-ws-tunnel`.

```
App -> TUN -> ripdpi-proxy -> handshake pipeline
  1. classify_target(target_ip)             -- ripdpi-ws-transport-port/src/dc.rs (dc_from_ip / dc_from_ipv6)
  2. read 64-byte MTProto init from client  -- ripdpi-proxy-runtime/.../handshake/ws_tunnel.rs
  3. classify_mtproto_seed(init)            -- ripdpi-ws-tunnel/src/mtproto.rs: ValidatedMtproto{dc}
  4. resolve_ws_tunnel_addr(dc)             -- ripdpi-ws-bootstrap/src/resolver.rs: encrypted DNS
  5. open_ws_tunnel(dc, resolved_addr)      -- ripdpi-ws-tunnel/src/connect.rs: TLS + WS handshake
  6. ws_relay(client, ws, seed_request)     -- ripdpi-ws-tunnel/src/relay.rs (+ relay/ submodules)
```

Runtime entry points (`ripdpi-proxy-runtime/src/runtime/handshake/ws_tunnel.rs`):
- `should_ws_tunnel_first()` -- `WsTunnelMode::Always` + known Telegram IP
- `should_ws_tunnel_fallback()` -- `WsTunnelMode::Fallback` + known Telegram IP
- `run_ws_tunnel()` / `run_ws_tunnel_with_seed()` -- classify, resolve, relay

`WsTunnelResult` enum: `ValidatedMtproto`, `NotMtproto`, `UnmappableDc`,
`ShortInit`, `BootstrapFailed`, `WsOpenOrRelayFailed`.

## 3. Telegram DC Database (`ripdpi-ws-transport-port/src/dc.rs`)

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

**IPv6** (`dc_from_ipv6`): two recognised Telegram-owned supernets are also
tunneled, each mapped to one representative production DC (not per-sub-prefix):
`2001:67c:4e8::/48` -> DC2, `2001:b28:f23c::/46`..`f23f` -> DC3. Any other IPv6
address returns `None` (passthrough, not tunneled). `classify_target()` in
`ripdpi-ws-transport-port/src/lib.rs` calls `dc_from_ipv6()` for `IpAddr::V6`
exactly like the V4 path -- IPv6 Telegram traffic in the two known supernets is
tunneled, not silently dropped to passthrough.

**WS endpoints:** `ws_host()` -> `kws{n}.web.telegram.org` (or `kws{n}-test.*`),
`ws_url()` -> `wss://kws{n}.web.telegram.org/apiws`. MediaOrCdn returns `None`.

**Cloudflare Worker route (`worker_route.rs`):** `CloudflareWorkerRoute` +
`WorkerBearer` let an operator route the outer TLS/WebSocket connection
through their own Cloudflare Worker (real Telegram gateway carried in
`X-Ripdpi-Upstream`) instead of connecting to `kws*.web.telegram.org`
directly. `WorkerBearer` validates an RFC 6750 bearer token and redacts itself
in `Debug`. A worker route must never be combined with `fake_sni` -- the
implementation is required to reject that combination so a verified Worker
TLS connection is never silently downgraded to the insecure fake-SNI path.

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

## 8. Encrypted DNS Bootstrap (`ripdpi-ws-bootstrap` crate, `src/resolver.rs`)

`resolve_ws_tunnel_addr(dc, runtime_context, protect_path)` resolves
`kws{n}.web.telegram.org` via encrypted DNS (DoH/DoT), using
`ProxyRuntimeContext` if available, falling back to default context. Prevents
DNS-based blocking of the WS tunnel endpoint. This is a separate crate from
`ripdpi-ws-tunnel`; it depends on `ripdpi-dns-resolver` and
`ripdpi-ws-transport-port` (for `TelegramDc`/`ws_host()`), not the other way
around. A same-named `resolve_ws_tunnel_addr` wrapper also exists as a
`pub(in crate::runtime)` method on `ripdpi-proxy-runtime/src/runtime/state/ws.rs`
-- that one just forwards into this crate's function with the runtime's
current context, it is not a second implementation.

## 9. Diagnostics Integration (`ripdpi-diagnostics-telegram/src/telegram.rs`)

The `telegram_availability` probe includes `telegram_ws_tunnel_probe()`:
TLS+WS handshake to DC2 without sending MTProto data. Reports `wsTunnelStatus`,
`wsTunnelRttMs`, `wsTunnelError`. Feeds composite `qualityScore` (DL 3x, UL 2x,
DC 1x, WS 1x). Wrapped in `catch_unwind` for panic recovery.

Telemetry: `on_ws_tunnel_escalation(target, dc, success)` fires on fallback
escalation (`ripdpi-android-telemetry-adapter/src/{observer,adaptive}.rs`); runtime emission lives in `ripdpi-proxy-runtime/src/runtime/state/ws.rs`.

## 10. Updating Telegram DC Ranges

1. Edit `dc_from_ip()` (IPv4) or `dc_from_ipv6()` (IPv6) in
   `native/rust/crates/ripdpi-ws-transport-port/src/dc.rs`
2. Add `(octet0, octet1) => match o[2] { ... }` arms (IPv4) or a new supernet
   comparison against `ip.segments()` (IPv6)
3. Add boundary tests (first and last IP/supernet in range)
4. If adding DC6+, update `TelegramDc::from_raw()` range checks
5. Bump `TELEGRAM_DC_IPV4_TABLE_LAST_REVIEWED` and keep the
   `dc_ipv4_table_provenance` test's `YYYY-MM-DD` format check passing
6. Run `cargo test --locked -p ripdpi-ws-transport-port`
7. Update DC endpoints in diagnostics probe target configuration

Static lookup table by design -- must work without network. Per the docstring
in `dc.rs`, the IPv4 table is reviewed quarterly (`docs/strategy-pack-operations.md`
"Telegram DC table review"); the IPv6 supernet mapping shares that review
obligation. Do not treat "last reviewed" as a fixed historical fact -- read
`TELEGRAM_DC_IPV4_TABLE_LAST_REVIEWED` from source rather than restating a date here.

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

**Partial IPv6 coverage:** `classify_target()` tunnels IPv6 addresses inside the
two known Telegram supernets (see section 3) exactly like IPv4, but returns
`Passthrough` for any other IPv6 address -- there is no IPv6 equivalent of the
IPv4 "unrecognized subnet still falls back to DC2" behavior. An IPv6 Telegram
address outside those two supernets is not detected and is not tunneled.
