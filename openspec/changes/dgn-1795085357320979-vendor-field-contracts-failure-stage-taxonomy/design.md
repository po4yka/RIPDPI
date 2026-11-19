## Context

Failure classification currently lives in `classifyFailureText` in `core/data/model/src/main/kotlin/com/poyka/ripdpi/data/ServiceTelemetry.kt` (text match), `DpiProbeError.kt` and `ProbeStage.kt` under `core/detection`, and Rust classification in `native/rust/crates/ripdpi-diagnostics-classification`. The bundle contract is already vendored under `core/data/src/test/resources/contract/` and pinned by `core/data/src/test/kotlin/com/poyka/ripdpi/data/RipdpiBundleContractTest.kt`.

## Goals / Non-Goals

- Goal: a single typed `failure_stage`, no text matching, no silent loss on the export path.
- Non-goal: changing user-visible stage presentation (owned by `connection-stage-scale`).
- Non-goal: new probes (deploy D3).

## Decisions

- The enum is `FieldFailureStage` in `ripdpi-diagnostics-contracts` (Rust, `types/field_failure_stage.rs`, with `CLIENT_FIELD_REPORT_CONTRACT_VERSION`) and in `core/diagnostics-data` (Kotlin, `FieldFailureStage.kt`, with `ClientFieldReportContractVersion`). The name differs from the internal `ripdpi_failure_classifier::FailureStage` (classification stage), which stays as it is. Both enums are checked against the one vendored schema by `FieldFailureStageContractTest` (Kotlin) and the `field_failure_stage` tests (Rust, which read the vendored directory through the repository root), and the pair is registered in `quality/release-gates/cross-language-runtime-contracts.json`, which `scripts/ci/check_cross_language_runtime_contracts.py` validates. Rejected: Kotlin-only enum, which lets the languages drift.
- Rust connection-path mapping (`ripdpi-diagnostics-classification::field_failure_stage(&ClassifiedFailure)`): DnsTampering, DnsResolutionFailure -> dns; ConnectFailure, IpBlockSuspect -> tcp_connect; TcpReset -> tcp_reset; TlsAlert, TlsHandshakeFailure -> tls_handshake; QuicBreakage -> quic_handshake; HttpBlockpage, Redirect, TuicVersionUnsupported, ShadowTlsVersionMismatch, MasqueH3TcpUnsupported -> protocol_error; ConnectionFreeze, ConfirmGoodDpiSuspected -> idle_timeout; SilentDrop -> the stage where traffic stopped (Dns -> dns, Connect -> tcp_connect, TlsHandshake -> tls_handshake, QuicProbe -> quic_handshake, FirstWrite/FirstResponse/HttpResponse/PostHandshake/Relay -> idle_timeout, Diagnostic -> unknown); Unknown, StrategyExecutionFailure, CapabilitySkipped -> unknown.
- Kotlin probe mapping (`core/detection`, `DpiFieldFailureStage.kt`): SynDrop, Refused, NetUnreach, HostUnreach -> tcp_connect; TcpRst, TlsRst, TlsRstTls, TcpAbort -> tcp_reset; TlsSpoof, TlsAlertSni, TlsAlertHandshake, TlsBlockVersion, TlsEof, TlsDrop, TlsMitm -> tls_handshake; DnsFail -> dns; Unknown -> unknown. ProbeStage: TCP_CONNECT -> tcp_connect, TLS_HANDSHAKE -> tls_handshake, SENDING_DATA and READING_DATA -> unknown; `fieldFailureStage(error, stage)` uses the stage only when the error is Unknown. Exhaustive `when` expressions make the mapping total at compile time.
- Kotlin connection-path mapping (`FailureClass.fieldFailureStage`): DnsInterference -> dns; TlsInterference -> tls_handshake; Timeout -> tcp_connect; ResetAbort -> tcp_reset; Compatibility -> protocol_error (covers the NaiveProxy probe path); TunnelEstablish, WarpProvisioning, WarpEndpoint, FingerprintPolicy, NetworkHandover, NativeIo, Unexpected -> unknown.
- No current source emits reality_auth, proxy_auth, or throttled; they are defined for the deploy D3 protocol probes and the R3 report client.
- Typed classification instead of text matching: `classifyFailureText` and the native-event/lastError text scan are removed. `classifyFailureClass` uses the `FailureReason` variant, the exact typed code in `NativeRuntimeSnapshot.lastFailureClass` (decoded by `failureClassFromNativeCode`: the Rust `FailureClass::as_str` values plus the Kotlin prelaunch codes `relay_compatibility` and `dns`), and `networkHandoverClass`. `FailureReason.RelayConfigRejected` carries a typed `failureClass` set at the rejection site; `NaiveProxyCompatibilityException` derives it from its prelaunch failure code.
- Scope of the text rule: `DpiErrorClassifier` stays. It is the source boundary that turns a JVM exception (whose errno is only available as text on Android) into a typed `DpiProbeError`; the failure stage is derived from that typed value, never from text.
- Typed outcome on export: `DeveloperAnalyticsPayload.collectionStatus` (`COLLECTED` or `FAILED`, allow-listed top-level key) replaces the note string, and the failure is logged with Kermit. Rejected: keeping the note string, which cannot be counted.
- Audit of the other `runCatching` uses in `DiagnosticsArchiveExporter.kt`: `archiveStage` rethrows cancellation and errors and wraps the rest in a typed `DiagnosticsArchiveException`; rollback and cache-reconcile uses attach the failure as suppressed and rethrow it. None of them swallows an error, so only the developer-analytics collection changes.
- The canonical schema is published by deploy D1 at `contract/client-field-report.schema.json` (deployer commit `e917cf5b`) and vendored byte-identical.

## Contracts and ownership

- Serialized files: contract-version constants, vendored fixtures under `core/data/src/test/resources/contract/`, `quality/release-gates/cross-language-runtime-contracts.json`, and the Rust API snapshots of the two touched crates.
- Kotlin: `core/diagnostics-data`, `core/detection`, `core/data/model`, `core/diagnostics`, and the NaiveProxy rejection site in `core/service`.

## Risks / Trade-offs

- Mapping loses detail from probe-specific errors -> originals stay in local diagnostics; only the report uses the stage.
- Review-stage dia changes conflict -> rebase and rerun affected gates.

## Migration Plan

BREAKING: remove text classification and update every call site. No persisted data changes. Rollback is reverting the change. The vendored files are the final D1 contract.
