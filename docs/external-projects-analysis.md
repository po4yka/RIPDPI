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
| Last analyzed | 2026-03-12 (commit: HEAD at time of analysis, status refreshed after activation windows, adaptive split placement, HTTP parser evasions, adaptive fake TTL, and fake approximation ports) |

**Ideas extracted:**

| # | Idea | Status | Notes |
|---|------|--------|-------|
| 1 | Semantic marker system for protocol-aware offsets (`host`, `endhost`, `sld`, `midsld`, `endsld`, `method`, `extlen`, `sniext`) | IMPLEMENTED | RIPDPI now has a structured marker model in the vendored Rust stack and Android persistence layer. Offsets are no longer just numeric or host/SNI toggles; they resolve the same semantic positions zapret2 uses for HTTP/TLS-aware splitting and record placement. |
| 2 | Ordered multi-step strategy chains | IMPLEMENTED | RIPDPI now stores and executes explicit TCP/UDP chains instead of a single `desyncMethod`. Supported TCP steps are `split`, `disorder`, `fake`, `oob`, `disoob`, and `tlsrec`; supported UDP step is `fake_burst(count)`. `tlsrec` is enforced as a prelude-only step in v1. |
| 3 | Chain persistence, compatibility projection, and summary surface | IMPLEMENTED | The zapret2-style composition model is now carried through AppSettings, JNI JSON, diagnostics, and Android UI. RIPDPI persists structured chains, projects legacy `desync_method`/marker fields for compatibility, and renders deterministic summaries like `tcp: tlsrec(extlen) -> fake(host) -> split(midsld)`. |
| 4 | Hybrid raw authoring for strategy composition | IMPLEMENTED | RIPDPI does not embed Lua, but it now exposes a structured chain DSL with `[tcp]` and `[udp]` sections that maps into typed storage. This gives a zapret2-like authoring surface without adding a runtime scripting engine. |
| 5 | QUIC Initial parsing/decryption for hostname-aware UDP handling | IMPLEMENTED | RIPDPI now decrypts QUIC v1/v2 Initial packets, defragments CRYPTO frames, extracts the embedded ClientHello, and uses the recovered SNI for UDP route selection, host filters, cache records, and telemetry. On top of that base, RIPDPI now also ships QUIC fake Initial profile generation for UDP fake bursts. |
| 6 | Host-targeted fake chunks around HTTP Host / TLS SNI (`hostfakesplit` core) | IMPLEMENTED | RIPDPI now has a dedicated `hostfake` TCP chain step that resolves `host..endhost`, emits fake host chunks around the real span, optionally splits the real host once at a semantic marker, and safely degrades to normal split/write behavior when the host span is not usable. |
| 7 | Richer fake TLS mutation pipeline (`rndsni`, `dupsid`, `padencap`, `orig`, `rand`, size tuning) | IMPLEMENTED | RIPDPI now supports the practical socket-layer TLS fake mutations that map well from zapret2: randomized SNI mode, duplicated Session ID, padding-aware encapsulation, original-ClientHello base mode, generalized TLS fake size handling, and the existing fixed fake SNI / fake offset path. |
| 8 | Host autolearn / autohostlist-style preferred-group memory and penalties | IMPLEMENTED | RIPDPI now learns preferred TCP groups per normalized HTTP Host / TLS SNI, persists that state across restarts, penalizes failing groups on trigger conditions, and exposes the learned-host state through Android settings and diagnostics. |
| 9 | QUIC fake Initial profiles (`compat_default`, `realistic_initial`) | IMPLEMENTED | RIPDPI now generates QUIC fake payloads for UDP `fake_burst` using either a zapret-compatible compatibility blob or a realistic IETF QUIC Initial built from the production QUIC Initial encoder with optional fake host override. |
| 10 | Built-in fake payload profile library for HTTP/TLS/UDP | IMPLEMENTED | RIPDPI no longer relies on a single default HTTP/TLS/UDP fake payload. It now ships a curated built-in profile library, selectable through CLI, Android settings, JNI/native config, and diagnostics, while still preserving raw custom fake payloads as the highest-priority override. |
| 11 | Blockcheck-style automatic probing and recommendation flow | IMPLEMENTED | RIPDPI diagnostics now includes a separate `Automatic probing` profile that runs a fixed raw-path candidate suite across HTTP, HTTPS, and QUIC, scores TCP and QUIC candidates separately, and returns a manual recommendation plus scoreboard rather than silently changing user settings. |
| 12 | Range-based filtering (packet count, data size, sequence offset) | IMPLEMENTED | RIPDPI now has structured activation windows at both group and per-step levels across TCP and UDP. The model uses round, payload-size, and outbound stream-byte ranges instead of raw TCP sequence numbers, but it covers the practical selectivity surface that maps cleanly into RIPDPI's proxy architecture. |
| 13 | Lua-based runtime scripting engine | NOT IMPLEMENTED | RIPDPI intentionally chose typed Rust/Kotlin structures over a Lua runtime. This keeps the Android/JNI surface smaller and safer, but it means users cannot define arbitrary runtime packet logic the way zapret2 can. |
| 14 | Multi-instance processing pipeline architecture | NOT IMPLEMENTED | RIPDPI still uses its current local proxy/VPN routing model rather than independent parallel desync instances in the zapret2 sense. |
| 15 | Automatic segmentation without manual MSS configuration | IMPLEMENTED | RIPDPI now supports adaptive `auto(...)` markers that use live `TCP_INFO` segment hints (`snd_mss`, `advmss`, `pmtu`) plus semantic HTTP/TLS markers to pick concrete split boundaries per payload. The feature stays marker-driven and TCP-only in v1, but it eliminates the need for manual split placement in common cases. |
| 16 | Partial `fakedsplit` / `fakeddisorder` approximations | IMPLEMENTED (PARTIAL) | RIPDPI now ships `fakedsplit` and `fakeddisorder` TCP chain steps as Linux/Android-focused approximations built on the existing fake-send/retransmission primitive. They intentionally do not reproduce zapret2's raw sequence-overlap parity, but they approximate fake/retransmission ambiguity with the existing fake payload library, fake TLS mutations, fake offset, and adaptive fake TTL pipeline. |
| 17 | Aggressive HTTP parser evasions (`http_methodeol`, `http_unixeol`) | IMPLEMENTED | RIPDPI now ships zapret-style Method-EOL and Unix-EOL HTTP request mutations on top of the existing Host/domain/space tweaks. They are exposed as explicit aggressive Android toggles, included in diagnostics signatures, and exercised by dedicated automatic-probing candidates instead of being silently folded into the safe parser profile. |
| 18 | Adaptive fake TTL (`autottl`) | IMPLEMENTED (PARTIAL) | RIPDPI now supports a hybrid `auto_ttl` design: native config and CLI parity, runtime-scoped host/address keyed learning, deterministic candidate search, Android UI/diagnostics, and reuse across fake packets, `hostfake`, and `disoob`. It does not yet compute zapret2-style TTL from observed reply-hop counts, so the current implementation is a practical approximation rather than full parity. |

