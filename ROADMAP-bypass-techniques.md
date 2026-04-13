# ROADMAP: DPI Bypass Technique Expansion

> Cross-reference analysis between techniques documented as working in the field
> (TechnicalVault/Censorship/) and RIPDPI's current implementation.
> Each item includes rationale, technical approach, and vault references.

## Status Legend

- [ ] Not started
- [x] Already implemented or not applicable

---

## Tier 1 -- Highest ROI

### 1. Circular Strategy Rotation (mid-connection)

**Status:** [x] Implemented (2026-04-13)
**Priority:** Critical
**Crate:** `ripdpi-desync`, `ripdpi-runtime`

**What:** zapret2's `circular` orchestrator rotates through a set of desync
strategies *within a single TCP connection* when it detects failure signals
(retransmissions, RST, HTTP redirect). Current RIPDPI evolution (`epsilon-greedy
+ UCB1`) operates *across connections* -- once a strategy is picked for a flow,
it stays. This misses the case where middlebox adapts mid-session.

**Parameters (zapret2 reference):**
- `fails=N` (default 3) -- consecutive failures before rotation
- `retrans=N` (default 3) -- retransmission count threshold
- `seq=<rseq>` (default 64K) -- max relative sequence for success determination
- `rst=<rseq>` (default 1) -- RST sequence threshold
- `time=<sec>` (default 60) -- observation window

**Implemented design:**
1. Added `RotationPolicy` and `RotationCandidate` to the shared native config
   model and threaded the same optional `tcpRotation` shape through the
   Kotlin/native JSON bridge without changing AppSettings or Compose controls
2. Implemented per-connection TCP rotation in the steady-state relay path of
   `ripdpi-runtime`, activated only after the first successful outbound /
   first-response round on a socket
3. Rotation applies only at round boundaries: the current round finishes on the
   current chain, and the next outbound round re-plans with the next candidate
4. Reused the existing first-response classifier and TLS partial-response
   boundary tracker to trigger rotation from redirects, TLS alerts, reset-class
   failures, silent closes, and `TCP_INFO` retransmission deltas
5. Added `circular_tlsrec_split` to the `full_matrix_v1` TCP diagnostics suite
   with roadmap-default thresholds and the fallback order
   `tlsrec_hostfake_split -> tlsrec_fake_rich -> split_host`

**Vault references:**
- `Censorship/adaptive-strategy-selection.md` -- orchestrator functions
- `Censorship/lua-scriptable-dpi-engine.md` -- circular orchestrator params
- `Censorship/zapret2-architecture.md` -- "Обновление 2026-04-12: Оркестраторы"

---

### 2. Conditional Strategy Execution

**Status:** [x] Implemented (2026-04-13)
**Priority:** Critical
**Crate:** `ripdpi-desync`, `ripdpi-config`, `ripdpi-runtime`

**What:** zapret2 supports runtime branching: `per_instance_condition` runs a
desync step only when a `cond` function evaluates to true. Example: use
timestamp-based fake only when server supports TCP timestamps
(`cond_tcp_has_ts`), otherwise fall back to TTL-based fake. RIPDPI's activation
filters (`round`, `payload_size`, `stream_bytes`) are static and don't inspect
connection state.

**zapret2 example:**
```
--lua-desync=per_instance_condition
--lua-desync=fake:blob=fake_default_tls:tcp_ts=-1000:cond=cond_tcp_has_ts
--lua-desync=fake:blob=fake_default_tls:ip_ttl=7:cond=cond_tcp_has_ts:cond_neg
```

**Implemented design:**
1. Extended the existing shared `ActivationFilter` model instead of adding a new
   planner-only condition enum. TCP steps now support
   `tcp_has_timestamp`, `tcp_has_ech`, `tcp_window_below`, and
   `tcp_mss_below` predicates alongside the existing round / payload-size /
   stream-window filters.
2. Threaded the same fields through AppSettings protobuf, Kotlin data models,
   chain DSL, JSON bridge, and native config conversion so remembered policies,
   diagnostics overlays, and exact JSON payloads preserve the predicates
   losslessly.
3. Restricted the new predicates to TCP step activation filters. Group-level
   activation windows and UDP steps reject them in both Kotlin validation and
   native config conversion to avoid ambiguous semantics.
