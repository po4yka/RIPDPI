# Spec Version

This crate's wire format (ShadowTLS v3 HKDF/HMAC handshake) is pinned
against the following upstream reference.

- **Upstream repo:** https://github.com/ihciah/shadow-tls
- **Upstream tag:** v3 (release line)
- **Upstream commit:** unverified-as-of-2026-05-15
- **Last reviewed:** 2026-05-15
- **Owner:** unassigned

## Scope

This crate implements the ShadowTLS v3 client, including:

- HKDF-derived handshake auth
- HMAC-tagged framing
- TLS-cover handshake against the configured upstream server name

ShadowTLS v2 is intentionally unsupported pending the policy decision in
`docs/tasks/issues/add-shadowtls-v2-compatibility-or-document-v3-only.md`.

## Drift policy

ShadowTLS upstream is small; wire-affecting changes are rare. Watched
weekly via `.github/workflows/upstream-spec-watch.yml`.
