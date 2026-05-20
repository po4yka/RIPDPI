# ripdpi-diagnostics-probes

**Role:** probe-task execution. **Layer:** L6 — diagnostics / monitor.

## Responsibility

The concrete probe tasks executed during a scan — circumvention reachability,
DNS tampering, DoH survey, ECH handshake, HTTP injection, HTTP response-block,
IP-block-suspect, MTProto reachability, QUIC probe, service reachability,
throughput, and TLS-alert probes.

## Main dependencies

`ripdpi-diagnostics-classification`, `ripdpi-diagnostics-contracts`,
`ripdpi-diagnostics-http`, `ripdpi-failure-classifier`; `hickory-resolver`,
`rustls`, `tokio`.

## Extension points

A new probe task — add a module, emit observations the classifier understands,
and register it with the runner / lane adapter.

## What must not be added here

Candidate planning (`ripdpi-diagnostics-candidates`) or scan orchestration
(`ripdpi-diagnostics-runner`).

---
See [`DIAGNOSTICS_ARCHITECTURE.md`](../../../../docs/architecture/DIAGNOSTICS_ARCHITECTURE.md)
for the scan pipeline and
[`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md)
§3 for adding a probe.
