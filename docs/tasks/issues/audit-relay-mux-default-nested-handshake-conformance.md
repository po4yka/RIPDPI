---
title: Audit relay transports for MUX-default posture against TLS-in-TLS fingerprinting and add nested-handshake conformance fixture
type: task
status: doing
area: transport
priority: medium
owner: unassigned
parent: epic-protocol-conformance-tests
blocks: []
blocked_by: []
created: 2026-06-15
updated: 2026-07-14
source_wiki_pages:
  - "encapsulated-tls-handshake-fingerprinting"
  - "stream-multiplexing-tunnels"
linked_task: null
---

## Motivation

Xue et al. (USENIX Security 2024, "Fingerprinting Obfuscated Proxy Traffic with Encapsulated TLS Handshakes") measured, on a US academic ISP (Merit Network — a feasibility study, not a TSPU or GFW deployment), that non-multiplexed inner-TLS proxy flows are detectable at true-positive rates of ~0.75 at a false-positive rate of 0.054% for vmess, vless-over-tls, and trojan. Stream multiplexing is the strongest documented countermeasure in that study: vmess at concurrency=8 reduces TPR to ~0.17 (~78% relative reduction), and enabling MUX reduces it further to ~0.125; random padding including XTLS-Vision showed only marginal improvement. As documented in `encapsulated-tls-handshake-fingerprinting` and `stream-multiplexing-tunnels`, this establishes MUX-by-default as a meaningful structural countermeasure against the nested-handshake burst pattern — but only where the transport actually supports multiplexing. It is currently unaudited whether RIPDPI's relay transports enable MUX by default or leave per-app parallel inner-TLS connections open.

## Proposed change

This is a two-phase spike; no production code changes until Phase 1 delivers a written audit note.

**Phase 1 — Audit (read-only):** Review each relay-transport backend in `ripdpi-relay-core/src/backend/` and the relevant protocol crates (`ripdpi-vless`, `ripdpi-trojan`, `ripdpi-shadowsocks`, `ripdpi-tuic`, `ripdpi-hysteria2`) to determine:
- Whether the transport supports stream multiplexing at all (mux-capable vs mux-incapable).
- For mux-capable transports: whether the current default config enables MUX or leaves parallel non-multiplexed inner-TLS sessions as the default.
- For transports where MUX is supported but off by default: identify the config surface (`VlessMuxConfig`, `RelayMux` pool policy, etc.) that controls the default.

Deliver findings as a short design note (appended to this task file or filed as `docs/native/relay-mux-posture-audit-2026.md`) classifying each transport's posture: `mux-on-by-default`, `mux-supported-off-by-default`, or `mux-incapable`, with a pointer to the controlling config surface for each.

**Phase 2 — Conformance fixture (contingent on Phase 1):** For each transport classified `mux-supported-off-by-default`, add a loopback conformance test in the appropriate crate's test suite that:
- Drives a configurable number of concurrent streams through the transport backend.
- Asserts that after the first session is established, subsequent streams within the same mux-capable session do not open a new outer TLS handshake (i.e., the nested-handshake burst pattern is absent when MUX is enabled).
- Resides under `contract-fixtures/` or as an inline test in the protocol crate, consistent with the fixture discipline in the conformance epic.

Phase 2 scope is bounded by Phase 1 findings. If all relevant transports are already `mux-on-by-default`, the fixture criterion collapses to a regression test asserting the default is not changed.

## Acceptance criteria

- [x] Phase 1: a written audit note classifies every RIPDPI relay-transport backend as `mux-on-by-default`, `mux-supported-off-by-default`, or `mux-incapable`, with a pointer to the controlling config surface for each. (See **Phase 1 — Audit findings** below.)
- [x] Phase 2 (collapsed): the relevant TLS-over-TCP transports are either `mux-on-by-default` (xHTTP/H2: VLESS `xhttp`, Cloudflare) or `mux-incapable` in the datapath (classic Reality, ShadowTLS, Trojan, chain) — no `mux-supported-off-by-default` member exists, so Phase 2 collapses to the documented regression test asserting the default is not changed: `relay_tls_in_tls_exposure_posture_is_pinned_for_every_kind` in `ripdpi-relay-core/src/tests.rs`.
- [x] No existing conformance fixtures in `epic-protocol-conformance-tests` are regressed (`cargo nextest run -p ripdpi-relay-core --locked` green).
- [x] The audit note explicitly records the scope limitation of the source study (US academic ISP feasibility measurement; not a TSPU/GFW deployment finding). (See **Phase 1 — Audit findings → Scope limitation**.)