4. Extended the desync planner's `ActivationContext` with a TCP-state snapshot:
   negotiated TCP timestamp support, current advertised window, negotiated MSS,
   and whether the current outbound TLS payload contains an ECH extension.
5. Planner evaluation is per outbound TCP write. Unknown TCP-state values fail
   closed for that predicate, so steps are skipped rather than misapplied, and
   later steps continue to run normally.
6. V1 editing remains DSL/JSON-driven. The Android advanced settings surface
   reports these predicates in activation summaries and counts, but does not add
   a dedicated visual per-step condition editor.

**Vault references:**
- `Censorship/adaptive-strategy-selection.md` -- "Оркестраторы" section
- `Censorship/lua-scriptable-dpi-engine.md` -- per_instance_condition, cond_lua

---

### 3. TCP Flag Manipulation (General)

**Status:** [x] Implemented (2026-04-13)
**Priority:** High
**Crate:** `ripdpi-config`, `ripdpi-runtime`, `ripdpi-root-helper`

**What:** zapret2 allows arbitrary TCP flag setting/unsetting on fake and real
packets: `--dpi-desync-tcp-flags-set` / `--dpi-desync-tcp-flags-unset` with
named flags (FIN, SYN, RST, PSH, ACK, URG, ECE, CWR, AE, R1-R3). RIPDPI
currently has `FakeRst` (sends RST) but no general flag crafting. Different DPI
implementations react differently to unusual flag combinations (e.g., SYN+FIN,
PSH+URG on fakes).

**Implemented design:**
1. Added four step-local TCP flag mask fields across the shared Rust model,
   AppSettings proto, Kotlin typed chain model, JSON bridge, and chain DSL:
   `tcp_flags_set`, `tcp_flags_unset`, `tcp_flags_orig_set`,
   `tcp_flags_orig_unset`.
2. Import accepts both numeric masks and named masks, but canonical storage and
   formatting normalize to lower-case zapret-style names in fixed bit order:
   `fin|syn|rst|psh|ack|urg|ece|cwr|ae|r1|r2|r3`.
3. Validation is centralized and explicit. Fake masks are allowed only on steps
   that emit fake TCP packets, original masks are allowed only on steps that
   emit original payload bytes, `fakerst` is fake-only, and `oob` / `disoob`
   reject all four mask fields in v1.
4. Reworked the raw TCP packet builder so fake packet sends, fake RST,
   seq-overlap, multi-disorder, and TCP IP fragmentation share the same flag
   override path.
5. Original-payload flag overrides now force the raw-send + replacement-socket
   execution path rather than normal kernel stream writes. When the required raw
   capability is unavailable, the runtime fails closed instead of silently
   sending unmodified packets.
6. Root-helper IPC gained generalized flagged TCP payload support so rooted and
   non-rooted raw paths apply the same TCP flag overrides.
7. Added `fake_synfin` and `fake_pshurg` to the `full_matrix_v1` TCP strategy
   audit set, and added a primary-step Compose chip editor for fake/original
   flag masks in Advanced Settings when the chain shape is visually editable.

**Vault references:**
- `Censorship/dpi-bypass-techniques.md` -- "TCP Flag Manipulation" section
- `Censorship/fake-packet-construction.md` -- "--dpi-desync-tcp-flags-set"

---

### 4. IP Identification Field Control

**Status:** [ ] Not started
**Priority:** High
**Crate:** `ripdpi-desync`, `ripdpi-ipfrag`

**What:** The IPv4 Identification field can betray fake packets when it has a
discontinuous sequence relative to real packets. zapret2's
`--ip-id=seq|seqgroup|rnd|zero` controls this. `seqgroup` is most important: it
makes fake packets share the same IP ID sequence as originals, preventing DPI
from distinguishing them by IP ID gap.

**Modes:**
- `seq` -- sequential increments (default OS behavior)
- `seqgroup` -- fakes get IDs adjacent to the original's ID
- `rnd` -- randomized non-zero
- `zero` -- all zeros (platform may override)

**Approach:**
1. Add `ip_id_mode: IpIdMode` enum to `DesyncGroup.actions`
2. In `ripdpi-desync` raw socket packet builder:
   - `seq`: increment a per-connection counter
   - `seqgroup`: read original packet's IP ID, assign ID-1/ID+1 to fakes
   - `rnd`: `rand::random::<u16>() | 1` (avoid zero)
   - `zero`: set to 0
