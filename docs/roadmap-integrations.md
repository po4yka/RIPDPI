# Integrations and Techniques Roadmap

Prioritized list of integrations and path optimization techniques for RIPDPI, based on research across the Zapret community, NTC forums, GitHub projects, Cloudflare documentation, and academic papers (April 2025 -- June 2025 intelligence window).

## Current Threat Landscape

Russian middlebox (technical censorship infrastructure) capabilities as of mid-2025:

- Full TCP reassembly (basic fragmentation alone no longer sufficient)
- TLS ClientHello inspection with SNI extraction across fragments
- WireGuard protocol fingerprinting and active blocking
- ECH (Encrypted Client Hello) blocking for Cloudflare SNI since November 2024
- Volume-based throttling: connections exceeding 15--20 KB to foreign IPs frozen (not RST, silent drop)
- Emerging **whitelist mode** on some mobile networks: only approved IPs/domains reachable
- ML-based traffic classification by statistical properties (packet timing, sizes, entropy)

### What RIPDPI Already Implements

RIPDPI covers ~90% of known DPI desync techniques: split, disorder, multidisorder, fake (TTL/MD5sig), OOB, disoob, TLS record fragmentation (tlsrec/tlsrandrec), IP fragmentation (ipfrag2), sequence overlap, hostfake, QUIC evasion (fake burst, SNI split, fake version, dummy prepend), adaptive fake TTL, per-network policy memory, tournament bracket strategy probing, and encrypted DNS (DoH/DoT/DNSCrypt).

The primary gap is **tunnel/proxy protocol support** for when desync alone is insufficient, and **domestic relay chaining** for whitelist scenarios.

---

## Tier 1: Cloudflare WARP Integration (P0)

### 1. WARP WireGuard Tunnel

Embed a WireGuard tunnel to Cloudflare WARP endpoints as an alternative upstream path alongside the existing SOCKS5 proxy.

**Registration flow:**
1. Generate WireGuard keypair locally
2. POST public key to `https://api.cloudflareclient.com/v0a737/reg`
3. Receive peer public key, endpoint address, assigned IPv4/IPv6
4. Configure WireGuard tunnel with assigned parameters

**Endpoint:** `engage.cloudflareclient.com:2408` (UDP, standard WireGuard Noise_IK handshake)

**Architecture options:**
- **VPN mode**: VpnService TUN fd -> tun2socks -> SOCKS5 proxy -> WireGuard tunnel -> WARP
- **Hybrid mode**: RIPDPI desync for domestic sites + WARP tunnel for fully blocked sites with rule-based routing
- **Proxy mode**: WireGuard tunnel exposed as local SOCKS5 upstream (no VpnService claim)

**Critical detail:** `api.cloudflareclient.com` is DPI-blocked nationwide. RIPDPI must apply its own desync to the registration request. The existing `multidisorder --split-pos=2` technique works for this.

**Reference projects:** wgcf (Go, registration API), wireproxy (Go, WG-as-SOCKS5), oblivion-android (Java/Go, full Android WARP client), WG Tunnel (Kotlin/Go, Android WireGuard + AmneziaWG)

**Root required:** No | **Effort:** High

### 2. AmneziaWG Obfuscation Layer

Add AmneziaWG parameters on top of the WARP WireGuard tunnel to defeat WireGuard protocol fingerprinting.

Standard WireGuard is trivially detected by DPI: fixed 4-byte type headers (bytes 1--4), predictable packet sizes (148/92/64 bytes). AmneziaWG defeats this with:

| Parameter | Purpose | Typical Values |
|-----------|---------|----------------|
| **Jc** | Junk packets before handshake | 3--10 |
| **Jmin** | Minimum junk packet size | 50--64 bytes |
| **Jmax** | Maximum junk packet size | 1000--1024 bytes |
| **H1--H4** | Random message type headers (replace fixed bytes 1--4) | H1: 100K--800K, H2: 1M--8M, H3: 10M--80M, H4: 100M--800M |
| **S1--S4** | Random padding per packet type | 0--64 bytes |

**Constraint:** S1+56 must not equal S2 (avoids recreating original size relationship).

A standard WireGuard server works with an AmneziaWG client when only Jc/Jmin/Jmax are set (junk packets are ignored by the server).

**Reference:** amnezia-vpn/amneziawg-go (Go), AmneziaWG 2.0 spec

**Root required:** No | **Effort:** Medium (extends WARP integration)

### 3. WARP Domain Hostlist for DPI Desync

Built-in hostlist for Cloudflare WARP domains that require DPI desync to unblock registration and connectivity:

