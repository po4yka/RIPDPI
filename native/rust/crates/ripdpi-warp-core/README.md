# ripdpi-warp-core

**Responsibility:** the native WARP runtime — WireGuard plus the AmneziaWG
handshake-obfuscation codec, a userspace virtual interface, endpoint probing,
the WireGuard-over-WebSocket carrier, and the SOCKS front. The same runtime is
adapted for the separate AmneziaWG Android surface.
**Layer:** L7 — relay transports.

WARP is its own feature, gated by the `warp_*` settings (`warp_enabled`,
`warp_amnezia_*`, endpoint selection), **not** by `relay_kind`. AmneziaWG is
WireGuard with handshake obfuscation for high-censorship networks.

## Stable identifiers / contracts

The `warp_*` settings family; the WireGuard / AmneziaWG wire format (a fixed
protocol contract); the `warp_amnezia_preset` values
(`off` / `balanced` / `aggressive` / `custom`).

## Dependency direction

**Upstream:** `ripdpi-wireguard-ws` plus `smoltcp` and `tokio`. **Downstream:**
`ripdpi-warp-android` → `libripdpi-warp.so`, and `ripdpi-amneziawg-android`.

## Non-root fallback

No privileged operations — the userspace `smoltcp` virtual interface runs fully
on non-rooted devices. See
[`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md).

## Extension checklist

1. Add WARP runtime / endpoint-probing features behind the existing config.
2. Never change the WireGuard / AmneziaWG wire format — it is an interop contract.

---
See [`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md),
[`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md),
and [`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md).
