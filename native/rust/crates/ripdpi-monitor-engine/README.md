# ripdpi-monitor-engine

**Role:** monitor engine (runtime). **Layer:** L6 — diagnostics / monitor.

## Responsibility

The active-scan engine. Owns the scan **session** lifecycle, the execution
loop, platform integration, and the engine wire surface; produces structured
scan reports and scan-time passive events. This is the crate the old
monolithic `ripdpi-monitor` became — it is reached over JNI through
`ripdpi-android-diagnostics-adapter` (`NetworkDiagnostics.kt`).

## Main dependencies

`ripdpi-diagnostics-contracts`, `ripdpi-monitor-adapter`,
`ripdpi-monitor-lane-adapter`, `ripdpi-config`, `ripdpi-packets`,
`ripdpi-runtime-platform`, `ripdpi-telemetry`; `rustls`, `tokio`.

## Extension points

A new session capability, execution-loop behavior, or report field. Wire-shape
changes require a `DIAGNOSTICS_ENGINE_SCHEMA_VERSION` bump and a golden re-bless
(`tests/contract_fixtures.rs`).

## What must not be added here

Per-probe logic — probes live in the `ripdpi-diagnostics-*` crates and reach
the engine through `ripdpi-monitor-lane-adapter`. No JNI (that is L8).

---
See [`DIAGNOSTICS_ARCHITECTURE.md`](../../../../docs/architecture/DIAGNOSTICS_ARCHITECTURE.md)
for the scan pipeline and
[`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md)
§3 for adding a probe.
