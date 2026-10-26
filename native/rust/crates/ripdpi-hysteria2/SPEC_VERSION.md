# Spec Version

This crate's wire format (Hysteria 2 over QUIC, Salamander obfuscation, remote port hopping, and the H3 authentication exchange) is pinned against the following upstream reference.

- **Upstream repo:** https://github.com/apernet/hysteria
- **Upstream tag:** v2 (latest tagged release line)
- **Upstream commit:** unverified-as-of-2026-05-15
- **Last reviewed:** 2026-05-15
- **Owner:** unassigned

## Scope

This crate implements the Hysteria 2 client over Quinn, including:

- QUIC transport tuned to the reference client: 10 s keep-alive, 30 s idle timeout, 8 MiB stream and 20 MiB connection receive windows. Congestion control is quinn's default Cubic; Brutal, BBR and `Hysteria-CC-RX` rate negotiation are not implemented (the client sends `Hysteria-CC-RX: 0` and ignores the server value)
- Salamander XOR-style obfuscation keyed by server-supplied secret
- Remote port and local socket rotation on a fixed or randomized schedule
- HTTP/3 `POST https://hysteria/auth` with an opaque `Hysteria-Auth` value and status `233` on success
- H3 CONNECT and CONNECT-UDP paths for TCP and UDP forwarding

Pins on the v2 release line. v1 was removed entirely per `docs/adr/0004-protocol-support-policy.md`.

## Drift policy

Watched weekly via `.github/workflows/upstream-spec-watch.yml`. Salamander and port-hopping are the most volatile areas; conformance fixtures live under `contract-fixtures/hysteria2/<upstream-tag>/`.

## Independent acceptance peer

- Release: `app/v2.13.0`, reviewed on 2026-10-24.
- Executable digests and platform names: `test-lab/acceptance/peers/hysteria.py`.
- Upstream QUIC fix: `7612ad1036957cde45d672bebb8b9ec9c2e9b2a7`, included
  in the peer's `a10df75c260cee8161f3261d63a37bd125a6bb2a` QUIC dependency.
- This acceptance pin does not replace the wire-reference review status above.
