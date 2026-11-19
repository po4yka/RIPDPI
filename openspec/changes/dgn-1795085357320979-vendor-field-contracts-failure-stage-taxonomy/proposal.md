# Change: Vendor field analytics contracts and unify the failure-stage taxonomy

Task ID: `DGN-1795085357320979`

## Why

Field analytics needs one failure-stage vocabulary shared by the native crates and Kotlin. Today failures are classified by matching message text (`classifyFailureText` in `core/data/model/src/main/kotlin/com/poyka/ripdpi/data/ServiceTelemetry.kt`) and by separate probe-specific enums (`DpiProbeError`, `ProbeStage` in `core/detection`), so identical failures count under different names. `DiagnosticsArchiveExporter.kt` also discards the developer-analytics error with no log (`runCatching` around `developerAnalyticsSource.collect`).

## What Changes

- Vendor `client-field-report` v1 schema and fixtures from `ripdpi-vpn-deploy` into `core/data/src/test/resources/contract/` with a drift pin, the way `ripdpi-bundle` is vendored.
- Add one `failure_stage` enum (`FieldFailureStage`) (dns, tcp_connect, tcp_reset, tls_handshake, reality_auth, quic_handshake, proxy_auth, protocol_error, idle_timeout, throttled, unknown) in Rust (`ripdpi-diagnostics-contracts`) and Kotlin (`core/diagnostics-data`), with a total mapping from `DpiProbeError`, `ProbeStage`, and connection-path failures (the native `FailureClass` and `FailureStage` codes, mapped by one shared table).
- Replace text-matching classification with typed stage values at the source of each failure.
- Stop swallowing errors on the diagnostics export data path: log and return a typed outcome.
- BREAKING: the text-based classifier is removed; callers consume typed stages.
- External dependency: `ripdpi-vpn-deploy` D1 contracts publish the schema (deployer commit `e917cf5b`); the vendored copies are byte-identical to it.

## Capabilities

### New Capabilities

- `failure-stage-taxonomy`: the shared failure-stage enum, its mappings, and vendored contract conformance.

### Modified Capabilities

- None.

## Impact

- Kotlin: `core/diagnostics-data`, `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/dpi/DpiProbeError.kt` and `ProbeStage.kt`, `core/data/model/.../ServiceTelemetry.kt`, `core/diagnostics/.../export/DiagnosticsArchiveExporter.kt`.
- Rust: `native/rust/crates/ripdpi-diagnostics-contracts`, `ripdpi-diagnostics-classification`. Production wiring in `ripdpi-monitor-engine` belongs to R3.
- Serialized lane: vendored contract fixtures and `scripts/ci/check_cross_language_runtime_contracts.py` inputs.
- Related (not blockers): `connection-stage-scale` (DGN-1791523992124084, review) defines a presentation stage scale; this change reports a failure stage and reuses its stage evidence rather than adding a second vocabulary for UI. `correct-dia01-probe-outcomes`, `correct-dia10-diagnostic-evidence`, `correct-dia19-diagnostics`, `correct-dia20-local-detection` (review) touch the same probe outcome code; rebase on them. `svc-1786264762917506-wire-naiveproxy-probe-into-manager-startup` (review) adds the NaiveProxy probe path that the mapping must cover. `rst-1786264762917099-triage-undocumented-orphan-diagnostics-crates` (backlog) may prune crates this change edits; check before touching an orphan crate.
- Counterpart `ripdpi-vpn-deploy` changes: D1 contracts (blocker, external), D3 protocol probes (must use the same stage names), D5 block detection (consumes stages), D2 ingest and D7 e2e (validate fixtures byte for byte).
- Parent: EPC-1795085162356362.
