# ripdpi-diagnostics-tls

**Role:** per-protocol probe. **Layer:** L6 — diagnostics / monitor.

## Responsibility

TLS-layer diagnostic probes: TLS-handshake reachability and JA3
fingerprinting, and the ECH handshake spike. Produces TLS observations the
classifier consumes.

## Main dependencies

`ripdpi-diagnostics-contracts`, `ripdpi-diagnostics-dns`,
`ripdpi-diagnostics-transport`, `ripdpi-tls-profiles`; `rustls`.

## Extension points

A new TLS probe variant or fingerprint — add it alongside `tls/` / `ja3/` and
emit a TLS observation.

## What must not be added here

HTTP or DNS probe logic, scan orchestration, or classification rules. Build on
`ripdpi-diagnostics-transport`; do not reach into the runner.

---
See [`DIAGNOSTICS_ARCHITECTURE.md`](../../../../docs/architecture/DIAGNOSTICS_ARCHITECTURE.md)
for the scan pipeline and
[`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md)
§3 for adding a probe.
