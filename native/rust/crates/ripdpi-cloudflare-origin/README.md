# ripdpi-cloudflare-origin

**Responsibility:** the local xHTTP origin helper for Cloudflare Tunnel publish
mode — a standalone binary that serves the local origin which a bundled
`cloudflared` sidecar fronts.
**Layer:** L7 — relay transports. **Kind:** `bin` (`src/main.rs`).

## Stable identifiers / contracts

Used by `relay_kind = "cloudflare_tunnel"` with
`relay_cloudflare_tunnel_mode = "publish_local_origin"`. Packaged into APK
assets and run as a **subprocess** (not JNI-embedded), supervised by the
`Cloudflare*` services in `:core:service`.

## Dependency direction

**Upstream:** `ripdpi-vless` (`tokio`). **Downstream:** none — it is a
standalone binary launched by the Android subprocess supervisor.

## Non-root fallback

No privileged operations — runs fully on non-rooted devices. See
[`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md).

## Extension checklist

1. Add origin-server behavior behind the existing config / HTTP-server modules.
2. Emit structured readiness / failure output so the Android supervisor can
   classify it (the `RIPDPI-READY` / `RIPDPI-ERROR` pattern).

---
See [`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md),
[`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md),
and [`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md).
