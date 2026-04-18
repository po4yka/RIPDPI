# ROADMAP: DPI Bypass Technique Expansion

> Cross-reference analysis between techniques documented as working in the field
> (TechnicalVault/Censorship/) and RIPDPI's current implementation.
> Each item includes rationale, technical approach, and vault references.

## Related Roadmaps

This roadmap is the **tactical checklist** of bypass techniques to ship. It sits
under the master index and interlocks with three sibling roadmaps:

- [ROADMAP.md](ROADMAP.md) -- master index and sequencing across all four active roadmaps.
- [ROADMAP-bypass-modernization.md](ROADMAP-bypass-modernization.md) -- **strategic** companion.
  New tactics must fit the capability, planner, and measurement architecture defined there.
  - Modernization Workstream 1 (capability hygiene) gates honest evaluation of any new tactic here.
  - Modernization Workstream 2 (first-flight IR) is where new TCP/TLS/QUIC planning logic must land.
  - Modernization Workstream 3 (QUIC subsystem) owns QUIC Initial shaping; QUIC probe candidates in
    this roadmap should route through it rather than extending ad hoc packet families.
  - Modernization Workstream 7 (emitter tiers) supersedes ad hoc root/non-root decisions for new tactics.
- [ROADMAP-architecture-refactor.md](ROADMAP-architecture-refactor.md) -- **structural** companion.
  Refactor Workstream 3 (native runtime/desync decomposition) cleans the code seams this roadmap's
  Tier 3 work will need. New step-family executors land on the decomposed layout, not on today's
  central `desync.rs`.
- [docs/roadmap-integrations.md](docs/roadmap-integrations.md) -- transport layer beneath bypass tactics.

### Ownership Notes

Where this roadmap and a sibling roadmap both touch the same topic, this roadmap
owns the **concrete tactic + probe candidate**; the sibling roadmap owns the
**framework**.

| Topic | This roadmap | Sibling owner |
|-------|--------------|---------------|
| QUIC Initial shaping | probe candidates, step kinds | modernization Workstream 3 (subsystem) |
| DNS hostname recovery | LRU cache (shipped, item 10) | modernization Workstream 4 (multi-oracle scoring) |
| Root/non-root emitter split | SOCKS5 hardening (shipped, item 6) | modernization Workstream 7 (tier definition) |
| TCP flag mask execution | flag mask fields (shipped, item 3) | refactor Workstream 3 (executor split) |

## Status Legend

- [ ] Not started
- [x] Already implemented or not applicable

## Execution Status (2026-04-18)

- Items 1-12 are shipped and audited against the codebase.
- Items 13-14 now have the repo-owned client/runtime experimental seam landed
  (`ripdpi-runtime` transport types + Linux/Android raw packet helpers +
  `ripdpi-root-helper` IPC commands), while any cooperating server companion
  remains user-provided and out of scope for this repo.
- Item 15 is complete as documentation-only server hardening guidance.
- Architecture-refactor Workstream 3 is now complete, so any future runtime-side
  Tier 3 work should land on the extracted family/lowering seams rather than
  reopening the old central `desync.rs` executor shape, and any QUIC-side
  candidates should reuse the shipped packetizer/layout families from
  modernization Workstream 3.

## Verification Evidence (2026-04-15 audit)

Items 1-12 have been audited against the codebase. All claims verified with
high confidence. Test coverage: 47 unit tests for planning logic in
`ripdpi-desync/src/tests/plan_tcp.rs` (1030 lines) plus targeted tests for
DNS cache, proxy hardening, and protocol matching.

