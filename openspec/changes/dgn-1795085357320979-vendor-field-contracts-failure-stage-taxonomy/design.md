## Context

Failure classification currently lives in `classifyFailureText` in `core/data/model/src/main/kotlin/com/poyka/ripdpi/data/ServiceTelemetry.kt` (text match), `DpiProbeError.kt` and `ProbeStage.kt` under `core/detection`, and Rust classification in `native/rust/crates/ripdpi-diagnostics-classification`. The bundle contract is already vendored under `core/data/src/test/resources/contract/` and pinned by `core/data/src/test/kotlin/com/poyka/ripdpi/data/RipdpiBundleContractTest.kt`.

## Goals / Non-Goals

- Goal: a single typed `failure_stage`, no text matching, no silent loss on the export path.
- Non-goal: changing user-visible stage presentation (owned by `connection-stage-scale`).
- Non-goal: new probes (deploy D3).

## Decisions

- The enum lives in `ripdpi-diagnostics-contracts` (Rust, source of wire values) and `core/diagnostics-data` (Kotlin), checked against the vendored schema by one test per language plus `scripts/ci/check_cross_language_runtime_contracts.py`. Rejected: Kotlin-only enum, which lets the languages drift.
- Mapping table (starting point, finalised in implementation): SynDrop -> tcp_connect; TcpRst, TlsRst, TlsRstTls -> tcp_reset; TlsAlertSni, TlsAlertHandshake, TlsBlockVersion, TlsEof, TlsDrop, TlsSpoof, TlsMitm -> tls_handshake; QUIC probe failures -> quic_handshake; DNS probe failures -> dns; proxy auth failures -> proxy_auth; Reality verification failures -> reality_auth; idle and read timeouts after connect -> idle_timeout; rate-limited transfers -> throttled; everything unmapped -> unknown.
- Typed outcome on export: a sealed result with a failure variant rather than a note string. Rejected: keeping the note string, which cannot be counted.
- Assumption: D1 publishes the schema at `contract/client-field-report.schema.json` in `ripdpi-vpn-deploy`; local fixtures mirror the shared contract in the task brief until then.
- Assumption: the other `runCatching` uses in `DiagnosticsArchiveExporter.kt` (8 in total) are audited; only those on the data path are changed.

## Contracts and ownership

- Serialized files: `*schema-version constants`, vendored fixtures under `core/data/src/test/resources/contract/`, Rust `wire.rs` in `ripdpi-diagnostics-contracts`.
- Kotlin: `core/diagnostics-data`, `core/detection`, `core/data/model`, `core/diagnostics`.

## Risks / Trade-offs

- Mapping loses detail from probe-specific errors -> originals stay in local diagnostics; only the report uses the stage.
- Review-stage dia changes conflict -> rebase and rerun affected gates.

## Migration Plan

BREAKING: remove text classification and update every call site. No persisted data changes. Rollback is reverting the change. Final vendoring requires deploy D1; before that the fixtures are marked provisional in `status_detail`.
