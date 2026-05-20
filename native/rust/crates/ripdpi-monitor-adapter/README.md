# ripdpi-monitor-adapter

**Role:** monitor adapter. **Layer:** L6 — diagnostics / monitor.

## Responsibility

Adapts diagnostics and failure-classifier output into the
`ripdpi-diagnostics-contracts` shapes the monitor engine consumes. A thin
mapping crate between the contract layer and the engine.

## Main dependencies

`ripdpi-diagnostics-contracts`, `ripdpi-failure-classifier`,
`ripdpi-proxy-config`.

## Extension points

A new contract ↔ engine mapping when the contract or engine surface grows.

## What must not be added here

Probe logic, scan orchestration, or classification rules. Keep it a thin
adapter — growth here is a sign logic leaked from another crate.

---
See [`DIAGNOSTICS_ARCHITECTURE.md`](../../../../docs/architecture/DIAGNOSTICS_ARCHITECTURE.md)
for the scan pipeline and
[`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md)
§3 for adding a probe.
