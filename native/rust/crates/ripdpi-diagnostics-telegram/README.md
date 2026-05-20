# ripdpi-diagnostics-telegram

**Role:** per-protocol probe. **Layer:** L6 — diagnostics / monitor.

## Responsibility

Telegram-specific diagnostics — data-center reachability and availability
probes for Telegram traffic, producing Telegram observations the classifier
consumes.

## Main dependencies

`ripdpi-diagnostics-contracts`, `ripdpi-diagnostics-http`,
`ripdpi-diagnostics-tls`, `ripdpi-diagnostics-transport`.

## Extension points

A new Telegram reachability or throughput probe variant.

## What must not be added here

Generic (non-Telegram) probe logic, the MTProto tunnel itself
(`ripdpi-ws-tunnel`), scan orchestration, or classification.

---
See [`DIAGNOSTICS_ARCHITECTURE.md`](../../../../docs/architecture/DIAGNOSTICS_ARCHITECTURE.md)
for the scan pipeline and
[`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md)
§3 for adding a probe.
