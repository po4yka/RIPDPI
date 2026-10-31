# Spec Version

This crate is a thin SOCKS5 ↔ HTTPS-CONNECT helper. The wire formats involved are RFC standards rather than vendored upstream wire, but the helper subprocess contract with the Android service is versioned.

The APK ships only this repository-owned Rust helper (`ripdpi-naiveproxy`, packaged as a jniLib). It ships no klzgrad/naiveproxy or Chromium binary. The pins below are the sources this helper's wire behavior was checked against, not distributed artifacts.

- **Upstream repo:** https://github.com/klzgrad/naiveproxy (protocol reference: padding framer, padding socket, proxy delegate, HTTP proxy server socket)
- **Upstream tag:** v154.0.8037.49-5
- **Upstream commit:** d04efe1ea4477c2db24a7d0e2f068b3bee931e45
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
- A resolve-only mode, `--resolve <HOST> --server-port <N>` (below)
- A server check mode, `--check` (below)

## Resolve-only mode

`ripdpi-naiveproxy --resolve <HOST> --server-port <N>` resolves the proxy hostname and exits. The upstream Chromium client uses it, through the Android service, so that the name goes through RIPDPI's protected DoH.

- Arguments: exactly `--resolve` and `--server-port`. Any other argument, including every relay-mode flag, is an error. The mode takes no credentials, reads no stdin, binds no listener and prints no `RIPDPI-READY`.
- Protect path: `RIPDPI_PROTECT_PATH`, as in relay mode. With it, the lookup is protected DoH. Without it, the lookup is the same DoH pipeline with no protect hooks. The system resolver is never used.
- Success: one stdout line `RIPDPI-RESOLVED|<addr>[,<addr>...]` and exit 0. Each `<addr>` is an IP literal with no port, in resolver order, without duplicates. IPv6 has no brackets. An IP-literal `<HOST>` is echoed without a lookup.
- Failure: one stderr line `RIPDPI-ERROR|naiveproxy|dns|<message>` and exit 1. This covers lookup failures, empty results and invalid arguments. The class is always `dns`.

## Server check mode

`ripdpi-naiveproxy --check <relay-mode arguments>` checks a profile from the editor and exits. It takes the relay-mode arguments and credential stdin, binds no listener and prints no `RIPDPI-READY`.

- It opens one CONNECT to `ripdpi-check.invalid:443` (RFC 6761 reserves `.invalid`) through the server, so only the server gets traffic. The limit is 15 s.
- Result: one stdout line `RIPDPI-CHECK|naiveproxy|<class>` and exit 0. `<class>` is `ok` when the server answers the CONNECT without a 407, `timeout`, or a `RIPDPI-ERROR` class such as `auth`, `tls`, `dns` or `connect`. A server with probe resistance answers a wrong password like a web site, which also gives `ok`.
- Invalid arguments: one stderr `RIPDPI-ERROR` line and a non-zero exit, as in relay mode.

The crate is RIPDPI's repo-owned helper. It is intentionally not a Chromium-derived native naiveproxy binary.

## Drift policy

The helper schema version is the `RIPDPI-PROBE` JSON line emitted by `--probe`. Android requires schema `1` before every helper launch and rejects missing, malformed, timed-out, or unsupported probes. Schema `0` fallback is intentionally absent because the APK-bundled helper is freshly extracted for each start. RFC drift is lower risk than helper/manager contract drift.
