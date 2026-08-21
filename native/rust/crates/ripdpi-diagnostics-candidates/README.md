# ripdpi-diagnostics-candidates

**Role:** candidate planning. **Layer:** L6 — diagnostics / monitor.

## Responsibility

Plans the strategy-probe **candidates** — the ordered set of strategy
configurations a strategy probe tests. `quick_v1` combines the current TCP and
QUIC builders; `full_matrix_v1` adds lab/audit-only TCP and QUIC variants.
Some candidates are included only when platform capability probes allow them
(for example TCP Fast Open or IP fragmentation). Enumeration and planning only.

## Main dependencies

`ripdpi-diagnostics-contracts`, `ripdpi-diagnostics-dns`, `ripdpi-config`,
`ripdpi-proxy-config`, `ripdpi-dns-resolver`, `ripdpi-failure-classifier`,
`ripdpi-runtime-platform`.

## Extension points

Add a strategy-probe candidate, or change candidate ordering / qualifier logic.
Keep the `AGENTS.md` § Strategy Probe Candidates families in sync.

## What must not be added here

Probe *execution* (that is `ripdpi-diagnostics-probes`) or verdict
classification (that is `ripdpi-diagnostics-classification`).

The dependency set above is closed for this crate's role: it materializes
strategy configurations, so `ripdpi-config` / `ripdpi-proxy-config` types are
its working vocabulary, and DNS-context, platform-capability, and
failure-classification inputs are inherent to candidate qualification. New
dependency families (relay runtimes, telemetry sinks, UI state) do not belong
here; bring the need to the runner instead.

---
See [`DIAGNOSTICS_ARCHITECTURE.md`](../../../../docs/architecture/DIAGNOSTICS_ARCHITECTURE.md)
for the scan pipeline and
[`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md)
§3 for adding a probe.
