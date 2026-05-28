# ripdpi-xhttp

**Responsibility:** the xHTTP transport — HTTP/2 streaming relay used by VLESS
xHTTP profiles and Cloudflare Tunnel relay profiles, with Finalmask application
and gRPC framing.
**Layer:** L7 — relay transports.

## Stable identifiers / contracts

Used when `relay_vless_transport = "xhttp"` and by `relay_kind =
"cloudflare_tunnel"` profiles. Supports the `stream-up` and `stream-one`
xHTTP protocol modes. `packet-up` and split-endpoint `stream-down` are
rejected by `XhttpProtocolMode::parse` until the crate grows a per-chunk POST
path and split download endpoint support. Carries the Finalmask modes
(`header_custom` / `fragment` / `sudoku` / `noise`) applied directly on the
outbound xHTTP transport; xHTTP path/host come from `relay_xhttp_path` /
`relay_xhttp_host`.

## Dependency direction

**Upstream:** `ripdpi-vless`, `ripdpi-tls-profiles` (`tokio`). **Downstream:**
`ripdpi-relay-core`.

## Non-root fallback

No privileged operations — runs fully on non-rooted devices. See
[`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md).

## Extension checklist

1. Add missing xHTTP protocol modes or Finalmask modes behind the existing config types.
2. Keep the transport interoperable with VLESS xHTTP and Cloudflare Tunnel
   server expectations.

---
See [`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md),
[`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md),
and [`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md).
