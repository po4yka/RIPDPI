# External Projects Analysis

Tracking ideas and techniques from related open-source DPI bypass projects.
Each entry records what was analyzed, when, and which ideas are new vs already
implemented in RIPDPI. Re-analyze when a project's commit hash advances
significantly.

This is a research and planning document, not a committed roadmap.
The baseline assumed by this analysis is the current RIPDPI architecture:

- in-repository Rust native modules
- integrated diagnostics and passive telemetry
- local-network E2E and soak-oriented native test coverage

---

## Projects

### 1. zapret2

| Field | Value |
|-------|-------|
| Repository | [bol-van/zapret2](https://github.com/bol-van/zapret2) |
| Description | DPI circumvention tool for Linux/OpenWRT/FreeBSD with nfqueue/tproxy |
| Language | C, Lua |
| Last analyzed | 2026-03-11 (commit: HEAD at time of analysis, status refreshed after RIPDPI marker/chain/QUIC port) |

**Ideas extracted:**

| # | Idea | Status | Notes |
|---|------|--------|-------|
| 1 | Semantic marker system for protocol-aware offsets (`host`, `endhost`, `sld`, `midsld`, `endsld`, `method`, `extlen`, `sniext`) | IMPLEMENTED | RIPDPI now has a structured marker model in the vendored Rust stack and Android persistence layer. Offsets are no longer just numeric or host/SNI toggles; they resolve the same semantic positions zapret2 uses for HTTP/TLS-aware splitting and record placement. |
| 2 | Ordered multi-step strategy chains | IMPLEMENTED | RIPDPI now stores and executes explicit TCP/UDP chains instead of a single `desyncMethod`. Supported TCP steps are `split`, `disorder`, `fake`, `oob`, `disoob`, and `tlsrec`; supported UDP step is `fake_burst(count)`. `tlsrec` is enforced as a prelude-only step in v1. |
| 3 | Chain persistence, compatibility projection, and summary surface | IMPLEMENTED | The zapret2-style composition model is now carried through AppSettings, JNI JSON, diagnostics, and Android UI. RIPDPI persists structured chains, projects legacy `desync_method`/marker fields for compatibility, and renders deterministic summaries like `tcp: tlsrec(extlen) -> fake(host) -> split(midsld)`. |
| 4 | Hybrid raw authoring for strategy composition | IMPLEMENTED | RIPDPI does not embed Lua, but it now exposes a structured chain DSL with `[tcp]` and `[udp]` sections that maps into typed storage. This gives a zapret2-like authoring surface without adding a runtime scripting engine. |
| 5 | QUIC Initial parsing/decryption for hostname-aware UDP handling | IMPLEMENTED | RIPDPI now decrypts QUIC v1/v2 Initial packets, defragments CRYPTO frames, extracts the embedded ClientHello, and uses the recovered SNI for UDP route selection, host filters, cache records, and telemetry. This is intentionally scoped to routing/host-awareness in v1; it does not add a separate user-visible QUIC mode or QUIC-specific chain steps yet. |
| 6 | Binary blob support for custom fake packets (TLS/HTTP/QUIC templates) | PARTIAL | RIPDPI already supports custom fake payload blobs and fake modifiers. zapret2 still goes further with more curated, protocol-specific template sets, especially around realistic TLS/QUIC fake payloads. |
| 7 | Range-based filtering (packet count, data size, sequence offset) | PARTIAL | RIPDPI now has richer chain composition and QUIC-aware UDP routing, but its activation filters are still closer to rounds/protocol/port/host routing than zapret2's deeper per-flow/sequence/data-volume controls. |
| 8 | Lua-based runtime scripting engine | NOT IMPLEMENTED | RIPDPI intentionally chose typed Rust/Kotlin structures over a Lua runtime. This keeps the Android/JNI surface smaller and safer, but it means users cannot define arbitrary runtime packet logic the way zapret2 can. |
| 9 | Multi-instance processing pipeline architecture | NOT IMPLEMENTED | RIPDPI still uses its current local proxy/VPN routing model rather than independent parallel desync instances in the zapret2 sense. |
| 10 | Automatic segmentation without manual MSS configuration | NOT IMPLEMENTED | Marker-aware chains improve split targeting, but RIPDPI still relies on explicit split markers rather than inferred MSS/MTU-aware auto-segmentation. |

---

### 2. rethink-app

| Field | Value |
|-------|-------|
| Repository | [celzero/rethink-app](https://github.com/celzero/rethink-app) |
| Description | Android firewall + DNS changer with per-app rules and encrypted DNS |
| Language | Kotlin, Golang |
| Last analyzed | 2026-03-11 |

**Ideas extracted:**

| # | Idea | Status | Notes |
|---|------|--------|-------|
| 1 | DNS encryption (DoH/DoT/DNSCrypt) as first defense layer | NEW | RIPDPI diagnostics compare UDP DNS vs DoH for integrity checks, but don't offer encrypted DNS as a bypass layer. DNS manipulation is often the first blocking technique ISPs deploy -- offering built-in DoH/DoT would cover this attack surface. |
| 2 | Per-application network controls and state-aware firewall rules | NEW | rethink-app allows users to set network rules per app (block WiFi, allow cellular, etc.). RIPDPI applies desync uniformly. Per-app routing could let users exempt trusted apps or apply different desync strategies per app. |
| 3 | Kotlin + Golang (`firestack`) network stack integration pattern | REFERENCE | Interesting architectural parallel. rethink-app uses Golang via gomobile for its network stack, while RIPDPI uses Rust via JNI. Both solve the same problem (high-performance native networking on Android) differently. Golang offers easier concurrency; Rust offers better memory safety guarantees. No action needed, but useful reference if evaluating alternative native approaches. |
| 4 | Encrypted Client Hello (ECH) for hiding TLS SNI | NEW | ECH encrypts the SNI field in TLS ClientHello, making SNI-based blocking impossible. rethink-app supports ECH via its DNS stack. This is a protocol-level solution that complements RIPDPI's packet-level desync -- if the server supports ECH, no desync is needed for SNI hiding. |
| 5 | Domain/IP category-based filtering | NEW | rethink-app categorizes domains (ads, trackers, malware, social media) and applies rules by category. RIPDPI uses host/IP whitelist/blacklist. Category-based rules would let users say "apply desync to all social media" rather than listing individual domains. |

---

### 3. dpi-detector

| Field | Value |
|-------|-------|
| Repository | [Runnin4ik/dpi-detector](https://github.com/Runnin4ik/dpi-detector) |
| Description | Tool for detecting and classifying DPI blocking mechanisms |
| Language | Go |
| Last analyzed | 2026-03-11 |

**Ideas extracted:**

| # | Idea | Status | Notes |
|---|------|--------|-------|
| 1 | Runtime DPI blocking mechanism detection and classification | IMPLEMENTED | `ripdpi-monitor` already performs active diagnostics: DNS integrity, TLS probes, HTTP block-page detection, and TCP cutoff analysis. |
| 2 | TCP 16-20 KB cutoff detection technique | IMPLEMENTED | `ripdpi-monitor` sends repeated large HEAD requests (16 requests, 16 KB threshold) to detect volumetric TCP cutoffs. Matches dpi-detector's approach. |
| 3 | SNI whitelist discovery for blocked providers | IMPLEMENTED | `ripdpi-monitor` includes whitelist SNI retry probes that test alternate SNIs to find working domains on blocked IPs. |
| 4 | Error classification (TCP RST, abort, handshake timeout, TLS MITM, SNI-blocking) | PARTIAL | RIPDPI's `auto_level` detects `torst` (timeout/reset), `redirect` (HTTP 3xx), and `ssl_err` (TLS error). dpi-detector has more granular classification: distinguishing TCP RST from connection abort, identifying TLS MITM certificates specifically, and differentiating SNI-block from IP-block. Finer classification could improve auto-strategy selection. |
| 5 | Adaptive bypass strategy selection based on detected blocking type | PARTIAL | RIPDPI's `auto_level` switches desync groups on failure and caches successful group+IP combos. dpi-detector's approach is more systematic: run full detection suite first, then recommend specific bypass techniques per blocking type. A "diagnostic-first" mode could pre-select optimal desync groups before the user even tries to connect. |

---

### 4. NoDPI

| Field | Value |
|-------|-------|
| Repository | [GVCoder09/NoDPI](https://github.com/GVCoder09/NoDPI) |
| Description | Lightweight Windows DPI bypass tool with minimal dependencies |
| Language | C# |
| Last analyzed | 2026-03-11 |

**Ideas extracted:**

| # | Idea | Status | Notes |
|---|------|--------|-------|
| 1 | Random ClientHello fragmentation with variable-length segments | PARTIAL | RIPDPI supports splitting at fixed/SNI-relative offsets and `+r` (random position). NoDPI generates multiple random-length fragments per ClientHello rather than splitting at a single point. Multi-fragment random splitting could make patterns harder for DPI to fingerprint. |
| 2 | SNI-based fragmentation (4-section split based on SNI location) | IMPLEMENTED | RIPDPI's offset expressions (`+s`, `+e`, `+m` for SNI start/end/mid) with repeat syntax (`1:3:5`) already support multi-point SNI-aware splitting. |
| 3 | TLS version spoofing to create malformed packets DPI ignores | IMPLEMENTED | RIPDPI's `tlsminor` option changes the TLS minor version byte in record headers, achieving the same effect. |
| 4 | Minimal-dependency, no-admin-privilege design philosophy | REFERENCE | NoDPI runs without admin rights on Windows. RIPDPI requires VPN permission on Android (standard for all VPN-based tools). The principle of minimizing required permissions is worth keeping in mind -- avoid requesting unnecessary Android permissions. |

---

## Cross-Project Insights

| Technique | zapret2 | rethink-app | dpi-detector | NoDPI | RIPDPI Status |
|-----------|---------|-------------|--------------|-------|---------------|
| Packet splitting/fragmentation | Yes | -- | -- | Yes | IMPLEMENTED |
| Semantic marker offsets | Yes | -- | -- | Partial | IMPLEMENTED |
| Ordered multi-step strategy chains | Yes | -- | -- | Partial | IMPLEMENTED |
| Fake packets (TTL/MD5) | Yes | -- | -- | -- | IMPLEMENTED |
| TLS record splitting | Yes | -- | -- | -- | IMPLEMENTED |
| TLS version spoofing | -- | -- | -- | Yes | IMPLEMENTED |
| HTTP header manipulation | Yes | -- | -- | -- | IMPLEMENTED |
| Auto-detect & group switching | Yes | -- | -- | -- | IMPLEMENTED |
| TCP cutoff detection | -- | -- | Yes | -- | IMPLEMENTED |
| SNI whitelist discovery | -- | -- | Yes | -- | IMPLEMENTED |
| Raw chain DSL authoring | Yes | -- | -- | -- | IMPLEMENTED |
| QUIC Initial parsing/decryption | Yes | -- | -- | -- | IMPLEMENTED |
| QUIC-aware UDP host routing | Yes | -- | -- | -- | IMPLEMENTED |
| Lua/scripting for strategies | Yes | -- | -- | -- | NOT IMPLEMENTED |
| DNS encryption (DoH/DoT) | -- | Yes | -- | -- | NOT IMPLEMENTED |
| Per-app network rules | -- | Yes | -- | -- | NOT IMPLEMENTED |
| ECH support | -- | Yes | -- | -- | NOT IMPLEMENTED |
| Category-based filtering | -- | Yes | -- | -- | NOT IMPLEMENTED |
| Diagnostic-first mode | -- | -- | Yes | -- | NOT IMPLEMENTED |
| Auto MSS/segmentation | Yes | -- | -- | -- | NOT IMPLEMENTED |
| Multi-fragment random split | -- | -- | -- | Yes | NOT IMPLEMENTED |
| Granular error classification | -- | -- | Yes | -- | PARTIAL |
| Binary fake packet templates | Yes | -- | -- | -- | PARTIAL |

### Priority Ideas (high impact, feasible)

1. **DNS encryption (DoH/DoT)** -- covers the most common first-layer blocking; complements existing packet-level bypass
2. **Diagnostic-first mode** -- run detection suite before connecting, pre-select optimal desync group; builds on existing `ripdpi-monitor` infrastructure
3. **ECH support** -- protocol-level SNI hiding eliminates need for desync when server supports it; future-proof
4. **Granular error classification** -- distinguish RST/abort/MITM/SNI-block for better auto-strategy selection; incremental improvement to existing `auto_level`
5. **Auto segmentation** -- now that semantic markers, multi-step chains, and QUIC-aware UDP routing are implemented, the next zapret2-inspired improvement is automatically choosing split locations/sizes instead of requiring explicit markers

### Exploratory Ideas (interesting but larger scope)

- Lua/scripting engine for user-defined strategies
- Per-app desync routing
- Category-based domain filtering
- Multi-fragment random splitting
- Deeper zapret2-style range filters (sequence/data-volume aware activation)