| # | Item | Primary symbol | File | Line |
|---|------|----------------|------|------|
| 1 | Circular rotation | `RotationPolicy` / `CircularTcpRotationController` | `ripdpi-config/src/model/mod.rs` / `ripdpi-runtime/src/runtime/relay/stream_copy/rotation.rs` | 219 / 42 |
| 2 | Conditional execution | `tcp_has_timestamp` / `tcp_has_ech` / `ActivationTcpState` | `ripdpi-config/src/model/offset.rs` / `ripdpi-desync/src/types.rs` | - / 78 |
| 3 | TCP flag manipulation | `tcp_flags_set` / `fake_synfin` / `fake_pshurg` | `ripdpi-config/src/model/mod.rs` / `ripdpi-monitor/src/candidates.rs` | 153 / 260 |
| 4 | IP ID control | `IpIdMode` / `tlsrec_fake_seqgroup` | `ripdpi-config/src/model/mod.rs` / `ripdpi-monitor/src/candidates.rs` | 137 / 259 |
| 5 | Fakedsplit ordering | `FakeOrder` / `FakeSeqMode` | `ripdpi-config/src/model/mod.rs` | 121, 130 |
| 6 | SOCKS5 hardening | `ProxySessionOverrides` / `ProxyRuntimeSupervisor` | `ripdpi-proxy-config/src/types.rs` / `core/service/.../ProxyRuntimeSupervisor.kt` | 71 / 16 |
| 7 | Random fake host | `random_fake_host` / `tlsrec_hostfake_random` | `ripdpi-config/src/model/mod.rs` / `ripdpi-monitor/src/candidates.rs` | 179 / 347 |
| 8 | PCAP recording | `PcapWriter` / `PcapRecordingSession` | `ripdpi-monitor/src/pcap.rs` | 18, 75 |
| 9 | Plan cancellation | `desync_suppressed` / `cancel_on_failure` | `ripdpi-runtime/.../rotation.rs` / `ripdpi-config/src/model/mod.rs` | 54 / 228 |
| 10 | DNS hostname cache | `DnsHostnameCache` (LRU cap 4096, TTL 60-7200s) | `ripdpi-runtime/src/dns_hostname_cache.rs` | 27 |
| 11 | Payload exclusions | `payload_disable` bitmask | `ripdpi-config/src/model/mod.rs` + `ripdpi-runtime/src/runtime_policy/matching.rs` | - / 98 |
| 12 | Timer/Delay | `DesyncAction::Delay` / `inter_segment_delay_ms` (capped 500ms) | `ripdpi-desync/src/types.rs` / `ripdpi-config/src/model/mod.rs` | 165 / 162 |

### Surprises Discovered During Audit

The following are present in code but not catalogued in this roadmap. Decide
whether to promote them to first-class items or leave as implementation detail.

- **IPv6 Extension Headers** -- `TcpChainStep` fields `ipv6_hop_by_hop`,
  `ipv6_dest_opt`, `ipv6_dest_opt2`, `ipv6_routing`, `ipv6_frag_next_override`
  in `ripdpi-config/src/model/mod.rs:166-175`. No probe candidates yet.
- **Seq-overlap fake mode** -- `SeqOverlapFakeMode` enum (`Profile` / `Rand`)
  at `ripdpi-config/src/model/mod.rs:107-111`. Not referenced in roadmap.
- **DNS cache metrics** -- `DnsHostnameCache::stats()` returns `(size, hits, misses)`;
  available for telemetry but not yet surfaced in diagnostics exports.

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
it stays. This misses the case where TSPU adapts mid-session.

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

**Status:** [x] Implemented
**Priority:** High
**Crate:** `ripdpi-config`, `ripdpi-proxy-config`, `ripdpi-runtime`, `ripdpi-monitor`

**What shipped:** RIPDPI now exposes a group-wide IPv4 ID mode on the
fake-packet surface with full AppSettings, JSON/native bridge, and Advanced
Settings wiring. Supported modes are `seq`, `seqgroup`, `rnd`, and `zero`, with
the default empty value preserving previous behavior.

**Modes:**
- `seq` -- per-flow monotonic IDs for raw-built IPv4 datagrams
- `seqgroup` -- exact shared sequence across fake and promoted original raw TCP packets
- `rnd` -- randomized non-zero
- `zero` -- all zeros

**Runtime behavior:**
1. The main runtime owns the IPv4 ID allocator. Root-helper IPC now accepts
   explicit IPv4 IDs so rooted and non-rooted sends consume the same reserved
   sequence.
2. Allocation is per IPv4 datagram:
   - one unfragmented packet consumes one ID
   - an IPv4 fragment pair shares one ID
   - multi-packet fake batches consume contiguous IDs in transmit order
