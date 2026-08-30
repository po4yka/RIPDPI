# Spec Version

This crate is a thin SOCKS5 ↔ HTTPS-CONNECT helper. The wire formats involved are RFC standards rather than vendored upstream wire, but the helper subprocess contract with the Android service is versioned.

- **Upstream repo:** https://github.com/klzgrad/naiveproxy
- **Upstream tag:** tracks the latest release for binary distribution
- **Upstream commit:** unverified-as-of-2026-05-15
- **Last reviewed:** 2026-05-15
- **Owner:** unassigned

## Scope

This crate implements:

- SOCKS5 (RFC 1928, RFC 1929) inbound listener on a local Unix or TCP socket
- HTTP/2 CONNECT over TLS for the upstream tunnel
- NaiveProxy Variant1 payload-padding negotiation when the upstream opts in
- `RIPDPI-READY` / `RIPDPI-ERROR` text contract with the Android service (see `docs/native/relay-naiveproxy-runtime.md`)
- `RIPDPI-PROBE` capability reporting on `--probe`

The crate is RIPDPI's repo-owned helper. It is intentionally not a Chromium-derived native naiveproxy binary.

## Drift policy

The helper schema version is the `RIPDPI-PROBE` JSON line emitted by `--probe`. Android requires schema `1` before every helper launch and rejects missing, malformed, timed-out, or unsupported probes. Schema `0` fallback is intentionally absent because the APK-bundled helper is freshly extracted for each start. RFC drift is lower risk than helper/manager contract drift.