```
api.cloudflareclient.com
connectivity.cloudflareclient.com
engage.cloudflareclient.com
downloads.cloudflareclient.com
zero-trust-client.cloudflareclient.com
pkg.cloudflareclient.com
consumer-masque.cloudflareclient.com
```

**Root required:** No | **Effort:** Low

### 4. WARP Endpoint Scanner

Built-in scanner to find working WARP endpoints per-network. Test connectivity to Cloudflare IP ranges with latency measurement. Mobile endpoints differ from WiFi -- per-network endpoint discovery is essential.

**Integration point:** RIPDPI's existing diagnostics infrastructure (strategy probe pipeline).

**Root required:** No | **Effort:** Medium

---

## Tier 2: DPI Desync Enhancements (P1)

### 5. Auto-Strategy with Trigger-Based Fallback

ByeDPI's `-A <trigger>` model: automatically cycle through strategy groups when connections fail during normal operation (not just diagnostics).

**Triggers:**
- `torst` -- timeout or TCP RST received
- `redirect` -- HTTP redirect to block page
- `ssl_err` -- TLS handshake error
- `none` -- unconditional

Cache successful strategies per-destination with configurable TTL (default 28 hours). RIPDPI already has per-network policy memory; this adds real-time adaptive fallback within a single session.

**Root required:** No | **Effort:** Medium

### 6. HTTP Header Manipulation Steps

Add chain steps for HTTP-layer evasion:

| Step | Description |
|------|-------------|
| `hostcase` | Lowercase `Host:` header (`Host:` -> `host:`) |
| `domcase` | Randomize domain case (`TeSt.cOm`) |
| `methodspace` | Add whitespace after HTTP method |
| `methodeol` | Prepend newline before method line |
| `hostremovespace` | Remove spaces around Host value |

Complements TLS-level desync for plaintext HTTP sites still in use by some blocked resources.

**Root required:** No | **Effort:** Low

### 7. SYN Data (TCP Fast Open Style)

Embed payload data in the SYN packet itself using `TCP_FASTOPEN_CONNECT` socket option. Forces DPI to parse data from the first packet rather than waiting for the 3-way handshake.

**Root required:** No | **Effort:** Low

### 8. IPv6 Extension Header Injection

Inject hop-by-hop options, destination options, or routing headers into IPv6 packets:

- `hopbyhop` -- single hop-by-hop extension header
- `hopbyhop2` -- two hop-by-hop headers (violates RFC, triggers intermediate drops while DPI processes them)
- `destopt` -- destination options header

Effective on IPv6 networks where middlebox processes extension headers before dropping. Complementary to existing IPv6 fragmentation support in `ripdpi-ipfrag`.

**Root required:** VPN mode (raw IP packet access through TUN) | **Effort:** Medium

---

## Tier 3: Proxy Protocol Support (P2)

### 9. VLESS + Reality Client

Implement VLESS protocol with Reality transport as an upstream proxy option.

**Why Reality works:** It does not use self-signed certificates. Instead, it impersonates a real website (e.g., `microsoft.com`, `amazon.com`) using genuine TLS certificates obtained by proxying the TLS handshake to the real site. Only clients with the correct `shortID` and private key complete the handshake. To any observer (including middlebox), the server IS the target website. Detection rate under 5% with proper configuration.

**Architecture:** RIPDPI SOCKS5 proxy -> VLESS+Reality upstream -> user's server -> internet

**Why this matters:** When middlebox switches to whitelist mode, DPI desync fails entirely. VLESS+Reality through a domestic relay is the NTC community's #1 recommendation for 2025--2026.

**Reference:** sing-box (Go, full protocol implementation), v2rayNG (Android reference client)

**Root required:** No | **Effort:** High

### 10. Hysteria2 with Salamander

Implement Hysteria2 as a QUIC-based upstream tunnel option.

**Key features:**
- Masquerades as standard HTTP/3 server
- Authentication via HTTP POST; failed auth serves a real website (defeats active probing)
- **Salamander obfuscation**: per-packet 8-byte random salt + BLAKE2b-256 hash XOR on payload
- Uses QUIC unreliable datagrams for UDP proxying with built-in fragmentation
- Currently working in Russia (confirmed mid-2025)

**Root required:** No | **Effort:** High

### 11. Chain Relay Support

Route traffic through a domestic relay before reaching the foreign proxy server.

```
App -> domestic VPS (Russian IP) -> foreign VPS -> internet
```

middlebox applies less scrutiny to domestic traffic. When whitelist mode is active on mobile networks, a domestic relay on an approved IP is the only escape path.

