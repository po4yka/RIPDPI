# Integrations and Techniques Roadmap

Prioritized list of integrations and path optimization techniques for RIPDPI, based on deep research across the Zapret community, NTC forums, GitHub projects, Cloudflare documentation, protocol specifications, and academic papers (intelligence window: April 2025 -- April 10, 2026).

## Current Threat Landscape

Russian middlebox (technical censorship infrastructure) capabilities as of mid-2025:

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
- **App-level VPN detection (April 2026)**: Mintsifry published 3-step technical guide distributed to 20+ platforms (Yandex, VK, Sber, Ozon, Wildberries, Avito, X5, HeadHunter). Three required detection steps: (1) IP database check against VPN/datacenter ASNs via MaxMind GeoIP2/IP2Proxy; (2) parallel HTTP requests to Russian + foreign domains to detect split tunneling; (3) OS fingerprint + GPS cross-reference for corporate users. Deadline: April 15, 2026. Primary vector: Android `ConnectivityManager.getNetworkCapabilities().hasTransport(TRANSPORT_VPN)` — system-wide flag, requires no permissions, unaffected by per-app split tunneling. Wildberries, Ozon, MAX Messenger, ВкусВилл, Шоколадница implementing.
- **April 1, 2026 mass VPN drop event**: Coordinated middlebox rules update at 13:00 MSK — synchronized VPN connection drops observed across MTS, Beeline, Tele2. OpenVPN, WireGuard, L2TP/IPSec, PPTP fully blocked as of late March 2026. AmneziaWG survives as UDP obfuscation layer.
- **Telegram blocked April 4, 2026**: FSB opened criminal investigation against Durov. middlebox blocked Telegram via TLS handshake fingerprinting — non-standard ECH extension codepoint `0xFE02` (standard is `0xFE0D`) was detection vector. Fixed in Telegram Desktop 6.7.2 / Android 12.6.4 (~April 4–6). JA3 hash: `f07cc269d9323c428b7297219bed6754`.
- **ntc.party accessibility**: A-record removed from DNS ~April 2026. Workaround: direct IP `130.255.77.28` via `/etc/hosts`, or Tor/VPN. GitHub discussion: bol-van/zapret#1703.
- **xray-core v26.3.27 (March 27, 2026)**: Major release — Finalmask obfuscation framework (header-custom/Sudoku/fragment/noise), XHTTP/3 with BBR + udpHop, Hysteria2 inbound + Salamander, WireGuard FullCone NAT + Finalmask, REALITY warnings for Apple/iCloud SNI targets.
- **sing-box v1.13.7 stable + v1.14.0-alpha.10 (April 10, 2026)**: `cloudflared` inbound (Cloudflare Tunnel as bypass transport), `package_name_regex` route rules, `evaluate` DNS action, Hysteria2 BBR + hop interval randomization (alpha.8).

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

Mobile vs WiFi endpoints differ (different ISP routing, different middlebox rules). Integrates with RIPDPI's per-network policy memory and network handover detection.

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

Works because middlebox uses simplified HTTP parsers; real servers tolerate RFC 7230 deviations.

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

Exploit the difference between how middlebox inspects timestamps and how the destination server validates them via PAWS (Protection Against Wrapped Sequence Numbers, RFC 7323).

#### Mechanism

Standard TCP TSOPT format in options:
```
Kind=8 | Len=10 | TSval(4B) | TSecr(4B)
```

Attack: fabricate a fake packet whose `TSval` is decreased by 600,000 relative to the current monotonic counter (~10 minutes behind for 100 Hz tick). The destination server rejects the fake via PAWS (outdated timestamp). middlebox processes the fake first and updates its state machine — then the real packet arrives with a valid timestamp and passes through.

```rust
// In fake packet builder, after locating TCP timestamp option:
let fake_tsval = real_tsval.wrapping_sub(600_000_u32);
buf[tsopt_offset..tsopt_offset + 4].copy_from_slice(&fake_tsval.to_be_bytes());
```

youtubeUnblock v1.3.0 (January 31, 2026) introduced this as `--faking-strategy=timestamp`. Added because `pastseq` was banned by middlebox on 2024-12-27, and `tcp_check` alone fails on Cloudflare IPs (Cloudflare ignores bad checksums). Recommended: `--fake-sni=1 --faking-strategy=tcp_check,timestamp`.

#### Exact Implementation (youtubeUnblock `src/utils.c` + `config.h`)

Strategy bitmask (strategies are OR-combined, applied sequentially to the same fake packet):

```c
#define FAKE_STRAT_RAND_SEQ   (1 << 0)  // out-of-ack_seq sequence number
#define FAKE_STRAT_TTL        (1 << 1)  // fake TTL dies before server
#define FAKE_STRAT_PAST_SEQ   (1 << 2)  // BANNED by middlebox (2024-12-27)
#define FAKE_STRAT_TCP_CHECK  (1 << 3)  // corrupt TCP checksum +1 (applied last)
#define FAKE_STRAT_TCP_MD5SUM (1 << 4)  // inject invalid TCP MD5 option (kind=19)
#define FAKE_STRAT_TCP_TS     (1 << 6)  // decrease TSval by N ticks
// Default since v1.3.0:
#define FAKING_STRATEGY (FAKE_STRAT_TCP_CHECK | FAKE_STRAT_TCP_TS)
#define FAKING_TIMESTAMP_DECREASE_TTL 600000  // ~600 s at 1 kHz clock
```

TSOPT location walk + modification (from `fail_packet()`):

```c
// Walk TCP options to find kind=8 (TSOPT, len=10):
while (optp_len && *optp != 0x00) {
    if (*optp == 0x01) { optp_len--; optp++; continue; }  // NOP: skip 1 byte
    if (optp_len < 2) break;
    if (*optp == 0x08) { tcp_ts = optp; break; }           // found TSOPT
    optp_len -= optp[1]; optp += optp[1];
}
if (tcp_ts) {
    struct tcp_ts_opt *ts_opt = (void *)tcp_ts;
    // TSval at offset +2, big-endian uint32 — decrease by configured amount
    ts_opt->ts_val = htonl(ntohl(ts_opt->ts_val) - strategy.faking_timestamp_decrease);
}
// FAKE_STRAT_TCP_CHECK is applied after all other mods:
if (CHECK_BITFIELD(strategy.strategy, FAKE_STRAT_TCP_CHECK))
    tcph->th_sum = htons(ntohs(tcph->th_sum) + 1);  // intentional checksum break
```

Fake is sent first via `rawsend`, then the real packet follows (optionally fragmented). Fake SNI payload is a hardcoded 680-byte TLS ClientHello with SNI `www.google.com`.

#### CLI Reference

```
--faking-strategy=tcp_check,timestamp   # recommended default since v1.3.0
--faking-timestamp-decrease=600000      # TSval decrease in ticks (default)
--fake-sni=1                            # send fake TLS ClientHello first
--frag-origin-retries=2                 # retry count on fragmentation failure (v1.3.1+)
```

#### Constraints

- Requires `TCP_TIMESTAMPS` negotiated in SYN/SYN-ACK; if absent, modification silently skipped.
- Windows: `Tcp1323Opts=0` by default → timestamps absent → strategy has no effect.
- No effect on QUIC (QUIC uses its own ack delay, not TCP PAWS).
- Designed for Cloudflare-CDN YouTube IPs; effectiveness varies by ISP DPI vendor.

