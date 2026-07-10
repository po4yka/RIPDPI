# SPEC — `ripdpi-masque`

## Scope

MASQUE client supporting HTTP/3 CONNECT-UDP and HTTP/2 classic CONNECT for TCP. Includes generic/self-hosted RFC 9298 provider authentication with bearer tokens or TLS client certificates, plus the deployer-supplied Privacy Pass token flow.

## Standards

- **RFC 9298** — Proxying UDP in HTTP (CONNECT-UDP)
- **RFC 9000** — QUIC (post-handshake path validation on rebind)
- **RFC 9114** — HTTP/3 framing
- **RFC 9113** — HTTP/2 framing and classic CONNECT for TCP
- **RFC 6750** — Bearer auth

Provider extensions:

- Generic/self-hosted bearer authentication — `Authorization: Bearer <token>`
- Generic/self-hosted TLS client certificate authentication — standard TLS client auth
- Privacy Pass — deployer-supplied provider plug-in

## Auth modes

| Mode | Module | Notes |
|---|---|---|
| Bearer | `auth.rs` | Static token |
| Preshared | `auth.rs` | `Proxy-Authorization: Preshared <token>` |
| Privacy Pass | `privacy_pass.rs` | Deployer-supplied provider; retry on challenge |
| `cloudflare_mtls` | `tls.rs`, `provider_adapter.rs` | Legacy mode string retained for schema compatibility; treated as generic TLS client certificate auth |

## Transport selection

- UDP attempts HTTP/3 CONNECT-UDP (`h3.rs`) and may fall back to HTTP/2 CONNECT-UDP capsules when `use_http2_fallback` is true.
- TCP uses the independent `tcp_protocol` policy. `http2` selects HTTP/2 classic CONNECT (`h2.rs`) directly; it is not a fallback from a defective H3 attempt.
- TCP configurations that request HTTP/3 are rejected with `Unsupported` before DNS, socket creation, or QUIC establishment. The pinned H3 encoder emits scheme and path pseudo-fields for every request and therefore cannot encode the RFC 9114 classic CONNECT request shape.

## URL validation

Non-HTTPS URLs are rejected before native startup. CONNECT-UDP derives its URI-template base from the configured endpoint path; endpoint query templates are not supported. TCP classic CONNECT uses the target authority form required by RFC 9113.

## Known unsupported modes

- HTTP/3 TCP is intentionally unsupported until the client has an encoder that can emit RFC 9114 classic CONNECT without scheme, path, or an Extended CONNECT protocol token.

## Non-goals

- Server-side MASQUE.
- Silent H3-to-H2 fallback for TCP.
- Commercial-relay provider adapters such as iCloud Private Relay or Cloudflare proprietary auth.
- CONNECT-IP from RFC 9484.
