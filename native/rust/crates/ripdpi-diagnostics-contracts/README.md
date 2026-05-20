# ripdpi-diagnostics-contracts

**Role:** contract. **Layer:** L2 — contracts / config.

## Responsibility

The diagnostics wire contract. Defines the `ScanRequest` / `ScanReport` /
progress types exchanged between the native diagnostics engine and the Kotlin
side, the `DIAGNOSTICS_ENGINE_SCHEMA_VERSION` constant, and shared
`types` / `util` / `wire` helpers. Every other diagnostics crate depends on it.

## Main dependencies

`ripdpi-proxy-config`, `ripdpi-telemetry`; `serde`. No probe or runtime deps.

## Extension points

New wire fields — must be `#[serde(default)]`; bump
`DIAGNOSTICS_ENGINE_SCHEMA_VERSION` on any breaking shape change and update the
contract goldens.

## What must not be added here

Probe logic, network I/O, or dependencies on any probe / runner / monitor
crate. This is a leaf contract crate — keep it that way.

---
See [`DIAGNOSTICS_ARCHITECTURE.md`](../../../../docs/architecture/DIAGNOSTICS_ARCHITECTURE.md)
for the scan pipeline and
[`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md)
§3 for adding a probe.
