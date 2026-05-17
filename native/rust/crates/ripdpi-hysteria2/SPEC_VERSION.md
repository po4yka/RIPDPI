# Spec Version

This crate's wire format (Hysteria 2 over QUIC, Salamander obfuscation, port-hopping schedule, Privacy Pass-like auth) is pinned against the following upstream reference.

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
- Bearer / preshared auth headers
- H3 CONNECT and CONNECT-UDP paths for TCP and UDP forwarding

Pins on the v2 release line. v1 is documented as unsupported in `docs/tasks/issues/add-hysteria-v1-outbound-client-crate-and-profile-editor.md`.

## Drift policy

Watched weekly via `.github/workflows/upstream-spec-watch.yml`. Salamander and port-hopping are the most volatile areas; conformance fixtures live under `contract-fixtures/hysteria2/<upstream-tag>/`.
