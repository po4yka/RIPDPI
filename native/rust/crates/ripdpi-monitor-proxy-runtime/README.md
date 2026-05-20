# ripdpi-monitor-proxy-runtime

**Role:** monitor adapter. **Layer:** L6 — diagnostics / monitor.

## Responsibility

Bridges the monitor engine to the live proxy runtime — surfaces **passive**
proxy-runtime telemetry into the diagnostics/monitor view so a running proxy
session contributes evidence without a dedicated active scan.

## Main dependencies

`ripdpi-monitor-engine`, `ripdpi-proxy-runtime`, `ripdpi-runtime-api`,
`ripdpi-diagnostics-transport`.

## Extension points

A new passive-telemetry projection from the proxy runtime into the monitor.

## What must not be added here

Active scanning logic (that is `ripdpi-monitor-engine` plus the diagnostics
crates) or proxy-runtime behavior. Keep it an observation bridge.

---
See [`DIAGNOSTICS_ARCHITECTURE.md`](../../../../docs/architecture/DIAGNOSTICS_ARCHITECTURE.md)
for the scan pipeline and
[`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md)
§3 for adding a probe.
