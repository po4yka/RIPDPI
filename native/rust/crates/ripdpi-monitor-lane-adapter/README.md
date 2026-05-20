# ripdpi-monitor-lane-adapter

**Role:** monitor adapter. **Layer:** L6 — diagnostics / monitor.

## Responsibility

Adapts the diagnostics probe crates and the scan runner into the parallel
probe **lanes** (the TCP lane and the QUIC lane) that `ripdpi-monitor-engine`
executes. It is the wiring seam between the diagnostics family and the engine.

## Main dependencies

`ripdpi-diagnostics-runner`, `ripdpi-diagnostics-candidates`,
`ripdpi-diagnostics-classification`, and the per-protocol probe crates
(`ripdpi-diagnostics-{dns,http,telegram,tls,transport}`).

## Extension points

Wire a new probe crate or a new lane into the engine.

## What must not be added here

Probe *implementations* — this crate only adapts and routes. Keep it
wiring-only; the probe logic stays in the `ripdpi-diagnostics-*` crates.

---
See [`DIAGNOSTICS_ARCHITECTURE.md`](../../../../docs/architecture/DIAGNOSTICS_ARCHITECTURE.md)
for the scan pipeline and
[`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md)
§3 for adding a probe.
