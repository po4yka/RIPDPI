---
title: Audit relay transports for MUX-default posture against TLS-in-TLS fingerprinting and add nested-handshake conformance fixture
type: task
status: backlog
area: transport
priority: medium
owner: unassigned
parent: epic-protocol-conformance-tests
blocks: []
blocked_by: []
created: 2026-06-15
updated: 2026-06-15
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

- [ ] Phase 1: a written audit note classifies every RIPDPI relay-transport backend as `mux-on-by-default`, `mux-supported-off-by-default`, or `mux-incapable`, with a pointer to the controlling config surface for each.
- [ ] Phase 2 (contingent): for each `mux-supported-off-by-default` transport, a loopback conformance test is present and green asserting concurrent streams reuse the mux session rather than opening new outer TLS handshakes when MUX is enabled.
- [ ] No existing conformance fixtures in `epic-protocol-conformance-tests` are regressed.
- [ ] The audit note explicitly records the scope limitation of the source study (US academic ISP feasibility measurement; not a TSPU/GFW deployment finding).

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