3. `seqgroup` is exact rather than approximate:
   - mixed fake+original TCP paths promote the original send onto the existing
     raw/TCP_REPAIR replacement-socket path
   - if that exact raw path is unavailable, the send fails closed instead of
     silently falling back to a kernel-stream original with mismatched IDs
4. IPv6 is unaffected.

**Surface:**
- top-level fake-packet setting `ip_id_mode` / `ipIdMode`
- Advanced Settings dropdown: `Default`, `Sequential`, `Seqgroup`, `Random`,
  `Zero`
- `full_matrix_v1` adds `tlsrec_fake_seqgroup` as a dedicated diagnostic
  candidate without introducing a new strategy family taxonomy

**Vault references:**
- `Censorship/dpi-bypass-techniques.md` -- "IP ID Handling (v72+)"
- `Censorship/fake-packet-construction.md` -- IP ID control

---

### 5. Fakedsplit Ordering Variants (altorder)

**Status:** [x] Implemented (2026-04-13)
**Priority:** High
**Crate:** `ripdpi-config`, `ripdpi-runtime`, `ripdpi-monitor`

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

**Implemented design:**
1. Added shared `FakeOrder` (`0`-`3`) and `FakeSeqMode`
   (`duplicate` | `sequential`) fields to TCP steps and threaded them through
   AppSettings, Kotlin typed models, JSON/native config, and the TCP chain DSL
   as `altorder=` and `seqmode=`.
2. Supported ordering on `fake`, `fakedsplit`, `fakeddisorder`, and
   `hostfake`. Unsupported step kinds keep these fields empty on the wire and
   reject non-default values in Kotlin and native validation.
3. `hostfake` only allows non-default `altorder` when `midhost=` is present, so
   the runtime has two genuine host regions to reorder. `hostfake` still allows
   `seqmode=sequential` without `midhost`.
4. Refactored fake send execution in `ripdpi-runtime` into an ordered emission
   batch so the runtime can reorder fake and genuine spans for `fake`,
   `fakedsplit`, `fakeddisorder`, and `hostfake` while reusing the existing
   fake TTL, fake timestamp, TCP flag, and IPv4 ID logic per emitted packet.
5. `seqmode=duplicate` preserves current behavior. `seqmode=sequential`
   advances later fake packet sequence numbers in emission order and forces the
   raw/TCP_REPAIR batch path; if exact raw sequence control is unavailable, the
   send fails closed instead of silently degrading.
6. Added a primary-step Advanced Settings card for fake ordering. Simple chains
   expose editable `altorder` and `seqmode` dropdowns; complex chains fall back
   to the DSL summary. `hostfake` without `midhost` shows the card but keeps
   non-default ordering disabled.
7. Added `tlsrec_fakedsplit_altorder1` and `tlsrec_fakedsplit_altorder2` to
   the `full_matrix_v1` TCP diagnostics suite. `quick_v1` remains unchanged and
   the candidates stay inside the existing `fake_approx` family taxonomy.

**Vault references:**
- `Censorship/fake-packet-construction.md` -- "Fakedsplit Ordering Modes"
- `Censorship/dpi-bypass-techniques.md` -- "Fakedsplit Variants"

---

### 6. SOCKS5 Localhost Hardening

**Status:** [x] Implemented (2026-04-13)
**Priority:** High (security)
**Crate:** `ripdpi-runtime`, `ripdpi-proxy-config`, `core:service`

**What:** The vault documents a critical vulnerability (ntc.party/t/23871, 56
replies) affecting ALL Android VLESS clients: unauthenticated SOCKS5 on a
predictable port lets any app discover the VPN exit IP. RIPDPI already has
`auth_token` support in `negotiate_socks5()`. The remaining gaps were VPN-mode
random port handoff and command-line payloads bypassing the local auth
injection path.

**What shipped:**
1. **VPN-only ephemeral localhost binding** -- the VPN-managed proxy now starts
   with a session-local `listenPortOverride=0`, while standalone Proxy mode
   still uses the persisted `proxyPort`.
2. **Telemetry-resolved port handoff** -- `ProxyRuntimeSupervisor.start()`
   waits for proxy readiness, reads the reported `listenerAddress`, and returns
   a resolved localhost endpoint to the TUN-to-SOCKS runtime instead of
   assuming the configured fixed port.
