# Integrations and Techniques Roadmap

Prioritized list of integrations and DPI bypass techniques for RIPDPI, based on deep research across the Zapret community, NTC forums, GitHub projects, Cloudflare documentation, protocol specifications, and academic papers (intelligence window: April 2025 -- April 2026).

## Current Threat Landscape

Russian TSPU (technical censorship infrastructure) capabilities as of mid-2025:

- Full TCP reassembly (basic fragmentation alone no longer sufficient)
- TLS ClientHello inspection with SNI extraction across fragments
- WireGuard protocol fingerprinting and active blocking (fixed 4-byte type headers + predictable sizes 148/92/64)
- ECH (Encrypted Client Hello) blocking for Cloudflare SNI since November 2024 (triggers on SNI=`cloudflare-ech.com` + ECH extension combo; silent packet drop)
- Volume-based throttling: connections exceeding 15--20 KB to foreign IPs frozen (not RST, silent drop)
- Emerging **whitelist mode** on some mobile networks: only approved IPs/domains reachable
- ML-based traffic classification: Random Forest and CNN models trained on flow features (packet size distributions, inter-arrival timing, byte entropy ~7.99 bits/byte for encrypted traffic, TLS record size distributions, upload/download packet count asymmetry)
- JA4+ TLS fingerprinting at Cloudflare, AWS, and VirusTotal (sorts extensions alphabetically, includes ALPN and SNI, distinguishes TCP vs QUIC)

### April 2026 Escalation

- **Mobile whitelist regime**: 100+ resources in 13 approved categories; Beeline confirmed IP-level filtering (TCP SYN accepted, TLS ClientHello dropped for non-whitelisted IPs); Tele2 similar. Foreign VPS IPs unreachable on mobile data.
- **Platform VPN detection deadline (April 15, 2026)**: RKN mandated Yandex, VK, Sber, major marketplaces to detect and restrict VPN users. Non-compliance = loss of whitelist status + IT accreditation. Partial Yandex.Cloud subnet exclusions from whitelist.
- **Mobile international traffic surcharge (May 1, 2026)**: 150 RUB/GB over 15 GB/month threshold. VPN users generating international traffic on mobile data face automatic billing. Split routing (bypassing VPN for domestic traffic) becomes financially necessary.
- **MTS commercial VPN detection (March 2026)**: MTS briefly advertised "VPN detection as a service" at 87 RUB/day (page deleted, behaviour observed ~1 day). Pattern-based: sustained high-volume sessions to single IP; WireGuard/OpenVPN/PPTP/L2TP detected; obfuscated protocols (AmneziaWG) not yet.
- **App-level VPN detection (April 2026)**: Mintsifry published 3-stage technical guide. Wildberries, Ozon, MAX Messenger, ВкусВилл, Шоколадница implementing. Primary vector: Android `ConnectivityManager.getNetworkCapabilities().hasTransport(TRANSPORT_VPN)` — system-wide flag, requires no permissions, unaffected by per-app split tunneling.

### What RIPDPI Already Implements

RIPDPI covers ~90% of known DPI desync techniques: split, disorder, multidisorder, fake (TTL/MD5sig), OOB, disoob, TLS record fragmentation (tlsrec/tlsrandrec), IP fragmentation (ipfrag2), sequence overlap, hostfake, QUIC evasion (fake burst, SNI split, fake version, dummy prepend), adaptive fake TTL, per-network policy memory, tournament bracket strategy probing, and encrypted DNS (DoH/DoT/DNSCrypt).

The primary gap is **tunnel/proxy protocol support** for when desync alone is insufficient, and **domestic relay chaining** for whitelist scenarios.

---

## Tier 1: Cloudflare WARP Integration (P0)

### 1. WARP WireGuard Tunnel

Embed a WireGuard tunnel to Cloudflare WARP endpoints as an alternative upstream path alongside the existing SOCKS5 proxy.

#### Registration API

**Base URL:** `https://api.cloudflareclient.com/v0a4005/reg` (version varies: `v0a1922` in wgcf, `v0a4005` in warp-plus)

**Required headers:**
```
User-Agent: okhttp/3.12.1
CF-Client-Version: a-6.30-3596
Content-Type: application/json; charset=UTF-8
```

**TLS requirement:** The API enforces TLS 1.2 exactly (`MinVersion: tls.VersionTLS12`, `MaxVersion: tls.VersionTLS12`). Mismatched TLS versions return 403/error 1020. The `warp-plus` project uses `refraction-networking/utls` with a custom `SNICurve` extension (0x15, 1200 bytes padding) to bypass Cloudflare CDN fingerprinting.

**Registration request (`POST /reg`):**
```json
{
  "install_id": "",
  "fcm_token": "",
  "tos": "2026-04-05T12:00:00.000Z",
  "key": "<base64 WireGuard public key>",
  "type": "Android",
  "model": "PC",
  "locale": "en_US",
  "warp_enabled": true
}
```

**Registration response (full schema):**
```json
{
  "id": "<device UUID>",
  "token": "<bearer auth token for subsequent calls>",
  "account": {
    "id": "<account UUID>",
    "account_type": "free",
    "warp_plus": false,
    "premium_data": 0,
    "quota": 0,
    "license": "<license key>"
  },
  "config": {
    "client_id": "<base64 3-byte client ID>",
    "interface": {
      "addresses": {
        "v4": "172.16.0.2/32",
        "v6": "2606:4700:110:8a36::2/128"
      }
    },
    "peers": [{
      "public_key": "<Cloudflare WG peer public key>",
      "endpoint": {
        "host": "engage.cloudflareclient.com:2408",
        "v4": "162.159.192.1",
        "v6": "2606:4700:d0::a29f:c001"
      }
    }]
  }
}
```

The `config.client_id` (3 bytes, base64) maps to WireGuard's `Reserved` field: `peer.Reserved = [3]byte{clientID[0], clientID[1], clientID[2]}`. MTU is 1330 (single hop) or 1280 (WARP-in-WARP mode).

