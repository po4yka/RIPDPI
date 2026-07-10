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

The stable helper CLI is `ripdpi-cloudflare-origin --config-stdin`. The Android supervisor writes a bounded binary startup frame to standard input and closes the pipe before reading readiness output. The frame is `RIPDPI-CF-ORIGIN` (16-byte ASCII magic), schema byte `1`, then listener authority, xHTTP path, and VLESS UUID as three unsigned 16-bit big-endian length-prefixed UTF-8 fields. Total input is capped at 16 KiB; malformed, oversized, unsupported-schema, truncated, or trailing input fails closed without echoing field values. Listener, path, and UUID arguments are intentionally rejected so identity material and private paths do not appear in process argv. `--version` is accepted only as the sole argument.

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
3. Coordinate any startup-frame schema change with `encodeCloudflareOriginStartupConfig` in `:core:service` and add fixtures on both sides. Do not reuse helper argv for configuration.

---
See [`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md),
[`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md),
and [`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md).