3. **Per-session token and port rotation** -- initial VPN start and network
   handover restart both generate a fresh auth token and a fresh ephemeral
   listener port. DNS-only tunnel rebuilds reuse the current proxy endpoint
   because the proxy itself is not restarted.
4. **Shared session override layer for UI and command-line payloads** --
   runtime-only `sessionOverrides` now apply the local auth token and ephemeral
   port after payload parsing for both UI-config and command-line-config VPN
   sessions, without changing AppSettings or public CLI syntax.
5. **Mandatory localhost auth in VPN mode** -- SOCKS5 RFC 1929 auth and HTTP
   CONNECT `Proxy-Authorization` are both enforced whenever the VPN session
   injects a local auth token.
6. **Fail-closed startup behavior** -- if the proxy reports ready but does not
   publish a listener address, VPN startup aborts instead of silently falling
   back to a predictable port.

**Vault references:**
- `Censorship/vless-client-android-vulnerability.md` -- full vulnerability chain
- `Censorship/app-level-vpn-detection.md` -- "Метод 6: Веб-детекция через JavaScript"

---

## Tier 2 -- Strong Bypass Improvement

### 7. Hostfakesplit with Random Domain Generation

**Status:** [x] Implemented -- random_fake_host field on HostFake step; OS-entropy seeded per connection; tlsrec_hostfake_random probe candidate added
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

**Status:** [x] Implemented -- PcapWriter in ripdpi-monitor, PcapHook in desync execution pipeline, JNI bridge for start/stop/query recording, 10 MB cap with auto-stop
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

**Status:** [x] Implemented -- desync_suppressed flag on CircularTcpRotationController; cancel_on_failure policy field (default true); suppresses desync on failure until rotation completes
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

**Status:** [x] Implemented -- DnsHostnameCache LRU in ripdpi-runtime with TTL-based eviction; populated from encrypted DNS resolution; consumed in connect_target for ECH hostname recovery
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

**Status:** [x] Implemented -- payload_disable bitmask on DesyncGroupMatchSettings gates is_http/is_tls checks; per-group config via Vec of String in JSON bridge
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

**Status:** [x] Implemented -- DesyncAction::Delay(u16) in planner and both execution paths; extends inter_segment_delay_ms to all step kinds; config cap raised to 500ms; delayed split probe candidates added
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

**Status:** [x] Complete (in-repo client-side experimental surface)
**Priority:** Experimental
**Crate:** `ripdpi-desync`, `ripdpi-root-helper`
**Requires:** Root access
**Estimated LoC:** ~140 (IPC handler ~80, root-helper plumbing ~40, config struct ~20)
**Scaffolding base:** `ripdpi-root-helper/src/handlers.rs` already has 7 handlers
(`fake_rst`, `flagged_tcp_payload`, `seqovl`, `multi_disorder`, `ordered_tcp`,
`ip_frag_tcp`, `ip_frag_udp`). IPC protocol in `protocol.rs:58-83` uses JSON
command + optional fd; new `CMD_SEND_SYN_HIDE_TCP` fits the existing pattern.
**Depends on:** bypass-modernization Workstream 7 (emitter tier definition)
before shipping as production.

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
- TSPU: bypasses HTTP requests but **NOT** HTTPS
- Some ISPs drop packets with x2 reserved flag bits
- *nix systems require exact `tsecr` echo from SYN,ACK

**Delivered:**
1. Added `CMD_SEND_SYN_HIDE_TCP` to the `ripdpi-root-helper` IPC protocol
2. Added the shared `SynHideTcpSpec` transport seam in `ripdpi-runtime`
3. Added Linux/Android packet construction for `reserved_x2`, `urgent_ptr`,
   and `timestamp_echo` marker families
4. Added focused runtime tests that verify SYN is unset and the marker is
   encoded on the wire

**Remaining outside the repo:** any cooperating server-side companion logic is
still user-provided and intentionally out of scope for the client repository.

**Vault references:**
- `Censorship/zapret2-architecture.md` -- "SYN-Hide (Experimental)"
- `Censorship/tcp-desync-implementation.md` -- "SYN-Hide Mechanism"
- `Censorship/dpi-bypass-techniques.md` -- "SYN-Hide (Experimental)"

