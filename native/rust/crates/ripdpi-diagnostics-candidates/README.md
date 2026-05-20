# ripdpi-diagnostics-candidates

**Role:** candidate planning. **Layer:** L6 — diagnostics / monitor.

## Responsibility

Plans the strategy-probe **candidates** — the ordered set of strategy
configurations a strategy probe tests (the 24 TCP + 6 QUIC candidate matrix,
ordered modern-first for censored networks). Enumeration and planning only.

## Main dependencies

`ripdpi-diagnostics-contracts`, `ripdpi-diagnostics-dns`, `ripdpi-config`,
`ripdpi-proxy-config`, `ripdpi-dns-resolver`, `ripdpi-failure-classifier`,
`ripdpi-runtime-platform`.

## Extension points

Add a strategy-probe candidate, or change candidate ordering / qualifier logic.
Keep the `AGENTS.md` § Strategy Probe Candidates table in sync.

## What must not be added here

Probe *execution* (that is `ripdpi-diagnostics-probes`) or verdict
classification (that is `ripdpi-diagnostics-classification`).

---
See [`DIAGNOSTICS_ARCHITECTURE.md`](../../../../docs/architecture/DIAGNOSTICS_ARCHITECTURE.md)
for the scan pipeline and
[`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md)
§3 for adding a probe.
