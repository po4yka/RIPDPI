# Spec Version

This crate's wire format (TUIC v5 over QUIC) is pinned against the following upstream reference.

- **Upstream repo:** https://github.com/EAimTY/tuic
- **Upstream tag:** v5
- **Upstream commit:** unverified-as-of-2026-05-15
- **Last reviewed:** 2026-05-15
- **Owner:** unassigned

## Scope

This crate implements the TUIC v5 client over Quinn, including:

- v5 wire framing (`TUIC_VERSION = 0x05` in `protocol.rs`)
- `AUTHENTICATE`, `CONNECT`, `PACKET` command encoding
- Address representation: `None`, `Domain(host, port)`, `Socket(SocketAddr)`
- QUIC bi-stream lifecycle and UDP packet forwarding

TUIC v4 is intentionally unsupported. The v5-only decision and failure-classification surface are documented in `docs/architecture/tuic-v4-policy.md`.

## Drift policy

TUIC upstream activity is slower than xray-core. Watched weekly via `.github/workflows/upstream-spec-watch.yml`. Wire-affecting changes are expected to be rare; auth and command-byte changes are the most likely source of drift.
