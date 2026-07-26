# ripdpi-diagnostics-pcap

**Role:** support. **Layer:** L6 — diagnostics / monitor.

## Responsibility

Standalone PCAP encoding/recording primitives for explicit diagnostic tooling.
The crate is not currently wired into the production scan pipeline.

## Main dependencies

None (standalone leaf crate; no current workspace consumer).

## Extension points

Capture-format or trace-scope support.

## What must not be added here

Probe logic or scan orchestration. Recording must respect RIPDPI's privacy
posture — no persistence of traffic payloads beyond the explicit diagnostic
capture contract, and no device identifiers.

---
See [`DIAGNOSTICS_ARCHITECTURE.md`](../../../../docs/architecture/DIAGNOSTICS_ARCHITECTURE.md)
for the scan pipeline and
[`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md)
§3 for adding a probe.
