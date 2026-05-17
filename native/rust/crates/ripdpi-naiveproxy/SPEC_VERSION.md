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
- HTTPS CONNECT (RFC 7231) upstream tunnel
- `RIPDPI-READY` / `RIPDPI-ERROR` text contract with the Android service (see `docs/native/relay-naiveproxy-runtime.md`)

The naiveproxy *binary* itself is distributed by klzgrad/naiveproxy and runs as a managed subprocess. This crate is the in-process helper that front-ends it.

## Drift policy

The helper schema version (planned: `RIPDPI-PROBE` JSON line in `docs/tasks/issues/make-naiveproxy-helper-probe-return-structured-version-json.md`) is the contract that needs explicit versioning. RFC drift is not a concern.
