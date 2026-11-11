# ripdpi-xhttp

**Responsibility:** the xHTTP transport — HTTP/2 streaming relay used by VLESS
xHTTP profiles and Cloudflare Tunnel relay profiles, with Finalmask application
and a separate gRPC framing codec. The xHTTP session driver carries raw VLESS
bytes; its `application/grpc` header does not add gRPC message frames.
**Layer:** L7 — relay transports.

## Stable identifiers / contracts

Used when `relay_vless_transport = "xhttp"` and by `relay_kind =
"cloudflare_tunnel"` profiles. Supports the `stream-up` and `stream-one`
xHTTP protocol modes. Automatic mode (including an empty mode string) resolves
to `stream-one` with REALITY and is rejected with TLS, where upstream selects
unimplemented `packet-up`. Callers can explicitly select either supported mode.
`packet-up` is not implemented. `stream-down` names the GET download leg; it
is not a selectable upstream mode. Separate `downloadSettings` endpoints are
not supported. Unknown mode selectors are rejected. Carries the Finalmask modes
(`header_custom` / `fragment` / `sudoku` / `noise`) applied directly on the
outbound xHTTP transport; xHTTP path/host come from `relay_xhttp_path` /
`relay_xhttp_host`.

The native profile exposes path, Host override and a bounded connection pool.
It does not expose arbitrary request headers, metadata placement or upstream
request-count, reuse-count and connection-age controls.

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
