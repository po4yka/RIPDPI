# SPEC — `ripdpi-masque`

## Scope

MASQUE client supporting HTTP/3 and HTTP/2 transports, including
CONNECT (TCP) and CONNECT-UDP. Includes provider-specific extensions
for Cloudflare-direct (mTLS + `sec-ch-geohash`) and Privacy Pass.

## Standards

- **RFC 9298** — Proxying UDP in HTTP (CONNECT-UDP)
- **RFC 9000** — QUIC (post-handshake path validation on rebind)
- **RFC 9114** — HTTP/3 framing
- **RFC 9113** — HTTP/2 framing (fallback path)
- **RFC 6750** — Bearer auth

Provider extensions:

- Cloudflare mTLS — vendor-specific
- Privacy Pass — deployer-supplied provider plug-in

## Auth modes

| Mode | Module | Notes |
|---|---|---|
| Bearer | `auth.rs` | Static token |
| Preshared | `auth.rs` | `Proxy-Authorization: Preshared <token>` |
| Privacy Pass | `privacy_pass.rs` | Deployer-supplied provider; retry on challenge |
| `cloudflare_mtls` | `tls.rs`, `auth.rs` | Client cert + optional geohash header |

## Transport selection

- H3 first (`h3.rs`)
- H2 fallback (`h2.rs`) when H3 is unreachable; reason captured in
  the migration snapshot

## URL validation

Non-HTTPS URLs are rejected before native startup. Endpoint path and
query are preserved through H3 and H2 request construction.

## Known divergences from standards

- Cloudflare-direct flow couples the crate to one vendor; the
  `MasqueProviderAdapter` extraction in
  `docs/tasks/issues/extract-masque-provider-adapter-trait-to-decouple-cloudflare.md`
  is the planned decoupling.
- H3-to-H2 fallback telemetry is incomplete; see
  `docs/tasks/issues/add-h3-to-h2-fallback-telemetry-rollout-validation.md`.

## Non-goals

- Server-side MASQUE.
- Pure HTTP/2 MASQUE without the H3 attempt (the H2 fallback is
  reactive, not primary).
