# ripdpi-vless

**Responsibility:** the VLESS protocol transport — Reality TLS, the Vision flow,
the xHTTP addon hook, and the VLESS mux carrier.
**Layer:** L7 — relay transports.

Implements VLESS with Reality (`reality.rs` / `reality_hook.rs` /
`reality_seal.rs`), the Vision flow-control addon, and the wire format. VLESS mux uses the SagerNet sing-mux carrier with the yamux inner protocol; unsupported inner protocols fail during configuration resolution.

## Stable identifiers / contracts

Selected by `relay_kind = "vless_reality"` (`relay_vless_transport` chooses
`reality_tcp` vs `xhttp`). The VLESS wire format and the Reality handshake are
fixed protocol contracts. The Reality path declares six BoringSSL symbols by
hand — `boring` / `tokio-boring` are pinned to exact versions in the workspace
`Cargo.toml`; do not let a transitive update move them.

## Dependency direction

**Upstream:** `ripdpi-relay-mux`, `ripdpi-tls-profiles`. **Downstream:**
`ripdpi-relay-core`, `ripdpi-xhttp`, `ripdpi-cloudflare-origin`.

## Non-root fallback

No privileged operations — runs fully on non-rooted devices. See
[`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md).

## Extension checklist

1. Add VLESS addons / flow variants behind the existing wire types.
2. Never change the VLESS wire format or Reality handshake without a contract
   review — it must interoperate with external servers.

---
See [`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md),
[`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md),
and [`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md).