**Reference:** [youtubeUnblock v1.3.0](https://github.com/Waujito/youtubeUnblock/releases/tag/v1.3.0), [RFC 7323 §5 PAWS](https://datatracker.ietf.org/doc/html/rfc7323#section-5)

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

#### TLS ClientHello Byte Map (for Rust implementation)

```
TLS Record header (5 bytes):
  +0:    record type  = 0x16 (Handshake)
  +1..2: version      = 0x03 0x01 (TLS 1.0 compat)
  +3..4: record length (u16 BE)

Handshake header (4 bytes at +5):
  +5:    handshake type = 0x01 (ClientHello)
  +6..8: handshake length (u24 BE)

ClientHello body (at +9):
  +9..10:   legacy_version (0x03 0x03 = TLS 1.2 compat)
  +11..42:  random (32 bytes)                 ← RND: fill with random
  +43:      session_id length (u8, 0–32)
  +44..N:   session_id (session_id_len bytes) ← DUPSID: copy from real CH here
  After session_id:
    cipher_suites_len (u16 BE)
    cipher_suites (cipher_suites_len bytes)
    compression_len (u8)
    compression_methods (compression_len bytes)
    extensions_total_len (u16 BE)            ← PADENCAP: add payload_len here
    extensions list:
      each extension: type(2) + len(2) + data(len)
      SNI extension type = 0x0000            ← sni_del_ext: remove this entry
      Padding extension type = 0x0015        ← PADENCAP: add payload_len to its len field
```

#### Runtime `tls_mod` Operations (from zapret2 desync.c `runtime_tls_mod`)

| Flag | Constant | What it modifies |
|------|----------|-----------------|
| `rnd` | `FAKE_TLS_MOD_RND` | `random[11..42]` + `session_id[44..44+len]` — fill with random bytes |
| `dupsid` | `FAKE_TLS_MOD_DUP_SID` | Copy `payload[44..44+session_id_len]` into fake (requires same session_id_len) |
| `rndsni` | `FAKE_TLS_MOD_RND_SNI` | Replace SNI hostname bytes in fake with random bytes of same length |
| `padencap` | `FAKE_TLS_MOD_PADENCAP` | Add `payload_len` to TLS record len (+3), handshake len (+6), extensions total len, padding ext len — makes fake appear same size as real CH |

`padencap` requires a TLS padding extension (0x0015) to already be present in the fake blob. It expands that extension's length field, not the actual data — the fake is the same byte count as the hardcoded blob but the length fields claim it's larger.

#### Reassembly Integration

When ClientHello spans multiple TCP segments, `reasm_data` holds the complete reassembled payload. `dupsid` and `tls_client_hello_clone` must use `reasm_data` (not first-segment payload) to get the full session_id:

```lua
local data = desync.reasm_data or desync.dis.payload
-- tls_client_hello_clone stores result in desync[blob_name]
-- fake() checks replay_first(desync) to avoid acting on replay duplicates
```

#### ECH-Aware Variant

When the real ClientHello contains an ECH extension (0xFE0D), preserve the outer SNI (`public_name`) but zero the inner encrypted payload — outer SNI is what middlebox reads, inner is what the real server decrypts.

#### Full Function Catalog (zapret-antidpi.lua)

| Lua function | nfqws1 equivalent | Description |
|---|---|---|
| `drop` | `--drop` | Drop the intercepted packet |
| `send` | `--dup` | Duplicate and send with fooling |
| `pktmod` | (in-place) | Modify current packet, return MODIFY verdict |
| `http_domcase` | `--domcase` | Alternate case in HTTP `Host:` value |
| `http_hostcase` | `--hostcase` | Change `Host:` → `host:` header name |
| `http_methodeol` | `--methodeol` | Prepend `\n` before HTTP method line |
| `http_unixeol` | `--unixeol` | Convert `\r\n` → `\n` in HTTP headers |
| `syndata` | `--dpi-desync=syndata` | Send fake data payload inside SYN packet |
| `rst` | `--dpi-desync=rst/rstack` | Inject RST or RST+ACK to poison DPI state |
| `fake` | `--dpi-desync=fake` | Send fake payload(s) with low TTL / bad checksum |
| `multisplit` | `--dpi-desync=multisplit` | Split TCP payload at named position markers |
| `multidisorder` | `--dpi-desync=multidisorder` | Split + send segments in reverse order |
| `hostfakesplit` | `--dpi-desync=hostfakesplit` | Split at HTTP `Host` with fake hostname |
| `tls_client_hello_clone` | (new in zapret2) | Clone real ClientHello → modify SNI/session_id → store as blob for `fake` |

**Reference:** [zapret2 zapret-antidpi.lua](https://github.com/bol-van/zapret2), [zapret2 DeepWiki strategy library](https://deepwiki.com/bol-van/zapret2/3.3-anti-dpi-strategy-library-(zapret-antidpi.lua))

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

#### How the Engine Sends Multiple Fakes (zapret2 `desync.c` DESYNC_FAKE case)

Multiple fakes are implemented as a **linked list of blob items** iterated in the `DESYNC_FAKE` case. Each `--dpi-desync-fake-tls=<file>` argument appends one blob to the list:

```c
LIST_FOREACH(fake_item, fake, next) {
    // Apply tls_mod to this blob (rnd/dupsid/rndsni/padencap)
    if (l7proto == TLS && runtime_tls_mod(n, modcache, tls_mod,
            fake_item->data, fake_item->size,
            rdata_payload, rlen_payload, fake_data_buf)) {
        fake_data = fake_data_buf;
    } else {
        fake_data = fake_item->data;
    }
    // Build and send fake TCP packet:
    prepare_tcp_segment(..., htonl(sequence), DF, ttl_fake, ...,
        dp->desync_fooling_mode, dp->desync_ts_increment,
        dp->desync_badseq_increment, fake_data, fake_size, pkt1, &pkt1_len);
    rawsend_rep(dp->desync_repeats, &dst, desync_fwmark, ifout, pkt1, pkt1_len);
    // Optionally advance sequence number per fake:
    if (dp->tcp_mod.seq) sequence += fake_size;
}
```

All fakes share: same `ttl_fake`, same `desync_fooling_mode`, same `desync_badseq_increment`. They differ only in **payload blob**.

#### Exact Flowseal Configurations (v1.9.7)

**ALT strategy** (ts fooling, 2 fake blobs):
```bat
--dpi-desync=fake,fakedsplit --dpi-desync-repeats=6
--dpi-desync-fooling=ts
--dpi-desync-fake-tls="%BIN%stun.bin"
--dpi-desync-fake-tls="%BIN%tls_clienthello_www_google_com.bin"
```

**ALT10 strategy** (2 blobs, 4pda instead of google):
```bat
--dpi-desync=fake --dpi-desync-repeats=6 --dpi-desync-fooling=ts
--dpi-desync-fake-tls="%BIN%stun.bin"
--dpi-desync-fake-tls="%BIN%tls_clienthello_4pda_to.bin"
```

**SIMPLE FAKE ALT2** (badseq fooling):
```bat
--dpi-desync=fake --dpi-desync-repeats=6
--dpi-desync-fooling=badseq --dpi-desync-badseq-increment=2
--dpi-desync-fake-tls="%BIN%stun.bin"
--dpi-desync-fake-tls="%BIN%tls_clienthello_www_google_com.bin"
```

Fake blob types used: `stun.bin` (STUN binding request ≈ UDP-like payload), google/4pda/max.ru TLS ClientHello captures.

#### Fooling Modes (`darkmagic.h`)

| Flag | Value | Effect |
|------|-------|--------|
| `FOOL_MD5SIG` | 0x01 | TCP MD5 option (kind=19) — kernel drops on receive |
| `FOOL_BADSUM` | 0x02 | Corrupt L4 checksum |
| `FOOL_TS` | 0x04 | Add random/corrupt TCP timestamp value |
| `FOOL_BADSEQ` | 0x08 | Add `badseq_increment` to `th_seq`, `badseq_ack_increment` to `th_ack` |
| `FOOL_HOPBYHOP` | 0x10 | Add IPv6 hop-by-hop extension header |
| `FOOL_HOPBYHOP2` | 0x20 | Second IPv6 hop-by-hop header (RFC violation — drops at destination) |
| `FOOL_DESTOPT` | 0x40 | IPv6 destination options header |
| `FOOL_IPFRAG1` | 0x80 | IPv6 fragmentation header on first fragment |
| `FOOL_DATANOACK` | 0x100 | Send without ACK flag set |

#### RIPDPI Implementation

Store fakes as `Vec<Bytes>` per desync profile. For each blob in the list:
1. Apply runtime mods (rnd/dupsid/rndsni/padencap)
2. Construct TCP packet: seq=`original_seq` (shared), TTL=`fake_ttl`, fooling applied
3. Send `desync_repeats` times via raw socket
4. Then send the real packet (fragmented or whole)

`seq_advance=true` shifts sequence number by each fake's payload size — use when DPI tracks sequence space; off by default.

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

#### sing-box Hop Interval Randomization (alpha.8, March 30 2026)

sing-box v1.14.0-alpha.8 added **BBR profile + hop interval randomization** for Hysteria2, directly countering middlebox UDP pattern detection:

```json
{
  "type": "hysteria2",
  "server": "relay.example.com",
  "server_port": 443,
  "password": "your-password",
  "obfs": {
    "type": "salamander",
    "password": "obfs-password"
  },
  "congestion_control": "bbr",
  "hop_interval": "30s",
  "hop_ports": "20000-50000"
}
```

`hop_interval` randomizes which UDP port is used every N seconds, making traffic correlation across time windows harder. middlebox detects persistent UDP flows to foreign IPs — port hopping breaks the flow signature.

**Reference:** [Hysteria2 spec](https://v2.hysteria.network/docs/developers/Protocol/), [hysteria](https://github.com/apernet/hysteria), [sing-box v1.14.0-alpha.8](https://github.com/SagerNet/sing-box/releases/tag/v1.14.0-alpha.8)

**Root required:** No | **Effort:** High

---

### 11. Chain Relay Support

```
Client -> Russian VPS (Yandex.Cloud/VK Cloud) -> Foreign VPS -> Internet
```

middlebox freezes sessions >15-20KB on international links but is lenient toward domestic traffic. Protocol per hop: VLESS+Reality on both. VLESS `xtls-rprx-vision` splices inner TLS, reducing double-encryption overhead to near-zero. Dominant cost is added RTT.

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

#### Wire Format (v5) — All 5 Command Types

**Authenticate (client → server, first bidirectional stream after QUIC handshake):**
```
[Version(1)=5][Type(1)=0x00][Token(32)]
```

**Connect (TCP proxy, client opens new QUIC stream per connection):**
```
[Version(1)=5][Type(1)=0x01][UUID(16)][AddrType(1)][Addr(var)][Port(2)]
AddrType: 0xFF=Domain(1B len + bytes) | 0x00=IPv4(4B) | 0x01=IPv6(16B)
```

**Packet (UDP ASSOCIATE, QUIC unreliable datagram frame):**
```
[AssocID(2)][PacketID(2)][FragTotal(1)][FragID(1)][Size(2)][AddrType(1)][Addr(var)][Port(2)][Payload]
```
Built-in UDP fragmentation: `FragID=0..FragTotal-1`; receiver reassembles by `(AssocID, PacketID)`. Handles packets exceeding QUIC datagram MTU (~1200 bytes).

**Dissociate (cancel UDP session):**
```
[Version(1)=5][Type(1)=0x03][AssocID(2)]
```

**Heartbeat (bidirectional keepalive, prevents QUIC idle timeout):**
```
[Version(1)=5][Type(1)=0x04]
```

#### Authentication Token (TLS Keying Material Exporter, RFC 5705)

TUIC v5 does **not** use HMAC over a timestamp. The 32-byte token is derived via the **TLS Keying Material Exporter** — tying the token cryptographically to the specific TLS session, making it non-replayable:

```rust
// After TLS handshake completes on the QUIC connection:
let token: [u8; 32] = tls_conn.export_keying_material(
    /* label   */ uuid.as_bytes(),          // 16-byte UUID as label
    /* context */ Some(password.as_bytes()),
    /* length  */ 32,
).expect("TLS KME failed");

// Send Authenticate command:
stream.write_all(&[5, 0x00]).await?;        // Version=5, Type=Authenticate
stream.write_all(&token).await?;            // 32-byte session-bound token
```

Server calls `ExportKeyingMaterial` with the same parameters and compares. The token is unique per TLS session — capturing it from one connection cannot authenticate another. No timestamp needed; QUIC's built-in anti-replay handles freshness.

**Rust:** `rustls::ClientConnection::export_keying_material()` (rustls 0.23+)

#### Congestion Control (quinn)

```rust
use quinn::congestion::{BbrConfig, CubicConfig};

let mut transport = TransportConfig::default();

// BBR — recommended for high-latency/jitter links (GPON 30–80ms jitter):
transport.congestion_controller_factory(Arc::new(BbrConfig::default()));

// Cubic — better under middlebox token-bucket rate limiting:
transport.congestion_controller_factory(Arc::new(CubicConfig::default()));

let mut server_config = ServerConfig::with_crypto(Arc::new(tls));
server_config.transport_config(Arc::new(transport));
```

BBR3 (Google's latest) available via `quinn-bbr3` community crate. Choose BBR for high-RTT links; Cubic for steady-state throughput under ISP shaping.

#### 0-RTT

```
1st conn:  Full QUIC+TLS 1.3 handshake (1–2 RTT) → server issues session ticket
Reconnect: Client sends 0-RTT QUIC Initial → Authenticate + Connect in same flight
           → server validates KME token from 0-RTT data → tunnel ready in 0 RTT
```
QUIC `MAX_EARLY_DATA_SIZE` prevents 0-RTT replay at the transport layer.

#### Comparison with Hysteria2

| Aspect | TUIC v5 | Hysteria2 |
|--------|---------|-----------|
| Auth | UUID + TLS KME token (session-bound) | HTTP/3 POST /auth (password) |
| Obfuscation | None (QUIC must not be blocked) | Salamander (XOR + BLAKE2b, opaque bytes) |
| Mux | Native QUIC streams (per-connection) | Separate smux over single QUIC stream |
| Congestion | BBR / Cubic (configurable) | BBR mandatory |
| UDP | Native datagrams with built-in frag | Custom datagrams + Hysteria framing |
| Probing resistance | Low (QUIC headers visible) | High (Salamander = random bytes) |

TUIC is preferable where QUIC is not blocked and Hysteria2's obfuscation overhead is undesirable. Under middlebox QUIC blocking (packets >1001 bytes), neither works without QUIC fragmentation (item 5).

**Rust reference:** [tuic](https://github.com/EAimTY/tuic) — reference impl; [shoes](https://github.com/cfal/shoes) — multi-protocol crate (VLESS + Reality + Hysteria2 + TUIC v5).
**Spec:** [TUIC v5 protocol spec](https://github.com/EAimTY/tuic/blob/dev/SPEC.md)

**Root required:** No | **Effort:** High

---

### 21. ShadowTLS v3

Camouflages proxy traffic as a legitimate TLS 1.3 handshake with a real website (e.g., `www.apple.com`). A censor observing the connection sees a complete, valid TLS handshake to Apple — not a proxy.

#### Protocol Flow (6 Stages)

```
Stage 1  Client → ShadowTLS: TLS ClientHello (standard, no ShadowTLS marker)
Stage 2  ShadowTLS → Real server: forwards ClientHello verbatim
Stage 3  Real server → ShadowTLS → Client: ServerHello + Certificate + Finished
         [ShadowTLS records ServerRandom from ServerHello for key derivation]
Stage 4  Client → ShadowTLS: TLS Finished (ends legitimate handshake)
         Client embeds a 4-byte HMAC-SHA1 tag in the SessionID field:
           SessionID = [28 random bytes] + HMAC-SHA1(password, ServerRandom)[..4]
Stage 5  ShadowTLS verifies the 4-byte tag using ServerRandom it captured in Stage 3.
         - Tag matches → authenticated client, switch to tunnel mode (Stage 6)
         - Tag fails  → forward all data to real server (active-probing safe)
Stage 6  Data phase: client sends inner proxy data (e.g. shadowsocks AEAD) wrapped
         in TLS Application Data records with a 4-byte chained HMAC prefix per record
```

#### v3 Authentication — Exact HMAC Construction

**Handshake authentication (SessionID tag):**
```
# Three HMAC-SHA1 instances, all keyed with ServerRandom:
hmac_sr   = HMAC-SHA1(key=password, msg=ServerRandom)          # base
hmac_sr_c = HMAC-SHA1(key=password, msg=ServerRandom || "C")   # client direction
hmac_sr_s = HMAC-SHA1(key=password, msg=ServerRandom || "S")   # server direction

# SessionID embedded in client's TLS Finished:
SessionID = random_bytes(28) + hmac_sr.digest()[:4]            # 32 bytes total
```

**Data phase per-record authentication (chained HMAC):**
```
# XOR mask for entire record (hides inner proxy data from TLS layer):
mask = SHA256(password || ServerRandom)   # 32 bytes, reused across records

# Each TLS Application Data record:
record_header = [content_type(1)][legacy_version(2)][length(2)]  # 5 bytes
hmac_prefix   = HMAC-SHA1(hmac_sr_c, prev_hmac_prefix || payload)[:4]  # chained
wire_record   = record_header + hmac_prefix + XOR(payload, mask[:len(payload)])
```

The 4-byte HMAC prefix chains across records (input includes previous prefix) — prevents record reordering and replay within a session. ShadowTLS does **not** add encryption; the inner protocol (e.g., shadowsocks AEAD) provides confidentiality.

#### Key Material Summary

| Variable | Algorithm | Purpose |
|----------|-----------|---------|
| `hmac_sr` | HMAC-SHA1(pw, ServerRandom) | Base HMAC, 20 bytes |
| `hmac_sr_c` | HMAC-SHA1(pw, ServerRandom\|\|"C") | Client→server record auth |
| `hmac_sr_s` | HMAC-SHA1(pw, ServerRandom\|\|"S") | Server→client record auth |
| `mask` | SHA256(pw\|\|ServerRandom) | XOR obfuscation key, 32 bytes |
| SessionID tag | `hmac_sr[:4]` | 4-byte handshake auth in SessionID |

**Rust implementation key:**
```rust
// shadow-tls crate (ihciah/shadow-tls):
use hmac::{Hmac, Mac};
use sha1::Sha1;

type HmacSha1 = Hmac<Sha1>;
let mut mac = HmacSha1::new_from_slice(password.as_bytes()).unwrap();
mac.update(server_random);
let hmac_sr = mac.finalize().into_bytes();  // 20 bytes
let tag = &hmac_sr[..4];                   // 4-byte SessionID suffix
```

#### Active Probing Resistance

- No unique port signatures (standard 443)
- Failed HMAC → all data forwarded to real destination transparently
- SessionID tag changes per-session (ServerRandom is fresh each handshake)
- No static fingerprint: outer TLS is from a real server's certificate chain

#### Detection Vectors

| Vector | Mitigated? | Notes |
|--------|------------|-------|
| JA3 fingerprint | Yes | Client's own TLS stack used |
| Certificate validity | Yes | Real cert from real server |
| Active probing (replay) | Yes | ServerRandom changes each session |
| Traffic shape (data phase) | Partial | Depends on inner protocol padding |
| Timing correlation | No | Not addressed by ShadowTLS |

**Rust reference:** [shadow-tls](https://github.com/ihciah/shadow-tls) — Rust client/server reference implementation
**Spec:** [ShadowTLS v3 protocol design](https://github.com/ihciah/shadow-tls/blob/master/docs/protocol-en.md)

**Root required:** No | **Effort:** Medium

---

### 22. NaiveProxy

HTTP/2 CONNECT proxy where the client TLS fingerprint is indistinguishable from Chrome or Firefox. Unlike BoringSSL mimicry (which replicates cipher suites/extensions), NaiveProxy runs Chromium's actual `net/` stack.

#### What Makes It Undetectable

Chromium `net/` (Chrome **147.0.7727.49**, as of April 2026) produces exact TLS fingerprints:
- GREASE values (RFC 8701 — pseudo-random extension/cipher/group IDs per session)
- ML-KEM 768 key share (post-quantum, Chrome 131+)
- Exact extension ordering and parameters matching Chrome build
- HTTP/2 SETTINGS frame values: `HEADER_TABLE_SIZE=65536`, `ENABLE_PUSH=0`, `INITIAL_WINDOW_SIZE=6291456`, `MAX_HEADER_LIST_SIZE=262144`

JA4+ fingerprint: matches Chrome 147 exactly. No known classifier distinguishes NaiveProxy traffic from real Chrome browsing.

#### Wire Protocol

**CONNECT request (client → server, HTTP/2 stream):**
```
:method: CONNECT
:authority: target.host:443
:scheme: https
proxy-authorization: basic <base64(user:pass)>
padding: <non-Huffman random ASCII, 16–32 bytes>   ← header size anti-fingerprinting
```

**HPACK note:** `proxy-authorization` value is HPACK-compressed; wire bytes show only Huffman-coded opaque bytes, not plaintext credentials.

```
Server → Client:
  :status: 200
  [bidirectional HTTP/2 DATA frames — raw TCP tunnel]
```

#### Payload Padding Protocol (`kFirstPaddings = 8`)

NaiveProxy pads the **first 8 DATA frames** (constant `kFirstPaddings`) of each stream with a random 3-byte frame prepended to the actual payload:

```
For frames 0..7 (kFirstPaddings):
  [pad_length(1B)][pad_data(pad_length B)][actual_payload]
  pad_length = random(0, 255)

For frames 8+:
  [actual_payload]  (no padding)
```

This matches Chrome's `WINDOW_UPDATE` burst pattern during stream establishment and prevents length-distribution fingerprinting on connection setup.

**`AUTH_DATA` pseudo-header:** On servers that inspect H2 headers for auth (not just HTTP Basic), NaiveProxy encodes credentials as a custom pseudo-header using a challenge-response derived from the TLS session — avoids Basic auth pattern detection.

#### Known Weaknesses

| Weakness | Detectability | Notes |
|----------|---------------|-------|
| Fast-open burst | Medium | First 8 frames sent rapidly before server ACK — differs from browser pacing |
| H2 recv window | Medium | Default 128 MB (`INITIAL_WINDOW_SIZE=134217728`); real Chrome uses 6 MB |
| Caddy server fingerprint | Low | Must use Caddy + forwardproxy; NGINX/Apache H2 SETTINGS differ |
| Connection count | Low | Chrome opens max 6 H2 conns per domain; NaiveProxy may open more |

H2 recv window mismatch (128 MB vs Chrome's 6 MB) is the primary remaining distinguisher in lab conditions (≤3% FP rate in GFW classifiers as of 2025).

#### Deployment Requirement

Server must run `caddy` with `forward_proxy` plugin (not NGINX/haproxy — Caddy preserves the HTTP/2 server fingerprint Chrome expects). Without matching server fingerprint, active probing reveals the proxy.

```json
{
  "apps": {
    "http": {
      "servers": {
        "srv0": {
          "routes": [{
            "handle": [{
              "handler": "forward_proxy",
              "hide_ip": true,
              "hide_via": true,
              "auth_user_id": "user",
              "auth_pass": "pass",
              "probe_resistance": { "domain": "www.example.com" }
            }]
          }]
        }
      }
    }
  }
}
```

`probe_resistance.domain` makes unauthenticated requests return a real website (like ShadowTLS) — active-probing safe.

#### Android

No dedicated Android NaiveProxy client. Available indirectly via:
- **Clash.Meta** (`naive` outbound type in config)
- **sing-box** (naive outbound, wraps subprocess)

Native RIPDPI integration requires embedding Chromium `net/` via JNI — impractical (+20 MB binary, complex build). Recommended path: bundle `naiveproxy` binary as subprocess (similar to how sing-box handles it), invoke via `Command::new()`.

**Reference:** [naiveproxy](https://github.com/klzgrad/naiveproxy), [forwardproxy Caddy plugin](https://github.com/klzgrad/forwardproxy)
**Chrome version tracking:** [naiveproxy releases](https://github.com/klzgrad/naiveproxy/releases) — updated with every Chrome stable release

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

#### smux Frame Format

**v1 (8-byte header, Little Endian):**
```
[Version(1)=1][Cmd(1)][Length(2)][StreamID(4)][Data(Length)]

Cmd: 0x00=SYN | 0x01=FIN | 0x02=PSH | 0x03=NOP
```

**v2 (8-byte header, Little Endian — adds per-stream flow control):**
```
[Version(1)=2][Cmd(1)][Length(2)][StreamID(4)][Data(Length)]

Additional cmd: 0x04=UPD (flow control update)
```

**v2 cmdUPD frame (flow control):**
```
[Version(1)=2][Cmd(1)=0x04][Length(2)=8][StreamID(4)]
[Consumed(4)][Window(4)]   ← absolute counters, Little Endian
```
`Consumed` = total bytes consumed by receiver since stream open (monotonically increasing).
`Window` = receiver's current receive buffer size. Sender may transmit up to `Window - (sent - Consumed)` bytes. Prevents head-of-line blocking between streams.

#### yamux Frame Format (12-byte header, Big Endian)

```
[Type(1)][Flags(1)][StreamID(4)][Length(4)]   = 8 bytes (v0 — SYN/FIN/DATA/PING/GO_AWAY)

Window update frame:
[Type(1)=0x01][Flags(2)][StreamID(4)][Delta(4)]  ← Delta = window increment (not absolute)
```
yamux uses **delta** window increments (like TCP), unlike smux v2's absolute consumed+window model. Big Endian byte order is the key difference from smux.

#### h2mux (HTTP/2 CONNECT over existing connection)

```
h2mux opens a single TCP/TLS connection and sends:
  SETTINGS [HEADER_TABLE_SIZE=65536, INITIAL_WINDOW_SIZE=1073741823]
  WINDOW_UPDATE [stream=0, increment=1073741823]

Per multiplexed stream:
  HEADERS [:method=CONNECT, :authority=target:port]  → opens stream
  DATA [payload]                                      → bidirectional data
  RST_STREAM                                          → closes stream
```
h2mux is the best choice when the upstream server is behind a CDN that requires HTTP/2 (Cloudflare, Fastly) — the mux traffic is indistinguishable from HTTP/2 CONNECT proxy traffic.

#### sing-box Implementation Reference

```json
{
  "multiplex": {
    "enabled": true,
    "protocol": "smux",         // "smux" | "yamux" | "h2mux"
    "max_connections": 4,       // max parallel mux connections to relay
    "min_streams": 4,           // open new connection when existing has <N streams
    "max_streams": 0,           // 0 = unlimited streams per connection
    "padding": false,           // add random padding frames (anti-traffic-shape)
    "brutal": {
      "enabled": false,         // Hysteria2 bandwidth-limit bypass (TCP only)
      "up_mbps": 100,
      "down_mbps": 100
    }
  }
}
```

**Protocol selection guidance:**
- `smux v2` — default, best for Rust/Go implementations; per-stream flow control avoids buffer bloat
- `yamux` — HashiCorp standard; use when relay speaks yamux (e.g., Consul, Vault tunnels)
- `h2mux` — use when CDN fronting required (Cloudflare Workers, Fastly)

#### Impact for RIPDPI

Relevant when VLESS (item 9) or Hysteria2 (item 10) relay is active and users open many parallel connections. Reduces: observable connection-establishment burst (many QUIC Initials → single connection), relay CPU cost per connection, per-connection QUIC overhead (~1300 bytes min per Initial packet).

TUIC v5 (item 20) uses native QUIC stream multiplexing and does not require smux.

**Rust implementation:** [smux](https://github.com/black-binary/async-smux) (`async-smux` crate, tokio-based), [yamux](https://github.com/libp2p/rust-yamux) (`libp2p-yamux`), [h2](https://github.com/hyperium/h2) for h2mux mode.

**Reference:** [xtaci/smux](https://github.com/xtaci/smux) (Go reference), [sing-box multiplex docs](https://sing-box.sagernet.org/configuration/shared/multiplex/)

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

---

## Tier 3 (continued): xHTTP Transport (P0 — April 2026 Escalation)

### 25. xHTTP Transport for VLESS

Layer-4 ML behavioral classifiers deployed in middlebox in Q1 2026 defeat VLESS+Reality-over-TCP: a persistent bidirectional TLS stream to a foreign IP with symmetric packet size distribution and no characteristic application-layer request patterns is flagged within seconds. xHTTP defeats this by restructuring the tunnel as discrete HTTP transactions indistinguishable from browser API calls.

> **Status:** VLESS (item 9) is implemented; xHTTP is the missing transport layer. Without it, Reality-only VLESS will be blocked with increasing reliability as ML rulesets improve. WebSocket and gRPC are deprecated in xray-core in favour of xHTTP.

#### How middlebox Detects Reality-Only TCP

Four-layer classification pipeline (Habr, March 2026):

| Layer | Bytes | Signal |
|-------|-------|--------|
| L1 | 0–5 | Protocol byte signature — already defeated by Reality |
| L2 | 6–300 | JA3/JA4 fingerprint + SNI vs ASN mismatch |
| L3 | 300–3000 | Certificate origin vs ASN (Apple cert from Hetzner IP) |
| L4 | 3000–16000 | **Behavioral ML**: packet size distribution, upload/download ratio, connection duration, absence of legitimate API request patterns |

Reality closes L1–L3. xHTTP closes L4.

#### Wire Protocol

xHTTP encapsulates tunnel traffic as HTTP/2 (or HTTP/3) transactions. Each upstream "message" is a `POST` request body; each downstream "message" is a streaming `GET` response body. From an observer's perspective: ordinary browser-to-CDN API traffic.

**Four modes:**

| Mode | Upstream | Downstream | CDN compatible |
|------|----------|------------|----------------|
| `packet-up` | Multiple short POST requests | Single chunked GET response | Yes |
| `stream-up` | One persistent POST stream | Single chunked GET response | Partial |
| `stream-one` | Single bidirectional stream | Same stream | No (XTLS-Vision only) |
| `auto` | Client-selected per-capability | — | Yes |

**Request structure (packet-up, client → server):**
```
POST /<path> HTTP/2
Host: <destination>
Content-Type: application/grpc
X-Padding: <random bytes, 100–1000>   ← anti-size-fingerprinting

[VLESS payload chunk]
```

**Response structure (server → client):**
```
HTTP/2 200
Content-Type: application/grpc
Transfer-Encoding: chunked

[streaming VLESS response data]
```

#### XMUX Multiplexing

Without multiplexing, each TCP connection through the tunnel becomes a separate HTTP/2 stream, and the number of concurrent streams to a single CDN domain becomes a detection signal. XMUX batches multiple VLESS flows into one HTTP/2 connection.

```
XMUX session (persistent H2 connection to CDN)
├── H2 stream 1 → VLESS flow for tab 1
├── H2 stream 2 → VLESS flow for tab 2
└── H2 stream 3 → VLESS flow for tab 3
```

Config:
```json
{
  "xmux": {
    "max_connections": 8,
    "max_concurrent_streams": 32,
    "c_down_min_kbps": 0,
    "c_up_min_kbps": 0
  }
}
```

#### xPaddingBytes — Defeating Size-Distribution Classifiers

middlebox ML models extract packet size histograms. Proxy tunnels show MTU-clustered distributions (1400–1500 byte packets dominate). Browser traffic shows wide variance. `xPaddingBytes` adds a random number of padding bytes to each request:

```json
{
  "xPaddingBytes": "100-1000"
}
```

Each POST request body length = `payload_length + random(100, 1000)`. The server strips the padding. The resulting size distribution matches browser API calls.

**Mandatory:** Without padding, size-distribution ML achieves 95–99% classification accuracy in lab conditions.

#### Recommended Server Config (April 2026)

```json
{
  "transport": {
    "type": "xhttp",
    "path": "/api/v1/stream",
    "mode": "auto",
    "xPaddingBytes": "100-1000",
    "host": "www.microsoft.com",
    "scMaxConcurrentPosts": 0,
    "scMaxEachPostBytes": "1mb-2mb",
    "scMinPostsIntervalMs": "10-50"
  },
  "tls": {
    "enabled": true,
    "server_name": "www.microsoft.com",
    "fingerprint": "chrome",
    "reality": {
      "enabled": true,
      "public_key": "...",
      "short_id": "..."
    }
  }
}
```

**Preferred mask sites:** `github.com`, `twitch.tv`, `discord.com`. Avoid `apple.com` — Apple operates from AS714; Hetzner/Aeza IPs in Apple's name are flagged at L3.

**Version constraint:** Client and server Xray-core versions must match. XMUX protocol is under active development; mismatched versions cause silent connection failure.

#### CDN Deployment (xHTTP + Cloudflare)

When targeting `packet-up` mode through Cloudflare CDN:

```
Client → Cloudflare edge (CDN, whitelisted IP) → VPS (xHTTP server)
```

- Cloudflare acts as TCP/TLS terminator; VLESS payload rides in the CDN-proxied HTTP/2 body
- Client connects to Cloudflare IP (not blocked); Cloudflare forwards to VPS via HTTP/2 upstream
- **Cloudflare free tier limit:** 100 MB request body → `scMaxEachPostBytes` must stay under 95 MB
- CDN fronting defeats mobile whitelist blocking (Cloudflare IPs remain accessible)
- Requires `fingerprint: "chrome"` on client TLS to Cloudflare (see item 26)

#### Rust Implementation Notes

The existing `ripdpi-vless` crate handles VLESS framing; xHTTP is a transport-layer addition:

```
ripdpi-vless (VLESS framing)
  └── ripdpi-xhttp (new) — HTTP/2 transport
        ├── hyper 1.x + h2 crate (H2 framing)
        ├── xPaddingBytes PRNG (thread_rng range)
        ├── XMUX session manager (connection pool + stream counter)
        └── CDN TLS (boring crate, fingerprint: chrome — see item 26)
```

The `h2` crate (`hyperium/h2`) handles HTTP/2 framing directly without the overhead of full `hyper`. XMUX requires a connection pool: maintain N persistent H2 connections, route new VLESS flows to least-loaded connection.

**Interaction with item 23 (smux):** smux and XMUX are alternative multiplexing strategies; do not combine. Use XMUX when xHTTP transport is active.

#### References and Implementation Resources

**Protocol specification:**
- [xray-core xHTTP transport spec](https://xtls.github.io/en/config/transports/xhttp.html) — official config reference, mode descriptions, xPaddingBytes semantics
- [xray-core xHTTP source](https://github.com/XTLS/Xray-core/tree/main/transport/internet/xhttp) — Go reference implementation; `XmuxManager`, `XmuxSession`, padding injection
- [xray-core XMUX source](https://github.com/XTLS/Xray-core/blob/main/transport/internet/xhttp/xmux.go) — connection pool design, `scMaxConcurrentPosts` enforcement, stream routing
- [xray-core releases](https://github.com/XTLS/Xray-core/releases) — version-lock requirement; check `CHANGELOG.md` for xHTTP breaking changes per release; client/server must match

**Rust crates:**
- [`h2` crate](https://github.com/hyperium/h2) (`hyperium/h2` v0.4) — HTTP/2 framing; `SendRequest<Bytes>` for POST streams, `RecvStream` for chunked GET response
- [`hyper` v1.x `conn::http2`](https://docs.rs/hyper/latest/hyper/client/conn/http2/index.html) — H2-only connection builder; `Builder::new(TokioExecutor)` for async H2 client
- [`bytes` crate](https://github.com/tokio-rs/bytes) — zero-copy `Bytes`/`BytesMut` for padding injection without heap allocation

**CDN and server setup:**
- [chika0801/Xray-examples — VLESS-xHTTP-XTLS-Reality](https://github.com/chika0801/Xray-examples/tree/main/VLESS-xHTTP-XTLS-Reality) — canonical server + client configs for xHTTP+Reality+CDN; Nginx reverse proxy snippets
- [Cloudflare Workers request size limits](https://developers.cloudflare.com/workers/platform/limits/) — 100 MB request body; set `scMaxEachPostBytes: "95mb"` to stay within free tier limit
- [3x-ui xHTTP server setup](https://github.com/MHSanaei/3x-ui/wiki) — panel config for xHTTP transport inbound; relevant for users configuring their own VPS via GUI
- [autoXRAY](https://github.com/iwatkot/autoxray) — one-command VLESS+xHTTP setup on clean Ubuntu VPS; Docker-based 3x-ui deployment

**Community field reports:**
- [ntc.party thread 13855](https://ntc.party/t/13855) — "Тестируем XHTTP", 426+ replies, April 2026; ISP-specific working configs, xPaddingBytes tuning, CDN fallback
- [ntc.party thread 23924](https://ntc.party/t/23924) — xHTTP + Reality + Steal Oneself; when Reality overhead is unnecessary; Nginx `location` proxy_pass config
- [ntc.party thread 23943](https://ntc.party/t/23943) — TCP Reality breaks multi-hop cascade; use gRPC or xHTTP transport for RU VPS → foreign VPS chains
- [Habr: Как ТСПУ ловит VLESS (Mar 12, 2026)](https://habr.com/ru/articles/1009542/) — 4-layer detection model; xHTTP as Layer-4 mitigation; recommended `dest` sites and `xPaddingBytes` rationale
- [Habr: Анатомия DPI (Mar 14, 2026)](https://habr.com/ru/articles/1009560/) — packet-level analysis; size distribution signals; timing features middlebox ML extracts

**Root required:** No | **Effort:** High | **Priority:** P0

---

## Tier 4 (continued): JA3 Fingerprint — All TLS Origins (P0)

### 26. JA3/JA4 Chrome Fingerprint for All Client-Originated TLS

Item 13 covers fingerprint mimicry conceptually. This item specifies the implementation scope triggered by the April 2026 escalation: **every** TLS connection originated by RIPDPI must present a Chrome-compatible JA3/JA4 fingerprint. Scope is broader than just middlebox-facing connections.

#### April 2026 Trigger Event

On April 1, 2026, middlebox began blocking Telegram by JA3 fingerprinting the Telegram client's own TLS ClientHello:

- **Telegram JA3:** `f07cc269d9323c428b7297219bed6754`
- **Detection:** Non-standard extension ordering, absent ECH, non-browser ALPN, MTProto-characteristic cipher suite order
- **Blocking scope:** Client-to-proxy TLS connections, not just server IP blocking
- **MTProxy/FakeTLS ineffective:** These change the server destination but not the client's own TLS fingerprint

Consequence: any component of RIPDPI that opens a TLS connection with Go's `crypto/tls` default or Rust `rustls` default fingerprint is now detectable and blockable by the same mechanism.

#### Scope of TLS Connections Requiring Chrome JA3

| Connection | Current library | Risk |
|-----------|----------------|------|
| VLESS+Reality outbound | BoringSSL via `boring` crate | Low — Reality handles server-side; client Hello to real site uses boring defaults |
| xHTTP to CDN (item 25) | TBD | **High** — Cloudflare-facing Hello is DPI-visible |
| Hysteria2 over QUIC | `rustls` / `quinn` | **High** — QUIC Initial carries TLS ClientHello |
| DoH resolver (dns-over-https) | `reqwest` / `rustls` | Medium — DNS query TLS visible |
| WARP registration API (item 1) | Custom TLS, already uses SNICurve extension | Medium |
| ShadowTLS v3 (item 21) | BoringSSL | Low — mirrors real site Hello by design |
| MASQUE/Cloudflare (item 12) | TBD | **High** — HTTP/3 QUIC Initial visible |

#### Chrome JA3 Fingerprint Spec (Chrome 133, April 2026)

```
TLS version: TLS 1.3
Cipher suites (order matters):
  0x1301 (TLS_AES_128_GCM_SHA256)
  0x1302 (TLS_AES_256_GCM_SHA384)
  0x1303 (TLS_CHACHA20_POLY1305_SHA256)
  0xC02B, 0xC02F, 0xC02C, 0xC030 (ECDHE-AESGCM variants)
  0xCCA9, 0xCCA8 (ECDHE-CHACHA20)
  0xC013, 0xC014, 0x009C, 0x009D, 0x002F, 0x0035 (legacy, for fallback)

Extensions (order matters for JA4, sorted for JA4+):
  0x0000 SNI
  0x0017 extended_master_secret
  0xFF01 renegotiation_info
  0x000A supported_groups: [X25519MLKEM768(0x11EC), X25519(0x001D), P256(0x0017), P384(0x0018)]
  0x000B ec_point_formats: [uncompressed]
  0x0010 ALPN: [h2, http/1.1]
  0x0016 encrypt_then_mac (empty)
  0x0023 session_ticket (empty)
  0x0012 signed_cert_timestamps (empty)
  0x0033 key_share: [X25519MLKEM768, X25519]
  0x002B supported_versions: [TLS 1.3, TLS 1.2]
  0x000D signature_algorithms: [ECDSA_P256_SHA256, RSA_PSS_SHA256, RSA_PKCS1_SHA256, ...]
  0x0015 padding (variable, to target ~512 byte ClientHello)
  GREASE values at start/end of cipher list and extension list
```

**ML-KEM 768 key share** (`X25519MLKEM768`, extension 0x11EC) was added in Chrome 131 and is a strong differentiator — Go `crypto/tls` and `rustls` do not include it by default.

#### Implementation via BoringSSL (`boring` crate)

RIPDPI already links BoringSSL via the `boring` crate (for `boringtun`). Extend this to control ClientHello construction:

```rust
use boring::ssl::{SslConnector, SslMethod, SslVersion};

fn build_chrome_connector() -> SslConnector {
    let mut builder = SslConnector::builder(SslMethod::tls_client()).unwrap();

    // TLS 1.3 only for modern connections
    builder.set_min_proto_version(Some(SslVersion::TLS1_2)).unwrap();

    // Set cipher list matching Chrome 133
    builder.set_cipher_list(
        "TLS_AES_128_GCM_SHA256:TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256:\
         ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:..."
    ).unwrap();

    // Enable GREASE (BoringSSL extension)
    builder.set_grease_enabled(true);

    // Add ML-KEM 768 key share group
    builder.set_curves(&[
        boring::nid::Nid::X25519MLKEM768,
        boring::nid::Nid::X25519,
        SslCurve::SECP256R1,
        SslCurve::SECP384R1,
    ]).unwrap();

    // Padding extension — target ~512 byte ClientHello
    builder.set_record_padding_cb(...);

    builder.build()
}
```

#### JA3 Fingerprint Staleness Problem

Chrome updates its TLS fingerprint approximately every 6 weeks with each major release. RIPDPI hardcoding Chrome 133 will drift. Options:

1. **Bundled fingerprint profiles** — JSON file with per-version Chrome/Firefox fingerprints, updated via strategy packs (item 15). App selects the most recent profile.
2. **GREASE randomisation** — Chrome's GREASE values change per-session; BoringSSL `set_grease_enabled(true)` handles this automatically.
3. **Fallback fingerprint rotation** — if current fingerprint starts failing diagnostics, rotate to next profile automatically.

#### References and Implementation Resources

**Protocol and specification:**
- [TLS 1.3 RFC 8446](https://www.rfc-editor.org/rfc/rfc8446) — ClientHello structure, extension ordering
- [JA3 fingerprinting paper](https://github.com/salesforce/ja3) — Salesforce original JA3 algorithm, hash computation
- [JA4+ specification](https://github.com/FoxIO-LLC/ja4) — JA4, JA4S, JA4T fingerprint formats
- [GREASE RFC 8701](https://www.rfc-editor.org/rfc/rfc8701) — Reserved values for GREASE extension slots
- [ML-KEM (Kyber) NIST FIPS 203](https://csrc.nist.gov/pubs/fips/203/final) — Post-quantum key encapsulation (X25519MLKEM768)

**Rust implementation:**
- [`boring` crate](https://crates.io/crates/boring) — BoringSSL Rust bindings (already in RIPDPI via boringtun); `SslContextBuilder::set_curves_list`, `set_grease_enabled`
- [`rustls`](https://crates.io/crates/rustls) — Pure-Rust TLS; does NOT support GREASE or ML-KEM by default — use boring crate for fingerprint-accurate TLS
- [uTLS Go library](https://github.com/refraction-networking/utls) — Reference implementation of Chrome/Firefox TLS mimicry; study `ClientHelloSpec` structures for port to Rust
- [tlsfingerprint.io](https://tlsfingerprint.io/) — Live JA3/JA4 database; test RIPDPI output here

**Telegram block incident (April 2026):**
- [ntc.party thread on Telegram JA3 block](https://ntc.party/t/telegram-blocking-ja3) — JA3 hash `f07cc269d9323c428b7297219bed6754` block confirmed; multi-connection "Siberian" trigger
- [`/Users/po4yka/GitRep/obsidian/TechnicalVault/Censorship/telegram-ja3-blocking-2026.md`] — local analysis note

**Chrome fingerprint sources:**
- [Chrome TLS changelog](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/net/socket/ssl_client_socket_impl.cc) — ClientHello construction in Chromium source
- [Fingerprint Randomizer proposal](https://bugs.chromium.org/p/chromium/issues/detail?id=775438) — GREASE history in Chrome
- [TLS Observatory](https://observatory.mozilla.org/) — Compare fingerprints across client versions

**Fingerprint profile bundling:**
- [Xray-core `fingerprint` field](https://xtls.github.io/en/config/transport.html#tlsobject) — How Xray/sing-box handle fingerprint profiles as JSON configs; reuse format for RIPDPI strategy packs (item 15)

**Root required:** No | **Effort:** High | **Priority:** P0

---

## Tier 5 (continued): Android Platform (P1–P2)

### 27. IP Correlation Deanonymization Defense — Dual-IP VPS Support

**Threat model (April 2026, ntc.party thread 23690, 163 replies):**

Spyware embedded in Russian apps (MAX Messenger, potentially others) detects the VPN exit IP and transmits it to servers. Authorities cross-reference with ISP NAT logs to deanonymize users. This attack works regardless of VPN protocol quality — it targets the *exit IP*, not the VPN detection.

```
Attack flow:
[User device] ──VLESS──▶ [VPS: inbound=A, outbound=A] ──▶ [Internet]
                                        ▲
              MAX Messenger sees IP A, reports to servers
                                        ▼
              [ISP NAT log: IP A ↔ user's home IP]
              → user identified
```

#### Defence: Separate Inbound and Outbound IPs

With two IPs on the relay VPS:

```
[User device] ──VLESS──▶ [VPS: inbound=A (secret), outbound=B (public)] ──▶ [Internet]
                                                        ▲
                                  Spyware sees IP B, reports it
                                                        ▼
                            [ISP NAT: IP B ↔ ???]  ← no match (B is not the listening addr)
```

The user connects to IP A (not advertised publicly). Traffic exits through IP B. The spyware reports B but ISP logs only show connections *to* A.

#### Client-Side Configuration

Add `outbound_bind_ip` field to relay/chain configuration:

```json
{
  "relay": {
    "entry": {
      "server": "A.A.A.A",
      "port": 443,
      "protocol": "vless-reality"
    },
    "outbound_bind_ip": "B.B.B.B"
  }
}
```

In Rust relay code (`ripdpi-relay-core`), bind outbound sockets explicitly:

```rust
let socket = TcpSocket::new_v4()?;
socket.bind(SocketAddr::new(outbound_ip, 0))?;
let stream = socket.connect(remote_addr).await?;
```

On the VPS, the secondary IP must be configured as a routing source:
```bash
ip route add default via <gateway> dev eth0 src B.B.B.B table 200
ip rule add from B.B.B.B table 200
```

RIPDPI should surface this as a configuration field in the relay setup wizard with a documentation link explaining the VPS-side route configuration.

#### Relationship to Chain Relay (item 11)

Chain relay (RU VPS → foreign VPS) provides equivalent protection: the spyware sees the Russian VPS's outbound IP, which is domestic and not directly linkable to a foreign proxy. Dual-IP VPS is the single-server alternative at lower cost.

#### References and Implementation Resources

**Threat research:**
- [ntc.party thread 23690 — IP correlation deanonymization](https://ntc.party/t/23690) — 163 replies; MAX Messenger spyware IP reporting, ISP NAT log cross-reference mechanism
- [`/Users/po4yka/GitRep/obsidian/TechnicalVault/Censorship/vps-ip-correlation-attack.md`] — local analysis note with attack flow diagram

**Dual-IP VPS providers:**
- [Hetzner additional IPs](https://docs.hetzner.com/cloud/servers/primary-ips/overview/) — €1.19/month per extra IP; simple failover setup
- [Aeza.net](https://aeza.net/) — Russian-friendly VPS with additional IPs; low latency from Russia
- [Vdsina.ru](https://vdsina.ru/) — Russian VPS provider, multiple IPs available

**Linux IP routing for dual-IP outbound:**
- [iproute2 policy routing](https://lartc.org/howto/lartc.rpdb.html) — `ip rule` / `ip route table` setup for source-based routing; exact commands for `ip route add default via <gw> src B.B.B.B table 200`
- [Linux Advanced Routing & Traffic Control](https://lartc.org/howto/) — comprehensive reference for multi-homed server routing

**Rust socket binding:**
- [`tokio::net::TcpSocket::bind`](https://docs.rs/tokio/latest/tokio/net/struct.TcpSocket.html#method.bind) — bind before connect for source IP selection
- [SO_BINDTODEVICE](https://man7.org/linux/man-pages/man7/socket.7.html) — bind socket to specific network interface (alternative approach)

**sing-box reference:**
- [sing-box `inet4_bind_address`](https://sing-box.sagernet.org/configuration/outbound/) — outbound bind IP field; adapt pattern for RIPDPI relay config schema
- [ntc.party thread 24016](https://ntc.party/t/24016) — `package_name_regex` 1.14.0-alpha.10 discussion (also relevant to item 28)

**Root required:** No (client-side config only) | **Effort:** Low | **Priority:** P1

---

### 28. Per-App Package Name Regex Routing

**Extends item 24 (per-app UID exclusion)** with regex-based matching introduced by sing-box 1.14.0-alpha.10 (ntc.party thread 24016, April 2026).

#### Problem with UID-Based Exclusion

Android VpnService `addDisallowedApplication(pkg)` requires exact package names and is resolved to UIDs at VPN startup. Two failure modes:

1. **Force-selected interface:** Some apps (Sberbank, certain Russian government apps) call `Network.bindSocket()` or use `ConnectivityManager.requestNetwork()` to bypass UID-based routing entirely — they select the underlying network interface directly, circumventing the VPN. UID exclusion does not help.

2. **Package name enumeration:** The exact-match exclusion list requires constant maintenance as new detection-implementing apps appear.

#### Solution: Regex-Based Routing Rules

Resolve patterns at config parse time to UID lists, then apply at runtime:

```json
{
  "route": {
    "rules": [
      {
        "package_name_regex": ["^ru\\.sberbank", "^ru\\.tinkoff", "^ru\\.vtb", "^ru\\.alfabank"],
        "outbound": "direct",
        "comment": "Banking apps — route direct to avoid VPN detection flags"
      },
      {
        "package_name_regex": ["^ru\\.gosuslugi", "^ru\\.nalog", "^com\\.fns"],
        "outbound": "direct",
        "comment": "Government apps — route direct, often have mandatory real-IP checks"
      },
      {
        "package_name_regex": [".+"],
        "invert": true,
        "outbound": "block",
        "comment": "Block unidentified packages (anti-leak for unknown apps)"
      }
    ]
  }
}
```

#### Android Implementation

```kotlin
// At VPN config build time — resolve regex to package list
fun resolvePackageRegex(pm: PackageManager, pattern: String): List<String> {
    val regex = Regex(pattern)
    return pm.getInstalledPackages(0)
        .map { it.packageName }
        .filter { regex.containsMatchIn(it) }
}

// Apply to VpnService.Builder
resolvedPackages.forEach { pkg ->
    try { builder.addDisallowedApplication(pkg) }
    catch (e: PackageManager.NameNotFoundException) { /* not installed */ }
}
```

#### Preset Rule Sets

Bundle common presets in `app-exclusion-list.json` (item 24's file):

| Preset | Pattern | Rationale |
|--------|---------|-----------|
| Russian banking | `^ru\\.sberbank`, `^ru\\.tinkoff`, `^ru\\.vtb`, `^ru\\.alfabank` | Use domestic IP for auth; geo-checks on login |
| Russian government | `^ru\\.gosuslugi`, `^ru\\.nalog`, `^com\\.fns` | Real IP required; often have VPN detection |
| Russian marketplaces | `^ru\\.wildberries`, `^ru\\.ozon`, `^ru\\.avito` | IP-based geo-check on checkout |
| All Russian namespace | `^ru\\.` | Aggressive — routes all `ru.*` apps direct |

#### Interaction with May 2026 Billing Surcharge

From May 1, 2026: mobile data usage >15 GB/month incurs 150 RUB/GB surcharge for international traffic. Apps that generate high-volume domestic traffic (streaming, cloud sync) must be routed direct to avoid billing through the VPN's international exit. The `^ru\.` preset directly addresses this.

#### References and Implementation Resources

**sing-box 1.14.0 `package_name_regex`:**
- [ntc.party thread 24016](https://ntc.party/t/24016) — original discussion of `package_name_regex` feature, April 2026
- [sing-box route rules documentation](https://sing-box.sagernet.org/configuration/route/rule/) — `package_name` and regex rule fields
- [sing-box 1.14.0-alpha changelog](https://github.com/SagerNet/sing-box/releases) — release notes for the feature introduction

**Android VpnService per-app routing:**
- [VpnService.Builder.addDisallowedApplication](https://developer.android.com/reference/android/net/VpnService.Builder#addDisallowedApplication(java.lang.String)) — official API; resolves package name to UID at build time
- [VpnService.Builder.addAllowedApplication](https://developer.android.com/reference/android/net/VpnService.Builder#addAllowedApplication(java.lang.String)) — allowlist variant (mutually exclusive with disallow list)
- [PackageManager.getInstalledPackages](https://developer.android.com/reference/android/content/pm/PackageManager#getInstalledPackages(int)) — enumerate installed packages for regex resolution

**Force-selected interface bypass (banking apps):**
- [ConnectivityManager.requestNetwork](https://developer.android.com/reference/android/net/ConnectivityManager#requestNetwork(android.net.NetworkRequest,%20android.net.ConnectivityManager.NetworkCallback)) — how apps bypass VPN UID routing
- [Android VPN bypass mechanisms](https://github.com/shadowsocks/shadowsocks-android/issues/2919) — shadowsocks-android issue tracking force-selected interface behavior

**Russian app package name reference:**
- [RuStore app catalog](https://www.rustore.ru/catalog) — authoritative source for Russian app package names
- Common patterns: `ru.sberbank.spasibo`, `ru.tinkoff.banking`, `ru.vtb24.mobilebank.android`, `ru.alfabank.mobile.android`, `ru.gosuslugi.mobile`, `ru.nalog.www`

**May 2026 billing surcharge source:**
- [ntc.party discussion on mobile billing changes](https://ntc.party/t/23602) — operator surcharge for >15 GB international traffic context

**Root required:** No | **Effort:** Low–Medium | **Priority:** P1

---

### 29. Russian Cloud Relay Preset for Mobile Whitelist Bypass

**Extends item 11 (chain relay)** with a purpose-built preset for the mobile whitelist regime active since September 2025 on all four major Russian operators.

#### Problem: Foreign VPS Unreachable on Mobile Data

Mobile whitelist mode blocks all TCP/TLS connections to non-approved IP ranges at the SYN+ClientHello level. Hetzner, DigitalOcean, Vultr, OVH, Aeza — all blocked. Even Cloudflare CDN IPs are partially blocked for mobile users (operator-specific).

#### Solution: Russian Cloud as First Hop

Russian cloud providers (Yandex.Cloud, VK Cloud, Selectel within Russia) are implicitly whitelisted — blocking them would break Yandex services and trigger regulatory liability. Traffic Yandex.Cloud → foreign VPS exits Russia on a domestic-origin connection, bypassing middlebox international-traffic inspection.

```
[Mobile device] ──VLESS+Reality──▶ [Yandex.Cloud preemptible VM, ru-central1]
                                              │
                                     VLESS+xHTTP tunnel
                                              │
                                              ▼
                                   [Foreign VPS, e.g. Aeza Stockholm]
                                              │
                                              ▼
                                         [Internet]
```

#### Yandex.Cloud Specifics

- **Preemptible VMs:** 400–500 RUB/month (e1.micro, 2 vCPU / 1 GB RAM), interrupted max 24h/day
- **Static IPs:** 108–120 RUB/month for a dedicated external IP
- **Connectivity:** Yandex.Cloud → Hetzner/Aeza/Vultr has 30–50ms added latency vs direct
- **SNI cover:** Use `ya.ru` or `yandex.ru` as outer SNI on the client→relay hop (both are whitelisted)

#### RIPDPI Config Preset

```json
{
  "preset": "ru-relay-mobile",
  "description": "Russian Cloud Relay for mobile whitelist bypass",
  "hops": [
    {
      "role": "entry",
      "server": "<yandex-cloud-vm-ip>",
      "port": 443,
      "protocol": "vless-reality",
      "tls": {
        "server_name": "ya.ru",
        "fingerprint": "chrome"
      }
    },
    {
      "role": "exit",
      "server": "<foreign-vps-ip>",
      "port": 443,
      "protocol": "vless-xhttp",
      "transport": {
        "mode": "packet-up",
        "xPaddingBytes": "100-1000"
      }
    }
  ],
  "routing": {
    "domestic_direct": true,
    "whitelist_domains_direct": true
  }
}
```

#### Routing Logic

- **Domestic traffic direct:** RU IPs bypass the relay entirely (improves latency for domestic services)
- **Whitelisted domains direct:** Gosuslugi, Sberbank, etc. use ISP connection (avoids double-hop overhead)
- **Everything else:** Relay chain

Integrate with the existing `host-autolearn-v2.json` per-network scoping: on mobile networks, auto-activate the Russian relay preset when diagnostics detect whitelist-mode blocking (item 4 of the WARP endpoint scanner can be adapted for relay health probing).

#### Cost Estimate

| Component | Cost |
|-----------|------|
| Yandex.Cloud e1.micro preemptible | ~400 RUB/month |
| Static IP | ~120 RUB/month |
| Foreign VPS (Aeza Stockholm) | ~€2/month (~200 RUB) |
| **Total** | **~720 RUB/month (~$8)** |

#### References and Implementation Resources

**Mobile whitelist regime background:**
- [`/Users/po4yka/GitRep/obsidian/TechnicalVault/Censorship/censorship-landscape-2026.md`] — whitelist mode activation timeline, operator coverage
- [ntc.party — whitelist blocking discussions](https://ntc.party/c/censorship-research-publications/22) — ongoing field reports from operators in 57+ regions

**Yandex.Cloud setup:**
- [Yandex.Cloud Compute VM pricing](https://cloud.yandex.ru/en/prices#compute) — e1.micro preemptible: ~400 RUB/month; static IP: ~120 RUB/month
- [Yandex.Cloud CLI quickstart](https://cloud.yandex.ru/en/docs/cli/quickstart) — `yc compute instance create` for automated provisioning
- [Yandex.Cloud preemptible VM docs](https://cloud.yandex.ru/en/docs/compute/concepts/preemptible-vm) — interruption policy, max 24h uptime guarantees

**VLESS+Reality on the entry hop:**
- [XTLS Reality protocol spec](https://github.com/XTLS/REALITY) — SNI cover (`ya.ru`/`yandex.ru`), `publicKey`/`shortId` config
- [3x-ui panel](https://github.com/MHSanaei/3x-ui) — web UI for Xray-core; simplest way to deploy the entry node on Yandex.Cloud VM
- [autoXRAY](https://github.com/GFWFighter/autoXRAY) — automated Xray + Reality setup script; supports dual-hop configuration

**xHTTP transport on the exit hop (item 25):**
- [Xray xHTTP transport docs](https://xtls.github.io/en/config/transport.html#xhttptransportsettings) — `xPaddingBytes`, `mode: "packet-up"`, multiplexing config
- [chika0801/Xray-examples — xHTTP](https://github.com/chika0801/Xray-examples) — ready-made server configs for xHTTP exit node

**Aeza Stockholm as foreign exit:**
- [Aeza.net Stockholm VPS](https://aeza.net/) — ~€2/month, low latency from Yandex.Cloud ru-central1
- [Yandex.Cloud → Stockholm latency](https://cloudping.info/) — typically 30–50ms; acceptable for interactive use

**Routing integration (item 4 WARP scanner adaptation):**
- [sing-box outbound `default_interface`](https://sing-box.sagernet.org/configuration/outbound/) — force traffic through relay on mobile vs. direct on Wi-Fi
- [RIPDPI item 4 — WARP endpoint scanner](../roadmap-integrations.md#4-warp-endpoint-scanner) — relay health probe can reuse same probe logic

**Root required:** No | **Effort:** Medium | **Priority:** P1

---


---

## Tier 2 (continued): Obfuscation Primitives (P1)

### 30. xray-core Finalmask Obfuscation Framework

**xray-core v26.3.27 (March 27, 2026)** introduced **Finalmask** — a unified obfuscation layer applied to all xray-generated proxy traffic before it reaches the wire. Unlike protocol-level mimicry (item 13, item 26), Finalmask operates at the byte-stream level, wrapping any transport.

#### Finalmask Types

| Type | Target | Effect |
|------|--------|--------|
| `header-custom` | TCP + UDP | Prepend/append custom byte sequences; `randRange` for size randomization |
| `Sudoku` | TCP + UDP | Puzzle-based payload transformation — produces structured but non-repeating byte patterns |
| `fragment` | TCP (from Freedom outbound) | Port-level fragmentation via `tcp_frag` parameters |
| `noise` | UDP | Random noise injection between real packets; `randRange` controls noise size |

#### Configuration Example (XHTTP + Finalmask)

```json
{
  "transport": {
    "type": "xhttp",
    "mode": "auto",
    "xPaddingBytes": "100-1000"
  },
  "finalmask": {
    "type": "header-custom",
    "header": "474554202f20485454502f312e310d0a",
    "randRange": "0-64"
  }
}
```

`randRange` adds 0–64 random bytes after the header per packet — breaks size-distribution ML classifiers without fixed overhead.

#### XHTTP/3 with BBR + udpHop

xray-core v26.3.27 unified `quicParams` brings together congestion control, port hopping, and Finalmask for QUIC:

```json
{
  "transport": { "type": "xhttp", "mode": "packet-up" },
  "quicParams": {
    "congestion": "bbr",
    "udpHop": {
      "ports": "20000-50000",
      "interval": "30s"
    }
  },
  "finalmask": { "type": "noise", "randRange": "16-128" }
}
```

Port hopping every 30s breaks UDP flow tracking; noise injection randomizes inter-packet timing.

#### WireGuard + Finalmask (FullCone NAT)

v26.3.27 added full UDP FullCone NAT to WireGuard inbound + outbound, combined with Finalmask `noise`:

```json
{
  "protocol": "wireguard",
  "settings": { "fullcone": true },
  "finalmask": {
    "type": "noise",
    "randRange": "8-32"
  }
}
```

This makes WireGuard traffic look like generic UDP noise. AmneziaWG provides similar junk injection at the WG protocol level; Finalmask adds an outer wrapper on top.

#### REALITY Warnings (v26.3.27)

- Warning printed at startup if using non-443 ports for REALITY (middlebox L3 detection: cert origin vs ASN mismatch)
- Warning if using Apple/iCloud/`apple.com` as `dest` — Apple operates from AS714; Hetzner/Aeza IPs serving Apple certs are flagged at middlebox Layer 3
- `maxUselessRecords` now auto-probed in 4 tiers on server startup (previously manual)

#### RIPDPI Integration Path

Finalmask is a server-side xray-core feature; RIPDPI as Android client does not implement Finalmask directly. Relevant for:
- Documenting compatible server configurations in item 29 (Russian Cloud Relay)
- Ensuring RIPDPI's xHTTP client (item 25) correctly negotiates with Finalmask-enabled servers

**Reference:** [xray-core v26.3.27 changelog](https://github.com/XTLS/Xray-core/releases/tag/v26.3.27)

**Root required:** No (server-side) | **Effort:** Low (config update) | **Priority:** P1

---

### 31. AmneziaWG PayloadGen — Protocol Imitation for Junk Packets

AmneziaWG's junk-packet injection (`Jc` count, `Jmin`/`Jmax` size, `S1`/`S2`/`H1`/`H2` offsets) uses random bytes by default. **PayloadGen** (ntc.party thread #23602, April 9, 2026) is a community web tool that generates junk payloads that **mimic real protocol patterns**, making AmneziaWG traffic look like legitimate UDP traffic to ML classifiers.

#### Protocol Imitation Modes

| Mode | Mimics | Key details |
|------|--------|-------------|
| `QUIC v1` | RFC 9001-compliant QUIC Initial | Correct encryption key derivation (fixed April 2026), valid QUIC header structure |
| `TLS 1.3 ClientHello` | Browser TLS ClientHello | Extension ordering, GREASE, SNI field |
| `HTTP/2 HEADERS` | Chrome H2 HEADERS frame | HPACK-compressed pseudo-headers |
| `DNS` | DNS query | Valid query structure; "AWG Split" mode randomizes QNAME bytes |
| `MQTT CONNECT` | IoT MQTT CONNECT packet | Protocol name + client ID patterns |

#### AmneziaWG Config with Protocol Imitation

```ini
[Interface]
PrivateKey = <key>
Address = 10.x.x.x/32
DNS = 1.1.1.1

# Junk injection parameters:
Jc = 4          # number of junk packets before handshake
Jmin = 40       # min junk packet size
Jmax = 70       # max junk packet size
S1 = 0          # byte offset for first substitution
S2 = 0          # byte offset for second substitution
H1 = 1          # header byte 1
H2 = 4          # header byte 2

[Peer]
PublicKey = <server-key>
Endpoint = <server-ip>:51820
AllowedIPs = 0.0.0.0/0
```

PayloadGen outputs `S1`, `S2`, `H1`, `H2` values calibrated to make junk packets match the chosen protocol's byte patterns.

#### April 2026 Bug Fixes in PayloadGen

PayloadGen v0.x (April 2026) fixed:
- Incorrect QUIC encryption key derivation (was using wrong initial salt)
- Wrong hex payload lengths causing malformed packets
- DNS "AWG Split" mode randomizing wrong byte offsets
- Authentication tag corruption in mixed imitation modes

#### Current Status

PayloadGen is still in active development (alpha). Validate generated configs against real DPI before production use. The QUIC imitation mode is the most effective against middlebox ML classifiers (QUIC on port 443/UDP is whitelisted on some operators).

#### RIPDPI Integration

AmneziaWG is already item 2 in the roadmap. This item adds:
- **Preset junk configurations** using PayloadGen protocol-imitation outputs (QUIC v1 mode recommended)
- **Config field:** expose `Jc`, `Jmin`, `Jmax`, `S1`, `S2`, `H1`, `H2` in RIPDPI AmneziaWG settings with preset selector (Random / QUIC-imitation / TLS-imitation)
- **Auto-selection:** detect operator from SIM card MCC/MNC → pick imitation mode known to work for that operator's DPI equipment

**Reference:** [AmneziaWG PayloadGen tool](https://ntc.party/t/23602) — ntc.party thread #23602, April 9, 2026

**Root required:** No | **Effort:** Low | **Priority:** P1

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
| 25 | xHTTP transport for VLESS | No | High | Very High | P0 |
| 26 | JA3/JA4 Chrome fingerprint — all TLS | No | High | Very High | P0 |
| 27 | IP correlation defense — dual-IP VPS | No | Low | High | P1 |
| 28 | Per-app package name regex routing | No | Low–Medium | High | P1 |
| 29 | Russian cloud relay preset | No | Medium | Very High | P1 |
| 30 | xray-core Finalmask obfuscation | No | Low | High | P1 |
| 31 | AmneziaWG PayloadGen protocol imitation | No | Low | Medium | P1 |

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
| [xray-core](https://github.com/XTLS/Xray-core) | Go | Finalmask obfuscation, XHTTP/3 + udpHop, REALITY; v26.3.27 |
| [amneziawg-android](https://github.com/amnezia-vpn/amneziawg-android) | Kotlin | AmneziaWG Android client; junk-packet parameters Jc/Jmin/Jmax |
| [Psiphon tunnel-core](https://github.com/Psiphon-Labs/psiphon-tunnel-core) | Go | Obfuscated proxy tunnel; v2.0.37 (April 7, 2026) |
| [sing-box](https://github.com/SagerNet/sing-box) | Go | v1.13.7 stable / v1.14.0-alpha.10; cloudflared inbound, package_name_regex |

## Sources

- Zapret community forum (evgen-dev.ddns.net) -- Cloudflare WARP thread, 64+ posts
- NTC party forum (ntc.party) -- bypass methods 2025--2026 discussions, thread 22584 (MAX surveillance), thread 22880 (MTS VPN detection), thread 21161 (zapret2 discussion, 805+ replies)
- net4people/bbs (GitHub) -- ECH blocking (#417), middlebox new methods (#490), mobile whitelists (#516)
- Cloudflare blog -- MASQUE architecture, WARP protocol details, Zero Trust WARP
- USENIX Security 2025 -- QUIC censorship evasion paper
- Habr (March 12, 2026) -- [Как ТСПУ ловит VLESS: 4-слойная классификация и xHTTP](https://habr.com/ru/articles/1009542/)
- Habr (March 14, 2026) -- [Анатомия DPI: первые 16 КБ пакета](https://habr.com/ru/articles/1009560/)
- Habr (April 9, 2026) -- [4-слойная VPN архитектура против вайтлиста](https://habr.com/ru/articles/1021160/)
- ntc.party thread 23605 -- Telegram TLS JA3 fingerprint blocking, 142 replies, April 2026
- ntc.party thread 23690 -- IP correlation deanonymization attack + sing-box dual-IP mitigation, 163 replies
- ntc.party thread 24016 -- sing-box 1.14.0 package_name_regex Android routing, April 2026
- ntc.party thread 13855 -- xHTTP transport field testing, 426+ replies, active April 2026
- net4people/bbs #603 -- Snowflake DTLS JA3 filtering (March 30, 2026) + CovertDTLS fix
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
- xray-core v26.3.27 changelog (March 27, 2026) -- Finalmask, XHTTP/3 quicParams, WireGuard FullCone NAT, REALITY warnings
- sing-box v1.14.0-alpha.8 (March 30, 2026) -- Hysteria2 BBR + hop interval randomization
- sing-box v1.14.0-alpha.10 (April 10, 2026) -- cloudflared inbound, evaluate DNS action, package_name_regex
- sing-box v1.13.7 stable (April 10, 2026) -- current stable branch
- ntc.party thread 23602 -- AmneziaWG PayloadGen protocol imitation tool (QUIC/TLS/HTTP2/DNS/MQTT modes), April 9, 2026
- Telegram Desktop 6.7.2 / Android 12.6.4 -- ECH extension fix (0xFE02 → 0xFE0D), April 4-6, 2026
- Psiphon tunnel-core v2.0.37 (April 7, 2026) -- active development, no public technical changelog
- Zona.media: Russian internet censorship in 2026 -- three-tier blocking architecture, middlebox ML deployment
- Xakep.ru: middlebox upgrade to 954 Tbit/s by 2030 -- 14.9B RUB budget, 2.5M+ rules, March 22-23 overload event
- net4people/bbs #516 -- Beeline mobile whitelist IP-level filtering confirmed (TCP SYN pass, ClientHello drop)
- Meduza (April 7, 2026) -- Russian marketplaces (Wildberries, Ozon, Sber) begin blocking VPN users