#### WARP Endpoint IP Ranges

**IPv4 prefixes:** `162.159.192.0/24`, `162.159.195.0/24`, `188.114.96.0/24`, `188.114.97.0/24`, `188.114.98.0/24`, `188.114.99.0/24`

**IPv6 prefixes:** `2606:4700:d0::/64`, `2606:4700:d1::/64`

**Valid UDP ports (54 total):**
```
500, 854, 859, 864, 878, 880, 890, 891, 894, 903, 908, 928, 934, 939,
942, 943, 945, 946, 955, 968, 987, 988, 1002, 1010, 1014, 1018, 1070,
1074, 1180, 1387, 1701, 1843, 2371, 2408, 2506, 3138, 3476, 3581,
3854, 4177, 4198, 4233, 4500, 5279, 5956, 7103, 7152, 7156, 7281,
7559, 8319, 8742, 8854, 8886
```

#### Architecture Options

**Pattern A -- VPN mode (oblivion-android model):**
```
Android VpnService -> TUN fd -> tun2socks/LWIP -> SOCKS5 -> netstack -> WireGuard -> WARP
```
oblivion-android uses: `xjasonlyu/tun2socks/v2` + `golang.zx2c4.com/wireguard` + `gvisor.dev/gvisor/netstack`. VPN config: MTU 1500, IPv4 `172.19.0.1/30`, IPv6 `fdfe:dcba:9876::1/126`, DNS `1.1.1.1`. App excluded via `addDisallowedApplication()`.

**Pattern B -- Proxy mode (wireproxy model):**
```
RIPDPI SOCKS5 proxy -> wireproxy SOCKS5 -> netstack virtual TUN -> WireGuard -> WARP
```
wireproxy uses `wireguard/tun/netstack` (gVisor netstack) to create a virtual network interface entirely in userspace. No kernel TUN, no root, no VpnService claim. SOCKS5 server dials through `Tnet.DialContext()` which routes TCP through the WireGuard tunnel.

**Pattern C -- Hybrid mode:** RIPDPI desync for domestic sites + WARP tunnel for fully blocked sites with rule-based routing based on hostlists or diagnostics probe results.

**Critical detail:** `api.cloudflareclient.com` is DPI-blocked nationwide. RIPDPI must apply its own desync (`multidisorder --split-pos=2`) to the registration request.

