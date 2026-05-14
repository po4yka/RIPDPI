---
title: Refactor QUIC and H3 into a composable transport crate
type: task
status: done
area: transport
priority: medium
owner: unassigned
parent: epic-composable-transport-layer-parity
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-14
---

- [x] #task Refactor QUIC and H3 into a composable transport crate #repo/RIPDPI #area/transport #status/done 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `refactor-quic-and-h3-into-a-composable-transport-crate`
- **Verify:** `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-hysteria2`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-hysteria2/**`, `native/rust/crates/ripdpi-masque/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Extract a `ripdpi-transport-quic` crate (and optional H3-specific
facade) so VLESS, VMess, and future outbounds can run over QUIC or
HTTP/3 directly — today QUIC/H3 is protocol-locked inside
`ripdpi-hysteria2` and `ripdpi-masque`.

## Context

Hysteria2 and MASQUE each pull `quinn` + `h3` + `h3-quinn` directly
and use them for their specific protocol needs. VLESS-QUIC, VMess-
QUIC, and generic H3 CONNECT are sing-box-supported outbound shapes
that RIPDPI cannot serve because there's no composable QUIC layer.
Refactor rather than duplicate: move the shared `quinn` setup into a
common crate, keep the Hysteria2 and MASQUE protocol-specific logic
on top.

## Acceptance criteria

- [ ] `ripdpi-transport-quic` exposes `QuicTransport` (bi-directional
    stream) and `QuicDatagramTransport` (CONNECT-UDP / datagram)
    surfaces.
- [ ] Shared `quinn` + `rustls` config factory in the crate;
    Hysteria2 and MASQUE consume it instead of building their own.
- [ ] `ripdpi-hysteria2` and `ripdpi-masque` continue passing all
    existing tests after migration.
- [ ] H3 facade (`H3Transport`) exposes a CONNECT-capable HTTP/3
    surface composable under VLESS / VMess / generic outbounds.
- [ ] ALPN, SNI, and per-profile uTLS-style fingerprinting are
    configurable at the transport boundary.
- [ ] VLESS outbound gains a `transport: quic` mode in its profile
    editor and wire-tests against an Xray VLESS-QUIC server.

## Links

- [[Epic - Composable transport layer parity]]


## control-plane-hardening

## Work log

- 2026-05-14: Implemented as the public `quic_transport` module of
  `ripdpi-hysteria2`
  (`native/rust/crates/ripdpi-hysteria2/src/quic_transport/`), not a separate
  crate. **Contract resolution:** the Summary sketches a new
  `ripdpi-transport-quic` crate, but the Verify command targets
  `-p ripdpi-hysteria2` and the Scope (`ripdpi-hysteria2/**`,
  `ripdpi-masque/**`) forbids registering a new workspace member.
  `ripdpi-hysteria2` is the natural home — it already owns the
  `quinn` + `h3` + `h3-quinn` dependency set and the bi-stream prototype
  (`tcp::DuplexStream`) this module generalizes. `ripdpi-masque` depends on
  `ripdpi-hysteria2` and consumes the shared module.
- `quic_transport::config`: `QuicTransportConfig` + the shared
  `quinn` + `rustls` config factory (`build_rustls_client_config` /
  `build_quinn_client_config`). ALPN, SNI, and a per-profile uTLS-style
  fingerprint-profile name are all configurable at the transport boundary.
  The `ring` crypto provider is pinned explicitly (the workspace enables
  both `ring` and `aws-lc-rs`, so the bare `ClientConfig::builder()` is
  ambiguous). The no-op `insecure` cert verifier — previously duplicated in
  both crates — now lives here once.
- `quic_transport::endpoint`: shared `build_client_udp_socket` /
  `build_quic_endpoint` / `maybe_rebind_endpoint`. The
  `build_client_udp_socket` + `try_bind_low_port` logic was byte-for-byte
  duplicated between `ripdpi-hysteria2::tls_quic` and
  `ripdpi-masque::h3::socket`; there is now one copy.
- `quic_transport::stream`: `QuicTransport` (cheap `Clone` handle over a
  `quinn::Connection`) + `QuicBiStream` (`AsyncRead + AsyncWrite`
  bi-directional stream surface) — the "QUIC bi-directional stream surface"
  the spec asks for.
- `quic_transport::datagram`: `QuicDatagramTransport` — the "QUIC datagram
  surface" (CONNECT-UDP / unreliable datagrams).
- `quic_transport::h3`: `H3Transport` facade exposing a CONNECT-capable
  HTTP/3 surface (`H3ConnectKind::{ConnectUdp, WebTransport}`,
  `build_connect_request` setting the extended-CONNECT `:protocol` via the
  `h3::ext::Protocol` request extension — HTTP/3 carries `:protocol` as a
  pseudo-header, not a normal header).
- **Refactor (not duplication):** `ripdpi-hysteria2::tls_quic::build_tls_config`
  now delegates to the shared `QuicTransportConfig` factory;
  `tls_quic::build_client_udp_socket` delegates to the shared endpoint
  helper; the private `NoCertificateVerification` copy was deleted.
  `ripdpi-masque::h3::socket::build_client_udp_socket` delegates to
  `ripdpi_hysteria2::build_client_udp_socket`; masque's now-unused `socket2`
  dependency was dropped. Both crates' existing test suites still pass.
- VLESS-QUIC profile-editor mode + the Xray VLESS-QUIC wire test are
  deferred: `ripdpi-vless`'s VLESS-QUIC path is out of this task's Scope
  (`ripdpi-hysteria2/**` + `ripdpi-masque/**` only). The composable surface
  it would build on (`QuicTransport` + `H3Transport`) is in place.
- TDD: config / endpoint / stream / datagram / h3 unit tests plus three
  real-QUIC-connection integration tests written RED first, driven GREEN
  (caught and fixed the `ring`-provider ambiguity and the `:protocol`
  pseudo-header handling along the way).
- Verify: `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-hysteria2`
  → 29 passed, 0 skipped, exit 0. Workspace clippy (`-D warnings`),
  `cargo fmt`, `cargo check --workspace`, and the whole-workspace nextest
  all clean.
- New dependencies: `rcgen` added as a `ripdpi-hysteria2` dev-dependency
  (QUIC server test scaffold); `ripdpi-hysteria2` added to
  `ripdpi-masque/Cargo.toml`; `socket2` removed from `ripdpi-masque`
  (now unused). All workspace deps already in the tree.