## Risks / open questions

- The Xue et al. study is a US-ISP feasibility measurement; applicability to TSPU/GFW DPI infrastructure is unverified. Phase 1 must note this scope boundary explicitly so findings are not overstated.
- VLESS wire-mux datapath may be unimplemented in the relay datapath (only `VlessMuxConfig` parsing exists; the Reality client may never multiplex — see `add-protocol-cross-stack-chain-tests-vless-over-xhttp-over-reality`). If VLESS is `mux-incapable` at the datapath level, Phase 2 for VLESS is blocked on the wire-mux feature landing first.
- Multiplexing changes connection-lifetime behavior and may interact with the existing per-exit-IP concurrent-session cap in `ripdpi-relay-core/src/backend/pool.rs` (see `per-exit-ip-tls-cap-with-mux-preference-in-relay-core`). The audit should check whether enabling MUX-by-default would affect that cap's accounting.
- `mux-incapable` transports (e.g. the direct-desync path in `ripdpi-proxy-runtime`) have no applicable countermeasure from this study; the audit should document why rather than leaving them blank.

## References

- `encapsulated-tls-handshake-fingerprinting` — primary source wiki page; Xue et al. USENIX Security 2024 figures and scope.
- `stream-multiplexing-tunnels` — MUX protocol mechanics and RIPDPI relay-mux architecture.
- `per-exit-ip-tls-cap-with-mux-preference-in-relay-core` — adjacent task: per-exit-IP session cap with mux-preference in `relay-core`; different motivation (home-ISP TLS-session-count policing) and mechanism (admission gate), but shares the `RelayMux` / `pool.rs` subsystem.
- `epic-protocol-conformance-tests` — parent epic; owns the `contract-fixtures/` substrate.
- `add-protocol-cross-stack-chain-tests-vless-over-xhttp-over-reality` — sibling; VLESS wire-mux datapath blocker relevant to Phase 2 scope for VLESS.

## Phase 1 — Audit findings (2026-06-17)

All facts read from `native/rust/crates` at HEAD (`5ff7b9e4`), not from prior docs.

### Two mux layers, neither is a concurrent stream multiplexer in the relay pool

1. **Relay session pool (`ripdpi-relay-mux` `RelayMux`, used by every backend via
   `relay-core/src/backend/pool.rs::PooledRelayBackend`).** This is a *serial*
   pool, **not** a concurrent multiplexer: `relay-mux/src/pool.rs` reuses the one
   cached session only when `active_leases == 0` (and `descriptor.reusable`).
   Concurrent SOCKS sessions therefore each acquire their own session — the pool
   does not by itself fold parallel inner-TLS flows onto one outer connection.
   Per-kind `max_active_leases` (`runtime_validation`) tunes pool size, not mux.
2. **Transport-internal stream mux.** The only stream multiplexing wired into a
   **TLS-over-TCP** relay datapath is **xHTTP / HTTP-2** (`ripdpi-xhttp`
   `XmuxConfig::default()` = 8 connections × 32 concurrent streams), reached by the
   two builders that produce `RelayBackend::Xhttp`: `vless_reality.rs::build_xhttp`
   (VLESS Reality `vless_transport = "xhttp"`) and `cloudflare_tunnel.rs`
   (`XhttpSessionMode::Tls`). The cross-stack test
   `cross_stack_vless_over_xhttp_over_reality_single_stream` exercises multiple
   round-trips over one xHTTP stream. QUIC transports (`hysteria2`, `tuic_v5`,
   `masque`) multiplex natively, but over UDP — outside the TLS-in-TLS-over-TCP
   surface this task targets.

