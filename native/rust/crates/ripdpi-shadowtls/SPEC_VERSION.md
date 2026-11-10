# Spec Version

This crate's wire format (ShadowTLS v3 HMAC-SHA1 handshake) is pinned against the following upstream reference.

- **Upstream repo:** https://github.com/ihciah/shadow-tls
- **Upstream tag:** v3 (release line)
- **Upstream commit:** 02dd0bc7bae8a2011729f95021690e694fd8e43e
- **Last reviewed:** 2026-11-10
- **Owner:** unassigned

## Scope

This crate implements the ShadowTLS v3 client, including:

- HMAC-SHA1 handshake auth: the first 4 bytes of the HMAC sign the ClientHello, and SHA-256(password || ServerRandom) is the server's XOR key
- HMAC-tagged framing
- TLS-cover handshake against the configured upstream server name

ShadowTLS v2 is intentionally unsupported. The v3-only decision and failure-classification surface are documented in `docs/architecture/shadowtls-version-policy.md`.

## Drift policy

ShadowTLS upstream is small; wire-affecting changes are rare. The weekly
workflow validates presence, formatting, and recorded pins; it does not fetch
upstream or prove that this pin is current. A maintainer must perform the
upstream comparison before updating `Last reviewed`.
