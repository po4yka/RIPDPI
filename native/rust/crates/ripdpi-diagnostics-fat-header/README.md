# ripdpi-diagnostics-fat-header

**Role:** per-protocol probe. **Layer:** L6 — diagnostics / monitor.

## Responsibility

TCP fat-header diagnostic probes — sends oversized first-flight headers to
detect middlebox parsing behavior and produces the corresponding observations.

## Main dependencies

`ripdpi-diagnostics-contracts`, `ripdpi-diagnostics-http`,
`ripdpi-diagnostics-tls`, `ripdpi-diagnostics-transport`.

## Extension points

A new fat-header variant or measurement.

## What must not be added here

Unrelated protocol probe logic, scan orchestration, or classification rules.

---
See [`DIAGNOSTICS_ARCHITECTURE.md`](../../../../docs/architecture/DIAGNOSTICS_ARCHITECTURE.md)
for the scan pipeline and
[`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md)
§3 for adding a probe.