**Implementation:** Multi-hop proxy chaining in the SOCKS5 upstream path. Could chain with any upstream protocol (VLESS, Hysteria2, plain SOCKS5).

**Root required:** No | **Effort:** Medium

### 12. MASQUE Protocol (HTTP/3 + TCP Fallback)

Implement Cloudflare's MASQUE (Connect-IP over QUIC/HTTP3) as the primary tunnel protocol to WARP.

**Why:** MASQUE runs on port 443, blending with normal HTTPS/HTTP3 traffic. Cloudflare's WARP client v2025.4 added automatic HTTP/2 TCP fallback when UDP is blocked.

**Note:** Cloudflare's implementation uses `cf-connect-ip` instead of standard RFC 9484 `connect-ip`.

**Reference:** usque (Go, open-source MASQUE reimplementation using quic-go)

**Root required:** No | **Effort:** Very High (QUIC + HTTP/3 stack)

---

## Tier 4: Detection Resistance (P3)

### 13. TLS Fingerprint Mimicry

Construct TLS ClientHello to mimic popular browsers (Chrome, Firefox, Safari). JA4+ fingerprinting is now standard at Cloudflare and can distinguish proxy traffic from real browsers.

**Challenge:** rustls explicitly refuses to support ClientHello customization (issue #1932 closed as "not planned").

**Options:**
- Use BoringSSL (`boring` crate, already in RIPDPI dependencies) for customizable ClientHello
- Manual ClientHello byte construction for the first flight only
- Go uTLS via FFI (complex but proven, supports Chrome/Firefox/Safari mimicry)

**Root required:** No | **Effort:** High

### 14. Adaptive Traffic Morphing

Shape VPN/proxy traffic to match the user's normal browsing patterns:
- Learn normal traffic patterns (timing, packet sizes, volume distribution)
- Add padding to small packets to normalize size distribution
- Introduce timing jitter to match browsing cadence
- Cap bandwidth to avoid statistical outliers (64--128 Kbit/s for stealth mode)

RIPDPI already has `EntropyMode` and adaptive infrastructure as a foundation.

**Why:** Next-generation DPI uses ML classifiers that detect tunneled traffic by statistical properties, not just protocol signatures.

**Root required:** No | **Effort:** High

### 15. Updatable Strategy Packs

Allow strategy configurations (desync chains, fake profiles, hostlists, endpoint lists) to be updated via downloadable packs without app updates.

**Why:** middlebox countermeasures evolve faster than app update cycles. The zapret community frequently publishes new configurations. zapret2 is exploring Lua scripting for runtime strategy definition.

**Root required:** No | **Effort:** Medium--High

### 16. QUIC Connection Migration Exploitation

Complete the QUIC handshake over a secure path (relay or CDN), then migrate to a direct connection. QUIC's connection migration changes the 4-tuple (src/dst IP:port) while maintaining the same connection ID, invisible to stateless DPI.

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

## Reference Projects

| Project | Language | Relevance |
|---------|----------|-----------|
| [wgcf](https://github.com/ViRb3/wgcf) | Go | WARP registration API client |
| [wireproxy](https://github.com/windtf/wireproxy) | Go | WireGuard-as-SOCKS5 proxy |
| [oblivion-android](https://github.com/bepass-org/oblivion) | Java/Go | Android WARP client |
| [WG Tunnel](https://github.com/wgtunnel/wgtunnel) | Kotlin/Go | Android WireGuard + AmneziaWG |
| [usque](https://github.com/Diniboy1123/usque) | Go | MASQUE reimplementation |
| [amneziawg-go](https://github.com/amnezia-vpn/amneziawg-go) | Go | AmneziaWG implementation |
| [ByeDPI](https://github.com/hufrea/byedpi) | C | DPI desync reference (auto-mode) |
| [sing-box](https://github.com/SagerNet/sing-box) | Go | VLESS/Hysteria2/TUIC protocols |
| [hysteria](https://github.com/apernet/hysteria) | Go | Hysteria2 + Salamander |
| [uTLS](https://github.com/refraction-networking/utls) | Go | TLS fingerprint mimicry |

## Sources

- Zapret community forum (evgen-dev.ddns.net) -- Cloudflare WARP thread, 64+ posts
- NTC party forum (ntc.party) -- bypass methods 2025--2026 discussions
- net4people/bbs (GitHub) -- ECH blocking (#417), middlebox new methods (#490), mobile whitelists (#516)
- Cloudflare blog -- MASQUE architecture, WARP protocol details
- USENIX Security 2025 -- QUIC censorship evasion paper
- Habr -- adaptive traffic morphing research (March 2026)
- zapret GitHub (bol-van/zapret) -- v72.12 changelog and documentation