---

### 14. UDP-over-ICMP Protocol Obfuscation

**Status:** [x] Complete (in-repo client-side experimental surface)
**Priority:** Experimental
**Crate:** `ripdpi-desync` or new `ripdpi-icmp-tunnel`
**Requires:** Root access (ICMP raw sockets)
**Estimated LoC:** ~450 (ICMP socket wrapper ~150, IPC send/recv handlers ~100,
tunnel wrapper ~200)
**Scaffolding base:** Follows the existing `ripdpi-root-helper` IPC pattern;
new commands `CMD_SEND_ICMP_WRAPPED_UDP` / `CMD_RECV_ICMP_WRAPPED_UDP` fit the
scheme. Tunnel wrapper layers above `ripdpi-runtime` UDP relay path
(`runtime/udp.rs` 964 lines).
**Depends on:** cooperating server component (nfqws2 with `--server` mode or
custom daemon). Not deliverable standalone from the client roadmap.

**What:** zapret2 can wrap UDP traffic (e.g., WireGuard, QUIC) in ICMP echo
request/reply packets. TSPU does not inspect ICMP payloads, making this
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

**Delivered:**
1. Added `CMD_SEND_ICMP_WRAPPED_UDP` / `CMD_RECV_ICMP_WRAPPED_UDP` to the
   `ripdpi-root-helper` IPC protocol
2. Added shared `IcmpWrappedUdpSpec`, `IcmpWrappedUdpRecvFilter`, and
   `ReceivedIcmpWrappedUdp` transport types in `ripdpi-runtime`
3. Added a versioned ICMP envelope codec with optional session-key XOR payload
   obfuscation
4. Added Linux/Android raw-ICMP send/receive helpers and focused runtime tests
   for envelope round-trip and packet extraction

**Remaining outside the repo:** the cooperating server endpoint remains
user-provided (`nfqws2 --server`-style deployment or a custom daemon) and is
not bundled by this project.

**Vault references:**
- `Censorship/zapret2-architecture.md` -- "ICMP/Protocol Obfuscation" with
  full nftables + nfqws2/winws2 config examples
- `Censorship/dpi-bypass-techniques.md` -- "UDP→ICMP Protocol Obfuscation"
- `Censorship/dpi-evasion-taxonomy.md` -- "Обфускация протоколов"

---

### 15. Server-Side Behavioral Scoring (Documentation)

**Status:** [x] Complete
**Priority:** Low (server-side, not client feature)

**What:** The graylist + nginx stream technique for detecting and deflecting
TSPU active probes. While not a client feature, RIPDPI could document
recommended server-side hardening for users who self-host.

**Scoring indicators (from Habr article by segflt):**

| Indicator | Score | Rationale |
|-----------|-------|-----------|
| Temporal correlation (probe 1-3s after legit traffic) | +5 | Strongest signal |
| Empty SNI / short session <2s / burst >=10/5s | +3 | Probe behavior |
| Minimal bytes <300 / high rate / TCP window anomaly | +2 | Scanner fingerprint |
| Abnormal TTL / non-standard MSS | +1 | Weak signals |

Threshold: score >= 5 triggers graylist (redirect to fallback, never DROP).

**Delivered:**
1. Added [docs/server-hardening.md](docs/server-hardening.md) with an `nginx
   stream` graylist layout, score model, map format, capture script, and
   Docker compose reference
2. Linked the guide from [README.md](README.md) and [docs/README.md](docs/README.md)

**Remaining:** none in-repo. This is documentation only and does not unblock
the still-open client/server transport work in items 13 and 14.

**Vault references:**
- `Censorship/vless-graylist-active-probing-defense.md` -- full guide
- `Censorship/active-probing-defenses.md` -- general theory + section 9

---

## Cross-Cutting Concerns

### Strategy Probe Expansion

Items #1-5, #7, and #12 each introduce new desync behaviors that should be added to
the `full_matrix_v1` probe candidate pool:

| Current candidates | New candidates from this roadmap |
|--------------------|----------------------------------|
| 21 TCP + 6 QUIC | +3 TCP (hostfake random, delayed split variants) + flag variants, altorders, conditional |

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