**Reference projects:** [wgcf](https://github.com/ViRb3/wgcf) (Go, registration API), [warp-plus](https://github.com/bepass-org/warp-plus) (Go, full WARP with endpoint scanning), [wireproxy](https://github.com/pufferffish/wireproxy) (Go, WG-as-SOCKS5), [oblivion](https://github.com/bepass-org/oblivion) (Java/Go, Android WARP), [WG Tunnel](https://github.com/wgtunnel/wgtunnel) (Kotlin/Go, Android WG + AmneziaWG)

**Root required:** No | **Effort:** High

---

### 2. AmneziaWG Obfuscation Layer

Add AmneziaWG parameters on top of the WARP WireGuard tunnel to defeat WireGuard protocol fingerprinting.

#### Obfuscation Parameters (UAPI)

| UAPI Key | Purpose | Typical Values |
|----------|---------|----------------|
| `jc` | Number of junk packets before handshake | 3--10 |
| `jmin` | Minimum junk packet size (bytes) | 50--64 |
| `jmax` | Maximum junk packet size (bytes) | 1000--1024 |
| `h1` | Header range for Init packets (replaces byte `1`) | `100000-800000` |
| `h2` | Header range for Response packets (replaces byte `2`) | `1000000-8000000` |
| `h3` | Header range for Cookie packets (replaces byte `3`) | `10000000-80000000` |
| `h4` | Header range for Transport packets (replaces byte `4`) | `100000000-800000000` |
| `s1` | Padding bytes prepended to Init (148B base) | 0--64 |
| `s2` | Padding bytes prepended to Response (92B base) | 0--64 |
| `s3` | Padding bytes prepended to Cookie (64B base) | 0--64 |
| `s4` | Padding bytes prepended to Transport data | 0--32 |

**Constraint:** `S1 + 56 != S2` (avoids recreating original size relationship). H1--H4 ranges must not overlap.

#### Header Replacement -- Byte Level

On send: standard type byte (1--4) replaced with crypto-random uint32 in `[start, end]`. Random padding prepended before the actual WireGuard packet. On receive: `DeterminePacketTypeAndPadding()` reads uint32 at offset `padding`, validates against header ranges, identifies type by both header match AND expected size (Init: pad+148, Response: pad+92, Cookie: pad+64, Transport: pad+16+).

#### Junk Packets

Each junk packet: `rand(jmin, jmax)` bytes of `crypto/rand` data. No headers, no structure -- pure entropy. Sent as individual UDP datagrams before handshake. Receivers discard (fail all header validation). Performance overhead: ~3--7%.

#### CPS Decoy Packets (AmneziaWG 2.0, I1--I5)

Up to 5 decoy UDP packets before handshake using a template language:

| Tag | Output |
|-----|--------|
| `<b 0xHEX>` | Fixed hex bytes |
| `<r N>` | N random bytes |
| `<rc N>` | N random ASCII letters `[a-zA-Z]` |
| `<rd N>` | N random digit chars `[0-9]` |
| `<t>` | 4-byte UNIX timestamp |

Example: `i1 = <b 0xc700000001><rc 8><t><r 50>` produces 67-byte datagram mimicking QUIC Initial.

**Reference:** [amneziawg-go](https://github.com/amnezia-vpn/amneziawg-go), [AmneziaWG 2.0 spec](https://docs.amnezia.org/documentation/amnezia-wg/)

**Root required:** No | **Effort:** Medium

---

### 3. WARP Domain Hostlist for DPI Desync

Built-in hostlist for Cloudflare WARP domains requiring DPI desync:

```
api.cloudflareclient.com
connectivity.cloudflareclient.com
engage.cloudflareclient.com
downloads.cloudflareclient.com
zero-trust-client.cloudflareclient.com
pkg.cloudflareclient.com
consumer-masque.cloudflareclient.com
```

`api.cloudflareclient.com` TCP is consistently blocked during registration. Some regions also block `engage.cloudflareclient.com:2408`. Zero Trust needs all seven; standard WARP needs at minimum the first two.

**Root required:** No | **Effort:** Low

---

### 4. WARP Endpoint Scanner

Built-in scanner using actual WireGuard handshake probes (not just ICMP/TCP). Tests random `(IP, port)` pairs from 6 IPv4 prefixes x 54 ports. Measures RTT with 5000ms timeout, 200 iterations, 10 parallel threads. Returns sorted by latency, filtering under 1500ms.

Mobile vs WiFi endpoints differ (different ISP routing, different TSPU rules). Integrates with RIPDPI's per-network policy memory and network handover detection.

**Root required:** No | **Effort:** Medium

---

## Tier 2: DPI Desync Enhancements (P1)

### 5. Auto-Strategy with Trigger-Based Fallback

ByeDPI's `-A <trigger>` model for real-time adaptive fallback during normal proxy operation.

#### Trigger System (from ByeDPI `extend.c`)

| Flag | Trigger | Detection Method |
|------|---------|-----------------|
| `DETECT_TORST` (8) | Timeout or RST | `ECONNRESET`, `ECONNREFUSED`, `ETIMEDOUT`; consecutive timeout count |
| `DETECT_TLS_ERR` (2) | TLS failure | FIN during first round on TLS; non-ServerHello response; session ID mismatch |
| `DETECT_HTTP_LOCAT` (1) | Block page redirect | HTTP 300--308 with `Location:` to different SLD |
| `DETECT_CONNECT` (16) | TCP connect failure | `ECONNREFUSED`, `ETIMEDOUT`, `EHOSTUNREACH` |

#### Strategy Cache

Keyed by **IP + port** (KAVL tree). Supports **subnet-level matching** via configurable prefix bits (`params.cache_pre`). Cache entry: `dp_mask` (bitmask of tried groups), current group, last detection type, timestamp. Configurable TTL.

#### Fallback Logic

1. Mark current group as tried in `dp_mask`
2. Walk linked list of groups: skip tried ones, skip non-matching trigger types
3. If `AUTO_SORT`: promote successful strategies by reordering the list
4. Update cache, reconnect if allowed
5. No matching group: reset cache, drop connection

**RIPDPI integration:** Extends existing per-network policy memory with real-time per-destination rotation.

**Root required:** No | **Effort:** Medium

---

### 6. HTTP Header Manipulation Steps

Exact byte-level changes for HTTP-layer evasion:

| Step | Before | After | Byte Change |
|------|--------|-------|-------------|
| `hostcase` | `Host: example.com` | `host: example.com` | 0x48 -> 0x68 |
| `domcase` | `Host: test.com` | `Host: TeSt.cOm` | Even positions uppercased |
| `methodspace` | `GET / HTTP/1.1` | `GET  / HTTP/1.1` | Extra space/tab after method |
| `methodeol` | `GET / HTTP/1.1\r\n` | `\nGET / HTTP/1.1\r\n` | 0x0A prepended |
| `hostnospace` | `Host: example.com` | `Host:example.com` | Remove 0x20 after colon |
| `unixeol` | `...\r\n` | `...\n` | Remove 0x0D |
| `hostpad` | `Host: ...` | `X-Pad: <N bytes>\r\nHost: ...` | Dummy headers before Host |

Works because TSPU uses simplified HTTP parsers; real servers tolerate RFC 7230 deviations.

**Root required:** No | **Effort:** Low

---

### 7. SYN Data (TCP Fast Open Style)

```c
setsockopt(fd, IPPROTO_TCP, TCP_FASTOPEN_CONNECT, &1, sizeof(int));
connect(fd, addr, addrlen);  // SYN carries TFO cookie
send(fd, data, len, 0);      // data piggybacked on SYN
```

**Android support:** Requires kernel 4.11+ (`TCP_FASTOPEN_CONNECT`). Android 10+ (kernel 4.14+) = yes. Most ROMs ship with TFO enabled. Some Russian ISPs (Rostelecom, MTS) RST SYN+data packets. Implement as optional with automatic fallback.

**Root required:** No | **Effort:** Low

---

### 8. IPv6 Extension Header Injection

#### Packet Format

**Single hop-by-hop (`hopbyhop`):** Insert 8-byte extension header (Next Header = original, Hdr Ext Len = 0, 6 bytes padding). IPv6 Next Header = 0x00 (IPPROTO_HOPOPTS). Total added: 8 bytes.

**Double hop-by-hop (`hopbyhop2`):** Two 8-byte HBH headers chained (1st -> 2nd -> TCP). Violates RFC 8200 Section 4.1. Conforming OS kernels discard. Ideal as **fake packets**: DPI processes them (poisoning state), destination drops them. Total added: 16 bytes.

**Destination options (`destopt`):** Same as HBH but Next Header = 0x3C (IPPROTO_DSTOPTS). Combinable: `IPv6 -> HBH -> DSTOPT -> TCP`.

**Android VPN TUN:** Full control over IP packets -- can insert extension headers before writing to TUN fd.

**Root required:** VPN mode | **Effort:** Medium

---

### 17. TCP Timestamp Faking

Exploit the difference between how TSPU inspects timestamps and how the destination server validates them via PAWS (Protection Against Wrapped Sequence Numbers, RFC 7323).

#### Mechanism

Standard TCP TSOPT format in options:
```
Kind=8 | Len=10 | TSval(4B) | TSecr(4B)
```

Attack: fabricate a fake packet whose `TSval` is decreased by 600,000 relative to the current monotonic counter (~10 minutes behind for 100 Hz tick). The destination server rejects the fake via PAWS (outdated timestamp). TSPU processes the fake first and updates its state machine — then the real packet arrives with a valid timestamp and passes through.

```rust
// In fake packet builder, after locating TCP timestamp option:
let fake_tsval = real_tsval.wrapping_sub(600_000_u32);
buf[tsopt_offset..tsopt_offset + 4].copy_from_slice(&fake_tsval.to_be_bytes());
```

youtubeUnblock v1.3.0 (January 31, 2026) introduced this as `--faking-strategy=timestamp`. Recommended combo: `--fake-sni=1 --faking-strategy=tcp_check,timestamp`. Provided a breakthrough specifically for YouTube slowdown on Keenetic/OpenWrt routers after the `pastseq` ban (December 27, 2025).

#### Constraints

- Requires TCP Timestamps negotiated in SYN/SYN-ACK (not all connections use them; check `TCP_SAVESYNSYN` or inspect SYN-ACK options).
- Windows does not enable TCP Timestamps by default (`HKLM\...\Tcpip\Parameters\Tcp1323Opts = 0`) → unreliable for Windows-originating traffic routed through the app.
- No effect on QUIC (QUIC has its own ack delay mechanism, unrelated to TCP timestamps).

**Reference:** [youtubeUnblock v1.3.0 release notes](https://github.com/Waujito/youtubeUnblock/releases/tag/v1.3.0), [RFC 7323 Section 5 (PAWS)](https://datatracker.ietf.org/doc/html/rfc7323#section-5)

**Root required:** No | **Effort:** Low

---

### 18. ClientHello Clone for Fake Packets

Current RIPDPI fakes use static or template-generated TLS payloads. A more fingerprint-resistant approach: clone the real TLS ClientHello from the proxied client connection, apply targeted modifications, and send the clone as the fake.

#### Why Superior to Static Fakes

Static fakes with rndsni/dupsid differ from the real connection in TLS version, cipher suite order, extension set, and GREASE values. JA4+ and ML classifiers can distinguish them from real ClientHellos. A cloned fake inherits the exact fingerprint of the real connection.

#### Implementation Steps

```
1. Buffer incoming TLS records until full ClientHello received
   (may span multiple TLS record layer chunks if >16 KB)

2. Clone the ClientHello byte slice

3. Apply modifications to the clone:
   - SNI extension (type 0x0000): clear the hostname, zero-fill or remove
   - session_id: randomize (first 32 bytes after legacy_version)
   - Re-compute ClientHello.length fields (2 bytes each level)

4. Wrap clone in TLS Record layer: [0x16][0x03 0x01][len16][clone]

5. Send clone as fake: low TTL (auto_ttl - delta) or bad checksum

6. Send real ClientHello as the legitimate packet
```

#### ECH-Aware Variant

When the real ClientHello contains an ECH extension (0xFE0D), the clone should preserve the ECH outer SNI (`public_name`) but zero the inner encrypted payload — the outer SNI is what TSPU reads, the inner is what the server decrypts.

**Reference:** [zapret2 zapret-antidpi.lua `tls_client_hello_clone`](https://deepwiki.com/bol-van/zapret2/3.3-anti-dpi-strategy-library-(zapret-antidpi.lua))

**Root required:** No | **Effort:** Low

---

### 19. Double Fake Strategy

Send two fake packets before the real payload instead of one. Each fake uses a different invalidation method, saturating DPI state machines that may recover from a single malformed packet.

#### Variants

**Homogeneous double fake** (same invalidation, incremented sequence numbers):
```
fake1: bad TTL (auto_ttl - delta), seq = real_seq - 10000
fake2: bad TTL (auto_ttl - delta), seq = real_seq - 5000
real:  correct TTL, seq = real_seq
```

**Heterogeneous double fake** (complementary invalidation):
```
fake1: bad TTL, valid checksum     → defeats checksum-aware DPI that filters bad-checksum fakes
fake2: bad checksum, valid TTL     → defeats TTL-aware DPI that filters low-TTL fakes
real:  valid TTL + valid checksum
```

Flowseal zapret-discord-youtube added double fake to ALT/ALT4/ALT10/ALT11/SIMPLE FAKE strategies in v1.9.7 (February 23, 2026) and reported improved success rates on ISPs that adapted to single-fake strategies.

#### Implementation

Add an optional `fake_count: u8` parameter (default 1, max 3) to the `FakeStep` in the strategy chain. When `fake_count > 1`, the second fake's invalidation method is toggled (TTL→checksum or vice versa) to maximise coverage.

**Root required:** No | **Effort:** Low

---

## Tier 3: Proxy Protocol Support (P2)

### 9. VLESS + Reality Client

#### VLESS Wire Format

**Request:** `Version(1B) | UUID(16B) | AddonsLen(1B) | Addons(PB) | Cmd(1B) | Port(2B) | AddrType(1B) | Addr(var)`

- Version: `0x01`. UUID: sole auth token. Cmd: `0x01`=TCP, `0x02`=UDP. AddrType: `0x01`=IPv4, `0x02`=Domain, `0x03`=IPv6.
- Addons: ProtoBuf-encoded `Flow="xtls-rprx-vision"` (prevents TLS-in-TLS detection).
- Response: `Version(1B) | AddonsLen(1B) | Addons`. Then raw bidirectional data. Zero encryption -- transport handles it.

#### Reality TLS Mechanism

Server configured with `dest` (mask site, e.g., `www.microsoft.com`). Handshake:

1. Client embeds auth in ClientHello `session_id`: `[version(3B)][reserved(1B)][timestamp(4B)][shortID(8-16B)]`
2. Key derivation: `ECDH(client_private, server_public)` -> `HKDF-SHA256(shared_secret, ClientHello.random[20:], "REALITY")` -> AES-GCM encrypt first 16 bytes of SessionID
3. Non-Reality clients (censors probing): server transparently proxies to real `dest` -- perfect camouflage
4. Authenticated clients: temp ed25519 cert, verified via `HMAC-SHA512(auth_key, pubkey)`

Detection rate under 5%. **Rust implementation:** [shoes](https://github.com/cfal/shoes) crate (VLESS + Reality + Hysteria2 + TUIC).

**Min config:** `server`, `server_port`, `uuid`, `tls.reality.public_key`, `tls.reality.short_id`, `tls.server_name`

**Root required:** No | **Effort:** High

---

### 10. Hysteria2 with Salamander

#### Auth Flow (over QUIC)

Client HTTP/3 POST to `/auth` with `Hysteria-Auth: <password>`. Server returns status **233** on success (anything else = failure + serves real web page to defeat probing).

#### Wire Formats

**TCP:** Per-stream: `RequestID(varint=0x401) | AddrLen | Addr | PadLen | Pad` -> `Status(u8) | MsgLen | Msg | PadLen | Pad` -> raw data.

**UDP (QUIC datagrams):** `SessionID(u32) | PacketID(u16) | FragID(u8) | FragCount(u8) | AddrLen | Addr | Payload`. Built-in fragmentation for oversized packets.

#### Salamander Obfuscation

```
Wire: [Salt (8B)] [Obfuscated (NB)]
hash = BLAKE2b-256(key || salt)
obfuscated[i] = payload[i] XOR hash[i % 32]
```

Every packet appears as random bytes. No discernible QUIC headers. Confirmed working in Russia mid-2025.

**Reference:** [Hysteria2 spec](https://v2.hysteria.network/docs/developers/Protocol/), [hysteria](https://github.com/apernet/hysteria)

**Root required:** No | **Effort:** High

---

### 11. Chain Relay Support

```
Client -> Russian VPS (Yandex.Cloud/VK Cloud) -> Foreign VPS -> Internet
```

TSPU freezes sessions >15-20KB on international links but is lenient toward domestic traffic. Protocol per hop: VLESS+Reality on both. VLESS `xtls-rprx-vision` splices inner TLS, reducing double-encryption overhead to near-zero. Dominant cost is added RTT.

**Root required:** No | **Effort:** Medium

---

### 12. MASQUE Protocol (HTTP/3 + TCP Fallback)

RFC 9484 Connect-IP over QUIC. Extended CONNECT with `:protocol = connect-ip`. IP packets encapsulated in QUIC datagrams: `[QuarterStreamID][ContextID][IPPacket]`.

Cloudflare uses `cf-connect-ip` variant with ECDSA P-256 auth. Port 443 (blends with HTTPS). HTTP/2 TCP fallback when UDP blocked (WARP v2025.4): IP packets in HTTP/2 stream frames, head-of-line blocking but connectivity maintained.

**Reference:** [usque](https://github.com/Diniboy1123/usque), [connect-ip-go](https://github.com/quic-go/connect-ip-go)

**Root required:** No | **Effort:** Very High

---

### 20. TUIC v5 Protocol

QUIC-based proxy protocol. Lighter than Hysteria2: no HTTP/3 auth ceremony, UUID-authenticated, native QUIC stream multiplexing (no smux layer needed), configurable congestion control.

#### Wire Format (v5)

**Connect request (client → server, over QUIC stream):**
```
[Version(1)=5][Type(1)][Token(32)][UUID(16)][AddrType(1)][Addr(var)][Port(2)]

Type: 0x00 = TCP CONNECT | 0x01 = UDP ASSOCIATE | 0x02 = Dissociate | 0x03 = Heartbeat
```

**UDP packet (QUIC datagram frame):**
```
[AssocID(2)][PacketID(2)][FragTotal(1)][FragID(1)][Size(2)][AddrType(1)][Addr(var)][Port(2)][Payload]
```

Built-in UDP fragmentation for packets exceeding QUIC datagram MTU (~1200 bytes).

#### Authentication Token

```
token = HMAC-SHA256(password, unix_timestamp_secs / 8)[..32]
```

Timestamp window: ±4 seconds (server drift tolerance). Prevents replay within the 8-second epoch.

#### 0-RTT

After the first connection, QUIC session tickets enable 0-RTT for subsequent handshakes. The connect request can be sent immediately in the 0-RTT QUIC Initial, reducing cold-start latency by ~1 RTT.

#### Comparison with Hysteria2

| Aspect | TUIC v5 | Hysteria2 |
|--------|---------|-----------|
| Auth | UUID + HMAC token | HTTP/3 POST /auth (password) |
| Obfuscation | None (relies on QUIC being allowed) | Salamander (XOR + BLAKE2b) |
| Mux | Native QUIC streams | Separate smux over single stream |
| Congestion | BBR / Cubic / NewReno (configurable) | BBR mandatory |
| UDP | Native datagrams with built-in frag | Custom datagrams + Hysteria framing |
| Probing resistance | Low (QUIC headers readable) | High (Salamander = random bytes) |

TUIC is preferable where QUIC is not blocked and Hysteria2's obfuscation overhead is undesirable.

**Rust reference:** [shoes](https://github.com/cfal/shoes) — single Rust crate implementing VLESS + Reality + Hysteria2 + TUIC v5.

**Root required:** No | **Effort:** High

---

### 21. ShadowTLS v3

Camouflages proxy traffic as a legitimate TLS 1.3 handshake with a real website (e.g., `www.apple.com`). A censor observing the connection sees a complete, valid TLS handshake to Apple — not a proxy.

#### Protocol Flow

```
Client                  ShadowTLS Server           Real Destination
  |--- TLS ClientHello -------->|                        |
  |                             |--- TLS ClientHello --->|  (proxy to real site)
  |<-- TLS ServerHello ---------|<-- TLS ServerHello ----|
  |<-- Certificate -------------|<-- Certificate --------|
  |<-- Finished ----------------|<-- Finished -----------|
  |
  | [TLS handshake complete: censor satisfied]
  |
  |--- TLS Application Data [HMAC-tagged] -->|
  |   Server verifies HMAC → switches to tunnel mode
  |<-- [Proxied application data] -----------|
```

#### v3 Authentication (HMAC per-record)

After each TLS application record, ShadowTLS appends a 8-byte HMAC tag:

```
tag = HMAC-SHA256(derived_key, record_header || payload || seq_num_be64)[..8]
```

Key derivation:
```
pre_master = ECDH(client_ephemeral, server_static)
derived_key = HKDF-SHA256(pre_master, "shadow-tls v3", password)
```

Server verifies tag on every record. First valid tag triggers tunnel mode switch. Unrecognized clients (censors) receive real website data — active-probing safe.

#### Active Probing Resistance

ShadowTLS v3 is specifically hardened against active probing:
- No unique port signatures (listens on 443)
- Replays a real TLS session if HMAC fails consistently
- Sequence number prevents replay across sessions

**Rust reference:** [shadow-tls](https://github.com/ihciah/shadow-tls) — Rust client/server implementation

**Root required:** No | **Effort:** Medium

---

### 22. NaiveProxy

HTTP/2 CONNECT proxy where the client TLS fingerprint is indistinguishable from Chrome or Firefox. Unlike BoringSSL mimicry (which replicates cipher suites/extensions), NaiveProxy runs Chromium's actual `net/` stack.

#### What Makes It Undetectable

Chromium `net/` produces exact TLS fingerprints including:
- GREASE values (RFC 8701 — pseudo-random extension/cipher/group IDs)
- ML-KEM 768 key share (post-quantum, Chrome 131+)
- Exact extension ordering and parameters
- HTTP/2 SETTINGS frame values matching Chrome's defaults

JA4+ fingerprint: matches Chrome 133 exactly. No known classifier distinguishes NaiveProxy traffic from Chrome.

#### Wire Protocol

```
Client → Server: CONNECT target:port HTTP/2
  :method: CONNECT
  :authority: target:port
  proxy-authorization: basic <base64(user:pass)>
  padding: <random ASCII, 16-64 bytes>   ← anti-length-fingerprinting

Server → Client:
  :status: 200
  [bidirectional HTTP/2 stream for TCP tunneling]
```

HTTP/2 header compression (HPACK) hides the `proxy-authorization` header from plaintext observation.

#### Padding Layer

Client pads request headers and data frames with random bytes to prevent traffic-shape fingerprinting. Server strips padding. `AUTH_DATA` pseudo-header carries obfuscated credentials for servers that inspect headers.

#### Deployment Requirement

Server must run `caddy` with `forward_proxy` plugin (not NGINX/haproxy — Caddy preserves the HTTP/2 server fingerprint Chrome expects). Without matching server fingerprint, active probing reveals the proxy.

**Android:** No dedicated Android NaiveProxy client; available indirectly via Clash.Meta (`naive` outbound type). Native RIPDPI integration requires embedding Chromium `net/` via JNI — high effort, large binary size (+20 MB).

**Reference:** [naiveproxy](https://github.com/klzgrad/naiveproxy), [forwardproxy Caddy plugin](https://github.com/klzgrad/forwardproxy)

**Root required:** No | **Effort:** Very High

---

### 23. Multiplexed Connections (smux / yamux)

Without multiplexing, each proxied TCP connection requires a separate QUIC connection (or separate TCP stream to the relay server). For users with many concurrent tabs/requests, this means many individual QUIC handshakes — visible as a burst of connection-establishment events to DPI.

#### Architecture

```
Without mux:
  Tab 1 → QUIC connection #1 → relay → example.com
  Tab 2 → QUIC connection #2 → relay → another.com
  Tab 3 → QUIC connection #3 → relay → third.com

With smux over single QUIC stream:
  QUIC connection #1 (persistent)
  └── QUIC stream 0
      ├── smux frame sid=1 → TCP to example.com
      ├── smux frame sid=2 → TCP to another.com
      └── smux frame sid=3 → TCP to third.com
```

#### smux Frame Format (v1)

```
[Version(1)][Cmd(1)][Length(2)][StreamID(4)][Data(Length)]

Cmd: 0x00=SYN | 0x01=FIN | 0x02=PSH | 0x03=NOP
```

#### sing-box Implementation Reference

sing-box `multiplex` field for VLESS/Shadowsocks/Trojan outbounds:
```json
{
  "multiplex": {
    "enabled": true,
    "protocol": "smux",       // "smux" | "yamux" | "h2mux"
    "max_connections": 4,
    "min_streams": 4,
    "max_streams": 0,
    "padding": false,
    "brutal": { "enabled": false }
  }
}
```

#### Impact for RIPDPI

Relevant when VLESS (item 9) or Hysteria2 (item 10) relay is active and users open many parallel connections. Reduces: QUIC handshake CPU cost on relay server, observable connection-establishment burst, per-connection QUIC overhead (~1300 bytes min per Initial packet).

TUIC v5 (item 20) uses native QUIC stream multiplexing and does not require smux.

**Reference:** [xtaci/smux](https://github.com/xtaci/smux), [sing-box multiplex docs](https://sing-box.sagernet.org/configuration/shared/multiplex/)

**Root required:** No | **Effort:** Medium

---

## Tier 5: Android Platform Hardening (P1--P2)

### 24. Per-app UID Exclusion for VPN Detection Bypass

**Context (April 2026):** Apps implementing VPN detection via `ConnectivityManager.getNetworkCapabilities().hasTransport(TRANSPORT_VPN)` return `true` system-wide — this flag is set for the entire device when any VPN profile is active, regardless of per-app split tunneling. However, **server-side IP-based detection** (the most common vector for Wildberries/Ozon) *is* defeated by excluding the app from the VPN: the app's traffic bypasses the RIPDPI tunnel and uses the real ISP IP.

#### Android VpnService API

```kotlin
// In VpnService.Builder, before establish():

// Denylist mode — these apps bypass the VPN tunnel entirely
listOf(
    "com.wildberries.ru",           // IP-base detection
    "ru.ozon.app.android",          // IP-base + ConnectivityManager
    "ru.mail.group.superapp",       // MAX Messenger: ConnectivityManager
    "ru.vkusvill.android",          // IP-base detection
    "com.chocolatefactory.android", // IP-base detection (Шоколадница)
    "ru.yandex.taxi",               // geo-signal detection
).forEach { pkg ->
    try { builder.addDisallowedApplication(pkg) }
    catch (e: PackageManager.NameNotFoundException) { /* not installed, skip */ }
}
```

#### Detection Method Matrix

| App | TRANSPORT_VPN | IP-base | Geo-signal | Excluded app result |
|-----|:---:|:---:|:---:|---|
| Wildberries | No | Yes | No | ✅ Fully fixed |
| Ozon | Yes | Yes | No | ⚠️ IP fixed, API still true |
| MAX Messenger | Yes | No | No | ⚠️ API still true |
| ВкусВилл | No | Yes | No | ✅ Fully fixed |
| Шоколадница | No | Yes | No | ✅ Fully fixed |
| Yandex Taxi | No | No | Yes | ⚠️ Partial (depends on location) |

#### What Exclusion Does NOT Fix

`hasTransport(TRANSPORT_VPN)` returns `true` system-wide even for excluded apps. The only non-root approach to hide the VPN at OS level is Android Work Profile (separate profile with no VPN configured). RIPDPI should display this distinction clearly in UI.

#### Implementation Details

- Maintain `app-exclusion-list.json` (bundled, updateable via strategy packs, item 15):
  ```json
  {
    "version": 3,
    "entries": [
      {
        "package": "com.wildberries.ru",
        "name": "Wildberries",
        "detection_methods": ["ip_base"],
        "exclusion_fixes": ["ip_base"],
        "added": "2026-04-01"
      }
    ]
  }
  ```
- **UI:** "App Compatibility Mode" section in Settings → show list of detected installed apps from the exclusion list with toggle per-app and explanation of what is/isn't fixed.
- **Auto-detect:** On VPN start, scan installed packages against exclusion list → prompt user if known-detection apps found.
- **Limitation disclosure:** For apps with `TRANSPORT_VPN` detection (Ozon, MAX), show: "VPN presence cannot be fully hidden from this app without Work Profile."

**Root required:** No | **Effort:** Medium

---

## Tier 4: Detection Resistance (P3)

### 13. TLS Fingerprint Mimicry

JA4+ normalizes extensions into sorted order, defeating randomization.

**Rust options:** rustls refuses customization (#1932). [craftls](https://github.com/3andne/craftls) = rustls fork with `.with_fingerprint()` (CHROME_108, FIREFOX_105 -- lags behind). `rquest` = BoringSSL-backed HTTP client with Chrome/Firefox impersonation. **BoringSSL (`boring` crate, already in RIPDPI)** = most flexible, full ClientHello control. Go uTLS via FFI = most up-to-date (Chrome 133 with ML-KEM, Firefox 148).

**Root required:** No | **Effort:** High

---

### 14. Adaptive Traffic Morphing

Defeat ML classifiers by normalizing traffic statistics. TLS record padding (RFC 8446 Section 5.4, up to 2^14+256 bytes). Timing jitter (1--50ms Poisson-distributed). Target entropy: 7.5--7.95 bits/byte. RIPDPI foundation: `EntropyMode`, `RuntimeAdaptiveSettings`.

**Root required:** No | **Effort:** High

---

### 15. Updatable Strategy Packs

zapret2 uses Lua scripting: strategies as functions receiving `(ctx, desync)` with packet dissection + connection tracking. sing-box uses downloadable binary rule-sets with `update_interval`.

**Proposed RIPDPI format:**
```json
{
  "version": 2,
  "min_app_version": "0.0.5",
  "strategies": [{
    "id": "ru-tspu-tls-2026q1",
    "tcp_chain": [
      {"kind": "fake", "marker": "host+2", "ttl": 6},
      {"kind": "disorder", "marker": "host+2"}
    ],
    "triggers": ["torst", "tls_err"]
  }],
  "hostlists": {"blocked": "https://example.com/lists/ru-blocked.txt"}
}
```

**Root required:** No | **Effort:** Medium--High

---

### 16. QUIC Connection Migration Exploitation

QUIC connections identified by Connection IDs (encrypted in short header), not IP:port tuples. QUICstep (Princeton): handshake through proxy, data phase migrates to direct connection. Results: 84% latency reduction, 93% proxy load reduction, 12.8% of QUIC sites support migration.

Additional techniques: Connection ID rotation (`NEW_CONNECTION_ID` encrypted), version negotiation (unsupported version triggers ignore), fake datagram prepend, token field tampering.

**Reference:** [QUICstep paper](https://arxiv.org/html/2304.01073v2), [quic-censorship](https://github.com/kelmenhorst/quic-censorship/blob/main/evade.md)

**Root required:** VPN mode | **Effort:** High

---

## Summary Matrix

| # | Feature | Root? | Effort | Impact | Priority |
|---|---------|-------|--------|--------|----------|
| 1 | WARP WireGuard tunnel | No | High | Very High | P0 |
| 2 | AmneziaWG obfuscation | No | Medium | High | P0 |
| 3 | WARP domain hostlist | No | Low | Medium | P0 |
| 4 | WARP endpoint scanner | No | Medium | Medium | P1 |
| 5 | Auto-strategy fallback | No | Medium | High | P1 |
| 6 | HTTP header manipulation | No | Low | Medium | P1 |
| 7 | SYN data (TFO) | No | Low | Medium | P1 |
| 8 | IPv6 extension headers | VPN | Medium | Medium | P1 |
| 9 | VLESS + Reality client | No | High | Very High | P2 |
| 10 | Hysteria2 + Salamander | No | High | High | P2 |
| 11 | Chain relay support | No | Medium | High | P2 |
| 12 | MASQUE protocol | No | Very High | High | P2 |
| 13 | TLS fingerprint mimicry | No | High | High | P3 |
| 14 | Adaptive traffic morphing | No | High | High | P3 |
| 15 | Updatable strategy packs | No | Medium--High | High | P3 |
| 16 | QUIC connection migration | VPN | High | Medium | P3 |
| 17 | TCP timestamp faking | No | Low | High | P1 |
| 18 | ClientHello clone for fakes | No | Low | Medium | P1 |
| 19 | Double fake strategy | No | Low | Medium | P1 |
| 20 | TUIC v5 protocol | No | High | High | P2 |
| 21 | ShadowTLS v3 | No | Medium | High | P2 |
| 22 | NaiveProxy | No | Very High | Medium | P2 |
| 23 | Multiplexed connections (smux) | No | Medium | Medium | P2 |
| 24 | Per-app UID exclusion | No | Medium | High | P1 |

## Reference Projects

| Project | Language | Relevance |
|---------|----------|-----------|
| [wgcf](https://github.com/ViRb3/wgcf) | Go | WARP registration API |
| [warp-plus](https://github.com/bepass-org/warp-plus) | Go | Full WARP with endpoint scanning, TLS evasion |
| [wireproxy](https://github.com/pufferffish/wireproxy) | Go | WireGuard-as-SOCKS5 via gVisor netstack |
| [oblivion](https://github.com/bepass-org/oblivion) | Java/Go | Android WARP with VpnService + tun2socks |
| [WG Tunnel](https://github.com/wgtunnel/wgtunnel) | Kotlin/Go | Android WireGuard + AmneziaWG |
| [usque](https://github.com/Diniboy1123/usque) | Go | MASQUE reimplementation |
| [amneziawg-go](https://github.com/amnezia-vpn/amneziawg-go) | Go | AmneziaWG implementation |
| [ByeDPI](https://github.com/hufrea/byedpi) | C | DPI desync (auto-mode, trigger system) |
| [sing-box](https://github.com/SagerNet/sing-box) | Go | VLESS/Hysteria2/TUIC, rule-set system, smux multiplex |
| [hysteria](https://github.com/apernet/hysteria) | Go | Hysteria2 + Salamander |
| [shoes](https://github.com/cfal/shoes) | Rust | VLESS + Reality + Hysteria2 + TUIC v5 |
| [uTLS](https://github.com/refraction-networking/utls) | Go | TLS fingerprint mimicry |
| [craftls](https://github.com/3andne/craftls) | Rust | rustls fork with fingerprint API |
| [connect-ip-go](https://github.com/quic-go/connect-ip-go) | Go | RFC 9484 Connect-IP |
| [youtubeUnblock](https://github.com/Waujito/youtubeUnblock) | C | TCP timestamp faking (v1.3.0), SNI fake strategies |
| [shadow-tls](https://github.com/ihciah/shadow-tls) | Rust | ShadowTLS v3 client/server |
| [naiveproxy](https://github.com/klzgrad/naiveproxy) | C++ | HTTP/2 CONNECT with Chrome TLS fingerprint |
| [forwardproxy](https://github.com/klzgrad/forwardproxy) | Go | Caddy plugin for NaiveProxy server |
| [xtaci/smux](https://github.com/xtaci/smux) | Go | smux stream multiplexing reference |
| [zapret2](https://github.com/bol-van/zapret2) | C + Lua | tls_client_hello_clone, double fake, Lua strategy engine |
| [flowseal/zapret-discord-youtube](https://github.com/Flowseal/zapret-discord-youtube) | BAT | Double fake strategy (ALT variants, v1.9.7) |

## Sources

- Zapret community forum (evgen-dev.ddns.net) -- Cloudflare WARP thread, 64+ posts
- NTC party forum (ntc.party) -- bypass methods 2025--2026 discussions, thread 22584 (MAX surveillance), thread 22880 (MTS VPN detection), thread 21161 (zapret2 discussion, 805+ replies)
- net4people/bbs (GitHub) -- ECH blocking (#417), TSPU new methods (#490), mobile whitelists (#516)
- Cloudflare blog -- MASQUE architecture, WARP protocol details, Zero Trust WARP
- USENIX Security 2025 -- QUIC censorship evasion paper
- QUICstep paper (Princeton/U. Michigan) -- QUIC connection migration exploitation
- ByeDPI source (hufrea/byedpi) -- `extend.c`, `params.h`, `desync.c`, `mpool.h`
- Zapret source (bol-van/zapret) -- `nfq/darkmagic.c`, `nfq/desync.c`
- Zapret2 (bol-van/zapret2) -- Lua strategy engine, zapret-antidpi.lua (tls_client_hello_clone, position markers)
- youtubeUnblock v1.3.0 release notes -- TCP timestamp faking strategy, Aho-Corasick SNI matching
- Flowseal zapret-discord-youtube v1.9.7 -- double fake strategy addition (ALT/SIMPLE FAKE variants)
- Hysteria2 protocol spec -- v2.hysteria.network
- TUIC v5 protocol spec -- github.com/EAimTY/tuic
- ShadowTLS v3 spec -- github.com/ihciah/shadow-tls
- VLESS protocol spec (Project X) -- xtls.github.io
- Reality source analysis -- objshadow.pages.dev
- AmneziaWG 2.0 spec -- docs.amnezia.org
- Habr articles 1021160, 1021392 -- 4-layer whitelist bypass architecture, app-level VPN detection
- Mintsifry VPN detection technical guide (April 2026) -- 3-stage detection: IP-base → ConnectivityManager → geo-signal
- Android ConnectivityManager API docs -- TRANSPORT_VPN system-wide flag, VpnService builder exclusion API
- RFC 9484 (Connect-IP), RFC 9000 (QUIC), RFC 8446 (TLS 1.3), RFC 8200 (IPv6), RFC 7323 (TCP Timestamps / PAWS)
