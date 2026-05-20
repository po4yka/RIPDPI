# ripdpi-diagnostics-transport

**Role:** probe primitive. **Layer:** L6 — diagnostics / monitor.

## Responsibility

Transport-layer probe primitives shared by every per-protocol probe crate —
TCP connection establishment, TTL probing (`platform_ttl`), and the WS-TLS
transport (`ws_tls`). The lowest probe layer; it carries no protocol semantics.

## Main dependencies

`ripdpi-diagnostics-contracts`, `ripdpi-socks5-core`; `rustls`, `tokio`.

## Extension points

A new transport-level primitive (a new connect/measurement helper) that
multiple per-protocol probes can reuse.

## What must not be added here

Protocol-specific probe logic (HTTP, TLS handshake, DNS) — that belongs in the
per-protocol crates. This crate must not depend on `ripdpi-diagnostics-{tls,
http,dns,...}`.

---
See [`DIAGNOSTICS_ARCHITECTURE.md`](../../../../docs/architecture/DIAGNOSTICS_ARCHITECTURE.md)
for the scan pipeline and
[`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md)
§3 for adding a probe.
