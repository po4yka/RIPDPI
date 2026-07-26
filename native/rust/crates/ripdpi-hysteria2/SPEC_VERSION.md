# Spec Version

This crate's wire format (Hysteria 2 over QUIC, Salamander obfuscation, port-hopping schedule, and the H3 authentication exchange) is pinned against the following upstream reference.

- **Upstream repo:** https://github.com/apernet/hysteria
- **Upstream tag:** v2 (latest tagged release line)
- **Upstream commit:** unverified-as-of-2026-05-15
- **Last reviewed:** 2026-05-15
- **Owner:** unassigned

## Scope

This crate implements the Hysteria 2 client over Quinn, including:

- QUIC transport setup with custom congestion config
- Salamander XOR-style obfuscation keyed by server-supplied secret
- Port hopping window scheduling
- HTTP/3 `POST https://hysteria/auth` with an opaque `Hysteria-Auth` value and status `233` on success
- H3 CONNECT and CONNECT-UDP paths for TCP and UDP forwarding

Pins on the v2 release line. v1 was removed entirely per `docs/adr/0004-protocol-support-policy.md`.

## Drift policy

Watched weekly via `.github/workflows/upstream-spec-watch.yml`. Salamander and port-hopping are the most volatile areas; conformance fixtures live under `contract-fixtures/hysteria2/<upstream-tag>/`.
