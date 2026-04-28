# ADR-007: RelaySession::open_datagram is a live API, not an orphan

**Status:** Accepted  
**Date:** 2026-04-27  
**Scope:** `ripdpi-relay-mux`, `ripdpi-relay-core`

## Context

A Phase 3 cleanup audit flagged `RelaySession::open_datagram` as a potential
orphan trait method on the grounds that UDP traffic in `ripdpi-relay-core`
passes through a `UdpSocket` that appeared unrelated to the trait. The
audit proposed either removing the method or unifying the UDP path.

Investigation findings:

1. **Three backends implement `open_datagram` with real UDP sessions:**
   - `Hysteria2Session` → `ripdpi_hysteria2::UdpSession` via `client.udp_session()`
   - `TuicSession` → `ripdpi_tuic::UdpSession` via `client.udp_session()`
   - `MasqueSession` → `ripdpi_masque::MasqueUdpRelay` via `client.udp_session()`

2. **Four TCP-only backends return `Unsupported` correctly and by design:**
   - `VlessRealitySession`, `XhttpSession`, `ChainRelaySession`, `ShadowTlsSession`
   - These also set `RelayCapabilities { udp: false, .. }` in their factory,
     so callers check `backend.udp_capable()` before ever calling `open_datagram`.

3. **The call chain is fully connected:**
   ```
   handle_udp_associate()          [SOCKS5 server, relay-core]
     └─ RelayBackend::open_udp_session()
          └─ PooledRelayBackend::open_udp_session(map)
               └─ RelayMux::open_datagram()          [relay-mux]
                    └─ RelaySession::open_datagram()  [trait dispatch]
                         └─ Hysteria2/Tuic/Masque backend UDP session
   ```

4. **The `UdpSocket` at line 1039 in `relay-core/src/lib.rs` is the
   local SOCKS5-side socket** — it binds an ephemeral port on `local_socks_host`
   to receive UDP datagrams from the Android SOCKS5 client. It is not a bypass
   of the trait; it is the inbound half of the SOCKS5 UDP ASSOCIATE flow.
   The outbound half (towards the relay server) goes through `open_datagram`.

## Decision

**Variant C — Document status quo; no code changes to the trait.**

The trait method is correctly implemented and actively used in production.
Removing it (Variant A) would break Hysteria2, TUIC, and MASQUE UDP support.
Unifying further (Variant B) is not needed; the architecture is already unified
through `RelayMux::open_datagram`.

The `relay-mux` crate gains a module-level doc comment clarifying the
TCP/UDP split so future readers do not re-raise this question.

## Consequences

- `RelaySession` remains a unified session abstraction covering both TCP streams
  (`open_stream`) and UDP datagrams (`open_datagram`).
- Backends that do not support UDP **must** return `io::ErrorKind::Unsupported`
  from `open_datagram` and advertise `RelayCapabilities { udp: false }`.
- The SOCKS5 server layer is the only caller of `open_datagram` in production;
  new callers must check `udp_capable()` before calling it.
- The audit finding is closed as a false positive; no further action required
  for P3.3.

## Alternatives Considered

- **Variant A (remove `open_datagram`):** Would silently drop UDP relay
  capability for Hysteria2, TUIC, and MASQUE. Rejected.
- **Variant B (unify `UdpSocket` through the trait):** The local SOCKS5 socket
  cannot go through `RelaySession` — it binds on the device's loopback/LAN
  interface, not on the relay tunnel. Architecturally correct as-is. Rejected.
- **Add a separate `UdpRelaySession` trait:** Unnecessary indirection; the
  existing `RelayCapabilities::udp` flag already gates the call site.
