# Spec Version

This crate is a thin SOCKS5 ↔ HTTPS-CONNECT helper. The wire formats involved are RFC standards rather than vendored upstream wire, but the helper subprocess contract with the Android service is versioned.

The APK ships only this repository-owned Rust helper (`ripdpi-naiveproxy`, packaged as a jniLib). It ships no klzgrad/naiveproxy or Chromium binary. The pins below are the sources this helper's wire behavior was checked against, not distributed artifacts.

- **Protocol reference:** https://github.com/klzgrad/naiveproxy, tag `v154.0.8037.49-5`, commit `d04efe1ea4477c2db24a7d0e2f068b3bee931e45` (padding framer, padding socket, proxy delegate, HTTP proxy server socket)
- **Server interop reference:** https://github.com/klzgrad/forwardproxy, tag `v2.11.7-naive2`, commit `e747854e78743dba85c039b38ed185fb11d93667`, built on Caddy `v2.11.7` (`72dd0fb067f6d7826c7f79907670ba4a713bfe37`) with the fork's `caddy-sing-quic-bbrv1.patch` and `caddy-read-idle-timeout.patch`. The `independent-naiveproxy-*` acceptance scenarios build and run exactly this server (`test-lab/acceptance/peers/naiveproxy.py`).
- **Last reviewed:** 2026-10-30
- **Owner:** RIPDPI native transport maintainers

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