### Open question resolved: VLESS sing-mux is parse-only

`VlessMuxConfig` (`ripdpi-vless/src/mux.rs`, sing-mux / yamux + padding) is
**parsed and validated but never consumed by a relay datapath** — `grep` for
`VlessMuxConfig` across the workspace finds only `ripdpi-vless` config/parse code
and its own unit tests; no `relay-core` backend wires it in. So classic VLESS
Reality (TCP) has **no wire-mux datapath**; its only multiplexing-capable mode is
xHTTP/H2 (a different mechanism). This confirms the risk noted in *Risks / open
questions* above.

### Per-kind classification

| relay_kind | outer transport | tls_over_tcp | classification | controlling config surface |
|---|---|---|---|---|
| hysteria2 | QUIC/UDP | no | mux-on (QUIC-native) | n/a (TLS-in-TLS-over-TCP N/A) |
| tuic_v5 | QUIC/UDP | no | mux-on (QUIC-native) | n/a |
| masque | HTTP/3 over QUIC | no | mux-on (QUIC-native) | n/a |
| mieru | custom obfuscation | no | mux-supported (config) | `MieruRelayConfig.multiplexing` (not TLS) |
| ssh | SSH binary | no | mux-incapable | n/a (not TLS) |
| shadowsocks | AEAD | no | mux-incapable | n/a (not TLS) |
| cloudflare_tunnel | xHTTP/H2 over TLS/TCP | yes | **mux-on-by-default** | `XhttpTlsConfig.xmux = XmuxConfig::default()` |
| vless_reality (`xhttp`) | xHTTP/H2 over Reality TLS/TCP | yes | **mux-on-by-default** | `vless_reality.rs::build_xhttp` `xmux: XmuxConfig::default()` |
| anytls | AnyTLS / TCP | yes | mux-on (protocol)¹ | `ripdpi_anytls` session layer |
| tor | Tor link TLS / TCP | yes | mux-on (circuits)¹ | Tor protocol |
| naiveproxy | H2 over TLS / TCP | yes | mux-on (subprocess H2)¹ | out-of-process (`builder: None`) |
| **vless_reality (classic TCP)** | Reality TLS / TCP | **yes** | **mux-incapable (datapath)** | `descriptor.reusable = false`; `VlessMuxConfig` parse-only |
| **chain_relay** | per-hop Reality / TCP | **yes** | **mux-incapable** | `descriptor.reusable = false` |
| **shadowtls_v3** | ShadowTLS / TCP | **yes** | **mux-incapable** | `descriptor.reusable = false` |
| **trojan** | TLS / TCP | **yes** | **mux-incapable** | `descriptor.reusable = false` |

¹ `mux-on (protocol/subprocess)` = the transport multiplexes *internally* (AnyTLS
substreams + padding; Tor circuits; NaiveProxy's Chromium H2), not via the
relay-core wire layer. Not verified at the relay-session datapath in this audit —
flagged for a Phase-2 follow-up if a per-transport stream-reuse assertion is wanted.

### Headline

No relay kind is **`mux-supported-off-by-default`** in the TLS-over-TCP datapath:
where stream mux exists it is on by default (xHTTP/H2), and the four
fingerprint-exposed kinds — classic `vless_reality`, `chain_relay`,
`shadowtls_v3`, `trojan` — are `mux-incapable` (each SOCKS session opens a fresh
outer TLS handshake; `descriptor.reusable = false`, in-process). The conformance
fixture derives exactly this set from the authoritative `reusable` flag, so it
cannot silently drift.

### Scope limitation (acceptance criterion)

The motivating measurement (Xue et al., USENIX Security 2024, on the Merit Network
US academic ISP) is a **feasibility study, not a TSPU/GFW deployment finding**.
Applicability to production censor DPI is unverified; the `mux-incapable`
classification above is a structural observation about RIPDPI's datapath, not a
claim that these transports are currently blocked anywhere.