---

### 2. rethink-app

| Field | Value |
|-------|-------|
| Repository | [celzero/rethink-app](https://github.com/celzero/rethink-app) |
| Description | Android firewall + DNS changer with per-app rules and encrypted DNS |
| Language | Kotlin, Golang |
| Last analyzed | 2026-03-12 (status refreshed after encrypted DNS, resolver telemetry, and diagnostics-driven resolver selection landed in RIPDPI) |

**Ideas extracted:**

| # | Idea | Status | Notes |
|---|------|--------|-------|
| 1 | DNS encryption (DoH/DoT/DNSCrypt) as first defense layer | IMPLEMENTED | RIPDPI now ships a shared native encrypted DNS resolver reused by diagnostics and VPN mode. VPN sessions can switch to mapped-DNS interception with DoH/DoT/DNSCrypt, while connectivity diagnostics can validate and recommend encrypted resolvers before escalating to stronger packet-level bypass. Built-in auto-selection is DoH-first in v1; custom DoT and DNSCrypt are supported manually. |
| 2 | Per-application network controls and state-aware firewall rules | NOT IMPLEMENTED | rethink-app allows users to set network rules per app (block WiFi, allow cellular, etc.). RIPDPI still applies desync uniformly. Per-app routing remains a plausible next step if the app needs coarse exemptions or app-specific strategies. |
| 3 | Kotlin + Golang (`firestack`) network stack integration pattern | REFERENCE | The useful lesson was architectural, not language-specific: keep the network data plane native and let Android orchestrate it. RIPDPI kept its Rust + JNI stack, but followed the same "thin Android wrapper over native networking core" direction rather than moving resolver logic into Kotlin. |
| 4 | Encrypted Client Hello (ECH) for hiding TLS SNI | NOT IMPLEMENTED | ECH remains attractive as a protocol-level complement to desync, but RIPDPI's current encrypted DNS stack does not make third-party app traffic use ECH. The current implementation preserves encrypted DNS and resolver metadata; it does not add an ECH-capable client path. |
| 5 | Domain/IP category-based filtering | NOT IMPLEMENTED | rethink-app categorizes domains (ads, trackers, malware, social media) and applies rules by category. RIPDPI still works in terms of host/IP lists and bypass strategies rather than category policy. |
| 6 | Resolver and transport telemetry expansion through existing polled snapshots | IMPLEMENTED | RIPDPI expanded the existing native JSON snapshot model instead of adding callback-heavy JNI. Tunnel/runtime telemetry now carries resolver endpoint, query latency, rolling average, failure count, fallback state/reason, and network handover classification, and that data is persisted into diagnostics history and archive output. |
| 7 | Diagnostics-driven resolver selection before stronger bypass escalation | IMPLEMENTED | Connectivity diagnostics now expand built-in encrypted resolvers, rank them by `dns_match` count and latency, and can apply a temporary session-local encrypted DNS override when VPN is running on plain UDP DNS. The diagnostics UI surfaces this recommendation ahead of strategy probing, with `Keep for this session` and `Save as DNS setting` actions. |

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
| Language | Python |
| Last analyzed | 2026-03-11 |

**Ideas extracted:**

| # | Idea | Status | Notes |
|---|------|--------|-------|
| 1 | Random ClientHello fragmentation with variable-length segments | IMPLEMENTED | RIPDPI now supports `tlsrandrec` in the structured TCP chain model and raw DSL, allowing multiple randomized TLS record splits after a semantic marker while preserving safe no-op behavior when a layout cannot be applied. |
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
| Adaptive fake TTL / autottl | Yes | -- | -- | -- | IMPLEMENTED (PARTIAL) |
| TLS record splitting | Yes | -- | -- | -- | IMPLEMENTED |
| Host-targeted fake chunks | Yes | -- | -- | -- | IMPLEMENTED |
| TLS version spoofing | -- | -- | -- | Yes | IMPLEMENTED |
| HTTP header manipulation | Yes | -- | -- | -- | IMPLEMENTED |
| Aggressive HTTP parser evasions | Yes | -- | -- | -- | IMPLEMENTED |
| Auto-detect & group switching | Yes | -- | -- | -- | IMPLEMENTED |
| Host autolearn / autohostlist | Yes | -- | -- | -- | IMPLEMENTED |
| TCP cutoff detection | -- | -- | Yes | -- | IMPLEMENTED |
| SNI whitelist discovery | -- | -- | Yes | -- | IMPLEMENTED |
| Raw chain DSL authoring | Yes | -- | -- | -- | IMPLEMENTED |
| QUIC Initial parsing/decryption | Yes | -- | -- | -- | IMPLEMENTED |
| QUIC-aware UDP host routing | Yes | -- | -- | -- | IMPLEMENTED |
| QUIC fake Initial profiles | Yes | -- | -- | -- | IMPLEMENTED |
| Fake payload profile library | Yes | -- | -- | -- | IMPLEMENTED |
| Diagnostic-first mode | -- | -- | Yes | -- | IMPLEMENTED |
| Lua/scripting for strategies | Yes | -- | -- | -- | NOT IMPLEMENTED |
| DNS encryption (DoH/DoT/DNSCrypt) | -- | Yes | -- | -- | IMPLEMENTED |
| Polled resolver telemetry expansion | -- | Yes | -- | -- | IMPLEMENTED |
| Diagnostics-driven DNS selection | -- | Yes | Partial | -- | IMPLEMENTED |
| Per-app network rules | -- | Yes | -- | -- | NOT IMPLEMENTED |
| ECH support | -- | Yes | -- | -- | NOT IMPLEMENTED |
| Category-based filtering | -- | Yes | -- | -- | NOT IMPLEMENTED |
| Auto MSS/segmentation | Yes | -- | -- | -- | IMPLEMENTED |
| Partial fakedsplit/fakeddisorder approximations | Yes | -- | -- | -- | IMPLEMENTED (PARTIAL) |
| Multi-fragment random split | -- | -- | -- | Yes | IMPLEMENTED |
| Granular error classification | -- | -- | Yes | -- | PARTIAL |
| Binary fake packet templates | Yes | -- | -- | -- | IMPLEMENTED |

### Priority Ideas (high impact, feasible)

1. **ECH support** -- protocol-level SNI hiding still complements the new encrypted DNS stack and could eliminate desync for servers that support it
2. **Per-app desync routing / policy controls** -- rethink-app's strongest still-missing product idea; useful for exempting trusted apps or limiting bypass to selected traffic
3. **Granular error classification** -- distinguish RST/abort/MITM/SNI-block for better auto-strategy selection; incremental improvement to existing diagnostics and automatic probing
4. **True reply-TTL-backed autottl parity** -- the current adaptive fake TTL implementation is practical and product-ready, but it still learns by runtime outcomes rather than deriving hop-count-aware TTL from observed replies the way zapret2 can on raw packet paths
5. **Additional UDP-specific evasions** -- zapret2 still has niche UDP mutations like `udplen`, `dht_dn`, and related protocol-shaped tricks that fit RIPDPI's proxy architecture better than raw sequence hacks and could complement the current QUIC fake-profile path

### RIPDPI-native Ports Landed Since The Initial Analysis

- semantic markers and ordered TCP/UDP chain DSL
- host-targeted fake chunks plus partial `fakedsplit` / `fakeddisorder`
- richer fake TLS mutation controls and built-in fake payload profile libraries
- QUIC fake Initial profiles
- host autolearn and diagnostics-side automatic probing
- activation windows, adaptive split placement, aggressive HTTP parser evasions, and adaptive fake TTL

### Exploratory Ideas (interesting but larger scope)

- Lua/scripting engine for user-defined strategies
- Category-based domain filtering
- More niche zapret2 UDP/protocol-specific mutations beyond QUIC
