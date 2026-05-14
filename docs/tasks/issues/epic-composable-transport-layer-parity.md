---
title: Epic - Composable transport layer parity
type: epic
status: done
area: epic
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [x] #task Epic - Composable transport layer parity #repo/RIPDPI #area/epic #status/done ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `epic-composable-transport-layer-parity`
- **Verify:** `all child rows in GOAL_LEDGER.md are DONE or BLOCKED`
- **Scope (only modify these + this file + the ledger):** _epic — coordination only; child tasks carry the file scope_
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Goal

Close the transport-layer gaps uncovered by the 2026-04-24 audit so every
outbound protocol in RIPDPI can use every carrier NekoBox ships: plain
TCP, TLS, Reality, WebSocket, gRPC, HTTP/2, HTTP/3, QUIC, HTTPUpgrade,
xHTTP, and wire-level multiplexing. Today transports are protocol-locked
(H3/QUIC only in Hysteria2/MASQUE; WebSocket only in the Telegram MTProto
crate) and three transports are missing outright (gRPC, HTTPUpgrade,
sing-mux/yamux/smux wire protocol).

## Why now

Outbound protocol crates (Epic - Extended outbound protocol support) must
compose with a transport to be useful. Trojan-over-WebSocket, VLESS-over-
gRPC, VMess-over-H2, and Trojan-over-HTTPUpgrade are common real-world
subscription shapes. Without a composable transport layer, every new
protocol epic produces half-usable outbounds. Ship the transports once,
wire every protocol through them.

## Key decisions

- **Transports live in their own crates** (`ripdpi-transport-ws`,
`ripdpi-transport-grpc`, `ripdpi-transport-httpupgrade`,
`ripdpi-transport-mux`, `ripdpi-transport-quic`). Each exposes an
`AsyncRead + AsyncWrite` (or `Sink + Stream` for datagram) surface
that any outbound crate can layer onto.
- **Pick WebSocket from the existing `ripdpi-ws-tunnel` crate and
generalize,** do not duplicate. The Telegram-only call site becomes
one consumer of the generic crate.
- **gRPC uses `tonic`** with protobuf framing; do NOT roll our own gRPC.
- **Mux: ship sing-mux + yamux.** `smux` is the odd-one-out (Trojan-Go
only); ship it if and only if real Trojan-Go subscriptions need it.
- **QUIC/H3 composable transport reuses the existing `quinn` + `h3`
stack** from Hysteria2/MASQUE; refactor to a shared crate rather than
two protocol-locked copies.
- **uTLS + Reality + ECH are already ahead of NekoBox.** Do NOT
regress; transport crates must accept a uTLS-capable TLS connector
as the composition point.

## Scope

- **In scope:** five new or generalized transport crates; adapter
traits; wire-format conformance tests against upstream sing-box
fixtures; composition docs (which protocol × transport combinations
are expected to work).
- **Out of scope:** meek / meek-lite (deprecated); obfs4 over non-
standard transports (Lyrebird already covers obfs4); custom TLS-
fragmentation layers beyond finalmask (already present).

## Ship definition

- [ ] `ripdpi-transport-ws` is a generic WebSocket transport composable
    under at least three outbounds (Trojan, VLESS, VMess).
- [ ] `ripdpi-transport-grpc` implements Xray-compatible gRPC framing
    (service name `proxy.v2ray.com.Service`, method `Tun`) via `tonic`.
- [ ] `ripdpi-transport-httpupgrade` speaks the HTTP/1.1 Upgrade dance
    used by Xray/V2Fly `httpupgrade` inbound.
- [ ] `ripdpi-transport-mux` implements sing-mux and yamux wire
    protocols with upstream-parity test vectors.
- [ ] `ripdpi-transport-quic` exposes a composable QUIC stream and
    datagram transport usable under VLESS (VLESS-QUIC), VMess, and
    future protocols.
- [ ] Every current outbound crate continues to pass its existing
    tests; no regression.
- [ ] Documentation table in `docs/transports.md` lists every
    protocol × transport combination and whether it is supported,
    not-supported-by-design, or pending implementation.

## Child tasks

- [[Generalize WebSocket transport for outbound composition]]
- [[Add gRPC transport crate with tonic and Xray-compatible framing]]
- [[Add HTTPUpgrade transport crate]]
- [[Add sing-mux and yamux wire multiplexing]]
- [[Refactor QUIC and H3 into a composable transport crate]]

## Dependencies

- Feeds: [[Epic - Extended outbound protocol support]] — every new
outbound crate in that epic can pick from the composable transport
set; several subscription shapes (Trojan-WS, VLESS-gRPC,
VMess-H2-HTTPUpgrade) cannot work without these transports.
- Feeds: [[Epic - NekoBox subscription and profile import]] — Clash
and sing-box subscription parsers can populate
transport-specific fields once the transports exist.

## Risks / open questions

- gRPC over a uTLS-spoofed TLS is non-trivial; `tonic` wants its own
`rustls` connector. Decide: expose a `hyper` client with a
swappable connector, or ship a thin `tonic` alternative that
accepts a raw TCP+TLS stream.
- Wire-mux on Android may raise memory pressure under many parallel
flows; benchmark the pool-size choice before shipping defaults.
- Composable QUIC transport means VLESS-QUIC becomes possible — but
subscription providers rarely ship VLESS-QUIC profiles. Ship the
composability; surface the profile type as "advanced" in UI.
- uTLS fingerprint parity under gRPC may degrade JA3/JA4 scores;
validate against the existing golden fixtures in `ripdpi-tls-
profiles`.

## Links

- [[ripdpi-android]]
- [[Epic - Extended outbound protocol support]]
- Child issues: 6
