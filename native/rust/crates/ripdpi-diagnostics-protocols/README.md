# ripdpi-diagnostics-protocols

**Role:** protocol-probe aggregation facade. **Layer:** L6 — diagnostics / monitor.

## Responsibility

Aggregates the per-protocol probe crates (`-dns`, `-tls`, `-http`,
`-fat-header`, `-telegram`, `-transport`) into one protocol-probe surface that
the scan runner consumes, plus the protocol `version_probe` and a `compat`
layer.

## Main dependencies

`ripdpi-diagnostics-contracts` and the per-protocol probe crates
(`ripdpi-diagnostics-{dns,fat-header,http,telegram,tls,transport}`).

## Extension points

Wire a newly added per-protocol probe crate into the aggregated surface.

## What must not be added here

Probe *implementation* — this crate aggregates; the actual probing belongs in
the per-protocol crates. No scan orchestration (that is the runner).

---
See [`DIAGNOSTICS_ARCHITECTURE.md`](../../../../docs/architecture/DIAGNOSTICS_ARCHITECTURE.md)
for the scan pipeline and
[`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md)
§3 for adding a probe.
