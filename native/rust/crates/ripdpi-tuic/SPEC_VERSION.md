# Spec Version

This crate's wire format (TUIC v5 over QUIC) is pinned against the following upstream reference.

- **Upstream repo:** https://github.com/tuic-protocol/tuic
- **Upstream tag:** v5 (SPEC.md); reference implementations `tuic-server-1.0.0` and `tuic-client-1.0.0`
- **Upstream commit:** 8e118f242f24a17a9f487dc344cc50d7e63e557e (SPEC.md)
- **Last reviewed:** 2026-11-09
- **Owner:** unassigned

## Scope

This crate implements the TUIC v5 client over Quinn, including:

- v5 wire framing (`TUIC_VERSION = 0x05` in `protocol.rs`)
- `AUTHENTICATE`, `CONNECT`, `PACKET` command encoding
- Address representation: `None`, `Domain(host, port)`, `Socket(SocketAddr)`
- QUIC bi-stream lifecycle and UDP packet forwarding

TUIC v4 is intentionally unsupported. The v5-only decision and failure-classification surface are documented in `docs/architecture/tuic-v4-policy.md`.

## Drift policy

TUIC upstream activity is slower than xray-core. The weekly workflow validates
presence, formatting, and recorded pins; it does not fetch upstream or detect
wire drift automatically. Auth and command-byte changes remain the most likely
source of drift.
