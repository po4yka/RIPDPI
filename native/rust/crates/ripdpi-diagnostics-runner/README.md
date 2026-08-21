# ripdpi-diagnostics-runner

**Role:** scan runner. **Layer:** L6 — diagnostics / monitor.

## Responsibility

Orchestrates a scan end to end — connectivity, strategy, and domain scan flows:
candidate planning → probe execution → classification → winner selection, under
a probe budget.

## Main dependencies

`ripdpi-diagnostics-candidates`, `ripdpi-diagnostics-classification`,
`ripdpi-diagnostics-contracts`, `ripdpi-diagnostics-dns`,
`ripdpi-diagnostics-fat-header`, `ripdpi-diagnostics-http`,
`ripdpi-diagnostics-tls`, `ripdpi-diagnostics-transport`,
`ripdpi-dns-resolver`, `ripdpi-failure-classifier`, `ripdpi-packets`,
`ripdpi-proxy-config`; `rustls`.

## Extension points

A new scan flow (a new module under `connectivity/` or `strategy/`), or
budget / winner-selection tuning.

## What must not be added here

Probe *implementations* or classification *rules* — the runner orchestrates the
other diagnostics crates; it must not re-implement them.

---
See [`DIAGNOSTICS_ARCHITECTURE.md`](../../../../docs/architecture/DIAGNOSTICS_ARCHITECTURE.md)
for the scan pipeline and
[`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md)
§3 for adding a probe.