3. Applies to all fake packet types (Fake, FakeSplit, FakeDisorder, HostFake,
   FakeRst) and IpFrag2 fragments

**Vault references:**
- `Censorship/dpi-bypass-techniques.md` -- "IP ID Handling (v72+)"
- `Censorship/fake-packet-construction.md` -- IP ID control

---

### 5. Fakedsplit Ordering Variants (altorder)

**Status:** [ ] Not started
**Priority:** High
**Crate:** `ripdpi-desync`

**What:** zapret2 supports 4 ordering modes (altorder 0-3) that control where
fake packets are placed relative to genuine segments. Different DPI
implementations process out-of-order packets differently, so having multiple
orderings increases the chance of finding one that works.

**Modes:**
- `altorder=0` -- fake before each genuine segment (current RIPDPI default)
- `altorder=1` -- all fakes first, then all genuine segments
- `altorder=2` -- interleaved: genuine1, fake1, genuine2, fake2, ...
- `altorder=3` -- all genuine segments first, then all fakes

Additionally: `--dpi-desync-fake-tcp-mod=seq` applies sequential TCP sequence
numbers across multiple fakes (instead of all sharing the original's seq).

**Approach:**
1. Add `fake_order: FakeOrder` enum to `TcpChainStep` for Fake/FakeSplit/
   FakeDisorder steps
2. Add `fake_seq_mode: FakeSeqMode` (`duplicate` | `sequential`) 
3. In desync plan execution, reorder the packet queue according to selected mode
   before flushing to raw socket
4. Add `altorder1`, `altorder2` variants to strategy probe candidates

**Vault references:**
- `Censorship/fake-packet-construction.md` -- "Fakedsplit Ordering Modes"
- `Censorship/dpi-bypass-techniques.md` -- "Fakedsplit Variants"

---

### 6. SOCKS5 Localhost Hardening

**Status:** [x] Partially implemented (`auth_token` exists)
**Priority:** High (security)
**Crate:** `ripdpi-runtime`

**What:** The vault documents a critical vulnerability (ntc.party/t/23871, 56
replies) affecting ALL Android VLESS clients: unauthenticated SOCKS5 on a
predictable port lets any app discover the VPN exit IP. RIPDPI already has
`auth_token` support in `negotiate_socks5()`. Remaining gaps:

**Remaining work:**
1. **Random port binding** -- bind SOCKS5 listener to a random high port
   instead of a fixed one. TeapodStream demonstrates this approach.
2. **Per-session credential rotation** -- generate fresh random auth_token on
   each VPN session start, pass to tun2socks bridge via the authenticated proxy
   URL
3. **Verify auth is enforced by default** -- ensure no code path allows
   unauthenticated SOCKS5 connections when VPN mode is active

**Vault references:**
- `Censorship/vless-client-android-vulnerability.md` -- full vulnerability chain
- `Censorship/app-level-vpn-detection.md` -- "Метод 6: Веб-детекция через JavaScript"

---

## Tier 2 -- Strong Bypass Improvement

### 7. Hostfakesplit with Random Domain Generation

**Status:** [ ] Not started
**Priority:** Medium-High
**Crate:** `ripdpi-desync`, `ripdpi-packets`

**What:** zapret2's `hostfakesplit` splits TLS/HTTP at a fixed host marker and
replaces the hostname in the fake segment with a randomly generated domain.
RIPDPI has `HostFake` with `midhost_offset` but the random-domain-at-marker
variant adds unpredictability that defeats DPI caching fake SNI values.

**zapret2 parameters:**
- `--dpi-desync-hostfakesplit-midhost` -- marker position
- `--dpi-desync-hostfakesplit-mod=host=<hostname>` -- custom or random hostname
- `--dpi-desync-hostfakesplit-mod=altorder=1` -- ordering variant

**Approach:**
1. Add `random_fake_host: bool` option to `HostFake` step config
2. When enabled, generate a random plausible domain (e.g.,
   `{8-char-hex}.{popular-tld}`) for each connection's fake segment
3. Optionally accept a template pattern (`*.example.com`) for targeted faking

**Vault references:**
- `Censorship/dpi-bypass-techniques.md` -- "Hostfakesplit Strategy"
- `Censorship/fake-packet-construction.md` -- "Hostfakesplit"

---

### 8. PCAP Diagnostic Recording

**Status:** [ ] Not started
**Priority:** Medium
**Crate:** `ripdpi-monitor`, `ripdpi-desync`

**What:** zapret2's `--lua-desync=pcap:file=capture.cap` enables wire-level
packet capture without external tools. Extremely valuable for debugging
on constrained Android devices where tcpdump isn't available.

**Approach:**
1. Add a `PcapWriter` utility in `ripdpi-monitor` that writes standard pcap
   format (global header + per-packet records with timestamps)
2. Hook into the desync pipeline: capture packets before and after
   manipulation (original + all generated fakes/splits)
3. Expose via diagnostics: "Record next N connections" button in UI
4. Include pcap files in diagnostic export bundles
5. Cap file size (e.g., 10 MB ring buffer) to prevent storage exhaustion

**Vault references:**
- `Censorship/lua-scriptable-dpi-engine.md` -- "PCAP-запись" section

---

### 9. Execution Plan Cancellation (mid-stream)

**Status:** [ ] Not started
**Priority:** Medium
**Crate:** `ripdpi-desync`, `ripdpi-runtime`

**What:** zapret2's `execution_plan_cancel(ctx)` can abort a strategy cascade
mid-stream and switch to a different approach. Combined with `stopif`
orchestrator (clear plan if condition is true). RIPDPI currently commits to a
strategy for the entire connection lifetime.

**Approach:**
1. Make the desync plan mutable: store current plan in `ConnState` as a
   `Vec<PlannedStep>` with a cursor
2. Add `plan_cancel()` and `plan_replace(new_plan)` methods
3. Trigger plan replacement on: connection freeze detection, unexpected RST
   pattern, TLS alert
4. Works in conjunction with circular rotation (#1) -- rotation replaces the
   remaining plan steps

**Vault references:**
- `Censorship/blockcheck-strategy-detection.md` -- execution_plan system
- `Censorship/lua-scriptable-dpi-engine.md` -- "Execution Plan API"

---

### 10. DNS Hostname-to-IP Cache for Strategy Decisions

**Status:** [ ] Not started
**Priority:** Medium
**Crate:** `ripdpi-dns-resolver`, `ripdpi-runtime`

**What:** zapret2's `--ipcache-hostname` extracts hostnames from DNS responses
to build IP→hostname mapping. This becomes critical when SNI is encrypted (ECH)
-- the desync engine needs to know which host a connection targets to select the
right strategy, but can't read it from the ClientHello.

**Approach:**
1. In `ripdpi-dns-resolver`, capture `(hostname, resolved_ip, ttl)` tuples from
   DNS query responses
2. Store in an LRU cache (`lru` crate, already in deps) keyed by IP
3. In `ripdpi-runtime` routing, when SNI is absent (ECH) or empty, look up the
   destination IP in the DNS cache to recover the hostname
4. Feed recovered hostname into desync group matching (host filters)

**Vault references:**
- `Censorship/auto-learning-hostlist-management.md` -- "DNS Response Analysis"
- `Censorship/zapret2-architecture.md` -- "--ipcache-hostname"

---

### 11. Payload Detection Exclusions

**Status:** [ ] Not started
**Priority:** Medium
**Crate:** `ripdpi-packets`, `ripdpi-config`

**What:** zapret2's `--payload-disable=list` prevents false positive protocol
classification (e.g., Roblox UDP packets misidentified as WireGuard due to
non-standard reserved bytes). RIPDPI's `ripdpi-packets` classifies protocols but
has no exclusion mechanism.

**Approach:**
1. Add `payload_disable: Vec<String>` to `DesyncGroup` config
2. In `ripdpi-packets` protocol classifier, skip disabled protocol checks
3. Common exclusions: `wireguard`, `stun`, `bt` (BitTorrent)
4. Expose in UI as "Protocol detection exceptions" in advanced settings

**Vault references:**
- `Censorship/zapret2-architecture.md` -- "--payload-disable" section

---

### 12. Timer/Delay-Based Evasion

**Status:** [ ] Not started
**Priority:** Medium
**Crate:** `ripdpi-desync`

**What:** zapret2's Lua timer API (`timer_set`/`timer_del`) enables delayed
packet injection -- e.g., send a fake packet 50ms after the real one to exploit
DPI timeout windows. Adds a temporal dimension to evasion that purely
synchronous packet manipulation can't achieve.

**Parameters (zapret2):**
- `timer_set(name, func, period_ms, oneshot, data)`
- `--timer-res` configurable (default 50ms, min 10ms)

**Approach:**
1. Add `delay_ms: Option<u16>` field to `TcpChainStep` / `UdpChainStep`
2. In desync plan execution, when `delay_ms` is set, schedule the packet send
   via `tokio::time::sleep()` (already async runtime)
3. Use cases: delayed fake after real (DPI timeout exploit), staggered
   multi-disorder sends, paced burst to avoid rate-based detection
4. Cap at reasonable maximum (500ms) to avoid connection timeouts
5. Add to probe candidates: `fake_delayed_50ms`, `disorder_delayed_100ms`

**Vault references:**
- `Censorship/zapret2-architecture.md` -- "Lua Timer API"
- `Censorship/lua-scriptable-dpi-engine.md` -- "Timer API" section

---

## Tier 3 -- Novel / Experimental

### 13. SYN-Hide

**Status:** [ ] Not started
**Priority:** Experimental
**Crate:** `ripdpi-desync`, `ripdpi-root-helper`
**Requires:** Root access

**What:** Experimental zapret2 technique that hides TCP SYN from DPI by
removing the SYN flag and inserting a "magic" marker that the server
recognizes. The server strips the magic and echoes back a proper SYN.

**Magic marker options:**
- `x2` -- reserved TCP flags (default, most compatible)
- Urgent pointer manipulation
- TCP option injection
- `tsecr` -- timestamp echo value

**Known limitations:**
- Linux conntrack invalidates non-SYN packets (workaround: postnat nfqueue
  pass-through -- not available on Android)
- middlebox: bypasses HTTP requests but **NOT** HTTPS
- Some ISPs drop packets with x2 reserved flag bits
- *nix systems require exact `tsecr` echo from SYN,ACK

**Approach (research-first):**
1. Requires a cooperating server component (not standalone client technique)
2. Implement as a `SynHide` step in `ripdpi-root-helper` (needs raw socket
   for SYN flag manipulation)
3. Add server-side companion tool or document xray plugin requirement
4. Start with `x2` magic as default, add tsecr as alternative
5. Gate behind "experimental" feature flag in UI

**Vault references:**
- `Censorship/zapret2-architecture.md` -- "SYN-Hide (Experimental)"
- `Censorship/tcp-desync-implementation.md` -- "SYN-Hide Mechanism"
- `Censorship/dpi-bypass-techniques.md` -- "SYN-Hide (Experimental)"

---

### 14. UDP-over-ICMP Protocol Obfuscation

**Status:** [ ] Not started
**Priority:** Experimental
**Crate:** `ripdpi-desync` or new `ripdpi-icmp-tunnel`
**Requires:** Root access (ICMP raw sockets)

**What:** zapret2 can wrap UDP traffic (e.g., WireGuard, QUIC) in ICMP echo
request/reply packets. middlebox does not inspect ICMP payloads, making this
transport invisible to current DPI. Uses custom ICMP codes (e.g., 199) to
distinguish from real pings.

**zapret2 syntax:**
```
--lua-desync=udp2icmp:ccode=199:scode=199
```

**Architecture:**
- Client: UDP out → wrap in ICMP echo-request (type 8, code 199)
- Server: ICMP echo-request in → unwrap → UDP to local service
- Server: UDP reply → wrap in ICMP echo-reply (type 0, code 199)
- Client: ICMP echo-reply in → unwrap → UDP to local app
- Optional: `dataxor=blob` for additional payload obfuscation

**Approach:**
1. Requires cooperating server (nfqws2 with `--server` mode or custom daemon)
2. Implement ICMP socket handling in `ripdpi-root-helper` (ICMP raw sockets
   need root or `CAP_NET_RAW`)
3. Create `IcmpTunnel` transport that wraps any UDP relay
4. Add nftables rules for server-side (document in setup guide)
5. Gate behind root + experimental flags

**Vault references:**
- `Censorship/zapret2-architecture.md` -- "ICMP/Protocol Obfuscation" with
  full nftables + nfqws2/winws2 config examples
- `Censorship/dpi-bypass-techniques.md` -- "UDP→ICMP Protocol Obfuscation"
- `Censorship/dpi-evasion-taxonomy.md` -- "Обфускация протоколов"

---

### 15. Server-Side Behavioral Scoring (Documentation)

**Status:** [ ] Not started (documentation only)
**Priority:** Low (server-side, not client feature)

**What:** The graylist + nginx stream technique for detecting and deflecting
middlebox active probes. While not a client feature, RIPDPI could document
recommended server-side hardening for users who self-host.

**Scoring indicators (from Habr article by segflt):**

| Indicator | Score | Rationale |
|-----------|-------|-----------|
| Temporal correlation (probe 1-3s after legit traffic) | +5 | Strongest signal |
| Empty SNI / short session <2s / burst >=10/5s | +3 | Probe behavior |
| Minimal bytes <300 / high rate / TCP window anomaly | +2 | Scanner fingerprint |
| Abnormal TTL / non-standard MSS | +1 | Weak signals |

Threshold: score >= 5 triggers graylist (redirect to fallback, never DROP).

**Approach:**
1. Add `docs/server-hardening.md` with nginx stream config, graylist format,
   tcpdump capture script, and Docker compose reference
2. Link from README.md server setup section

**Vault references:**
- `Censorship/vless-graylist-active-probing-defense.md` -- full guide
- `Censorship/active-probing-defenses.md` -- general theory + section 9

---

## Cross-Cutting Concerns

### Strategy Probe Expansion

Items #1-5 and #12 each introduce new desync behaviors that should be added to
the `full_matrix_v1` probe candidate pool:

| Current candidates | New candidates from this roadmap |
|--------------------|----------------------------------|
| 21 TCP + 6 QUIC | +4-8 TCP (flag variants, altorders, delayed, conditional) |

Update `ripdpi-monitor/src/` candidate lists and tournament bracket logic.

### Config Schema Evolution

Items #1-5, #7, #11, #12 all add new fields to `ripdpi-config` model. Plan a
single schema migration that adds all new fields with backwards-compatible
defaults (existing configs remain valid).

### Root Helper Expansion

Items #13 and #14 require new `ripdpi-root-helper` IPC commands:
- `send_syn_hide_tcp` -- SYN flag manipulation
- `send_icmp_wrapped_udp` / `recv_icmp_wrapped_udp` -- ICMP tunnel I/O

### Testing Strategy

- Unit tests: each new desync step kind needs packet-level assertion tests
- Fuzz targets: extend `cargo-fuzz` for new config variants
- Integration: add Maestro E2E flows for new strategy types
- Soak: include new candidates in `network_load` stress tests

---

## Reference: Vault Note Index

All vault notes referenced above live in
`~/GitRep/obsidian/TechnicalVault/Censorship/`:

| Note | Topics covered |
|------|---------------|
| `zapret2-architecture.md` | 3-layer arch, Lua timer API, OOB, SYN-Hide, ICMP tunneling, orchestrators, torrent detection, server mode, performance profiling |
| `dpi-bypass-techniques.md` | TCP/TLS/IP-level techniques, OOB, SYN-Hide, hostfakesplit, fakedsplit, TCP flags, IP ID, UDP→ICMP |
| `fake-packet-construction.md` | Fake variants, hostfakesplit, altorder modes, TCP flags, IP ID, binary data loading |
| `tcp-desync-implementation.md` | OOB support, SYN-Hide mechanism, server mode, marker-based splitting |
| `adaptive-strategy-selection.md` | Orchestrators (circular, conditional, repeater, stopif), retransmission detection, UDP/QUIC failure |
| `lua-scriptable-dpi-engine.md` | Timer API, orchestrators, track structure, PCAP recording, execution plan, performance profiling |
| `blockcheck-strategy-detection.md` | blockcheck2 architecture, execution plan system, failure detection |
| `auto-learning-hostlist-management.md` | DNS hostname analysis, incoming threshold, payload detection |
| `vless-client-android-vulnerability.md` | SOCKS5 vulnerability, TeapodStream mitigation |
| `app-level-vpn-detection.md` | JS web detection, browser isolation, Mintsifry methodology |
| `vless-graylist-active-probing-defense.md` | Server-side behavioral scoring, nginx stream, graylist |
| `active-probing-defenses.md` | Active probing theory, probe types, Reality/XHTTP defense |
| `dpi-evasion-taxonomy.md` | L3/L4/L7 classification, protocol obfuscation, Generation 6 evolution |
| `windivert-packet-diversion.md` | WinDivert bulk mode, filter combining (Windows reference) |
| `nftables-vs-iptables.md` | Kernel 6.17 compat, signature filtering, ipset bitmap:port |
