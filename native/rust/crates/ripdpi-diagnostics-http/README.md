# ripdpi-diagnostics-http

**Role:** per-protocol probe. **Layer:** L6 — diagnostics / monitor.

## Responsibility

HTTP-layer diagnostic probes: HTTP reachability, blockpage-fingerprint matching
(`blockpage_fingerprints`), and HTTP injection probing. Produces HTTP
observations the classifier consumes.

## Main dependencies

`ripdpi-diagnostics-contracts`, `ripdpi-diagnostics-tls`,
`ripdpi-diagnostics-transport`, `ripdpi-failure-classifier`.

## Extension points

A new HTTP probe, or a new blockpage fingerprint entry.

## What must not be added here

TLS-handshake or DNS internals, scan orchestration, or verdict classification.
Build on `ripdpi-diagnostics-transport` / `-tls`; do not depend on the runner.

---
See [`DIAGNOSTICS_ARCHITECTURE.md`](../../../../docs/architecture/DIAGNOSTICS_ARCHITECTURE.md)
for the scan pipeline and
[`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md)
§3 for adding a probe.
