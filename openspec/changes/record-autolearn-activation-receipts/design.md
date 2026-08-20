## Context

`ProxyRuntimeSupervisor.start()` waits for native readiness and polls `NativeRuntimeSnapshot` to resolve the dynamic listener address. It currently returns only `LocalProxyEndpoint`, discarding the snapshot. `ServiceRuntimeStartStopOrchestrator` publishes `Connected` before starting the periodic telemetry loop, so the first diagnostic context can observe the `ServiceStateStore` idle/`NoData` default.

The service already owns a stable `ServiceRuntimeSession.runtimeId`, policy signature/fingerprint correlators, serialized lifecycle execution, and a Room-backed `DiagnosticsArtifactWriteStore`. `NativeSessionEventEntity` already supplies generic `stage`, `outcome`, `attemptSequence`, runtime, mode, policy, and fingerprint fields and is included in archives with existing redaction. These contracts are sufficient without changing JNI, native telemetry, protobuf, Room, or archive schemas.

## Goals / Non-Goals

- Goal: Carry the exact ready-time native proxy snapshot through proxy and VPN composition to service telemetry before `Connected`.
- Goal: Persist one privacy-safe Autolearn activation receipt for initial startup and each successful proxy-runtime replacement.
- Goal: Make persisted, resolved/requested, and native-effective enabled states independently inspectable and correlated to one runtime generation.
- Goal: Keep networking available if diagnostics persistence fails while surfacing that failure as a warning.
- Non-goal: Add native telemetry fields, change Autolearn policy behavior, hot-reload settings, or remove probe-candidate Autolearn sanitization.
- Non-goal: Add host-level correlations or expose raw network, path, command-line, or store-location values.
- Non-goal: Add a new archive entry or schema version; the receipt remains an event in the existing envelope.
- Non-goal: Attribute Autolearn state to alternate provider-owned VPN paths that do not create a RIPDPI proxy runtime.

## Decisions

- Introduce a typed `ProxyRuntimeStartResult` containing `LocalProxyEndpoint` and the exact `NativeRuntimeSnapshot` used to resolve it. `ProxyRuntimeSupervisor.start()` performs only the existing single authoritative poll; it does not add another native call.
- Propagate the typed start result through `SharedProxyRuntimeStack` and the RIPDPI branch of VPN runtime composition. Mode-specific `startResolvedRuntime` callbacks return a `RuntimeStartEvidence` value instead of discarding startup evidence; alternate provider-owned branches return an explicit not-applicable result and emit no Autolearn receipt.
- Add a generic lifecycle callback that publishes runtime-start evidence after runtime startup succeeds and before session registration and `Connected`. Mode-specific telemetry coordinators construct a `ServiceTelemetrySnapshot` with proxy status `snapshot` and explicit `NoData` statuses for components not yet sampled.
- Define `AutolearnActivationReceipt` as a Kotlin service-domain type. It carries persisted enabled, resolved/requested enabled, native-effective enabled, `AutolearnResolutionSource`, `AutolearnActivationOutcome`, runtime ID, mode, policy/fingerprint correlators, generation, and captured-at time.
- Derive resolution source deterministically: `command_line` when command-line settings mode is enabled, otherwise `remembered_policy` when an exact remembered policy participated, otherwise `baseline_settings`.
- Classify outcome as `mismatch` when resolved/requested and native-effective values differ; otherwise classify from the effective state as `active` or `disabled`. A difference between persisted and resolved state remains visible but is not automatically an error because command-line or remembered resolution can intentionally override persisted input.
- Persist receipts through a dedicated `AutolearnActivationRecorder` backed by `DiagnosticsArtifactWriteStore`. Map the typed receipt into the existing event envelope with `source=app`, `subsystem=autolearn_activation`, `stage=runtime_ready`, the coarse outcome field, runtime/mode/policy/fingerprint fields, and `attemptSequence=generation`.
- Encode only the three boolean states and resolution-source token in the canonical event message. The message contains no host, store path, raw scope, command-line text, or arbitrary error string. Existing redaction handles the correlator fields.
- Add a monotonically increasing activation generation to `ServiceRuntimeSession`. Lifecycle operations are already serialized, so a mutable counter is sufficient. The stable event ID is derived from runtime ID plus generation, preventing a handover replacement from overwriting the initial receipt.
- Await the receipt write before publishing `Connected`, but catch non-cancellation storage failures, emit a structured warning, and continue startup. Cancellation remains propagated. Diagnostics storage is not allowed to become a network availability dependency.
- Route initial startup, proxy handover replacement, VPN handover replacement, and any transport-failover path that creates a new proxy runtime through the same evidence publisher. Bootstrap-only proxy supervisors adapt to the typed return but do not emit service activation receipts.

## Contracts and ownership

- `core/service` owns `ProxyRuntimeStartResult`, `RuntimeStartEvidence`, resolution-source/outcome types, generation assignment, ordering, telemetry publication, and the recorder implementation.
- `core/engine-api` and `core/engine` remain unchanged: `RipDpiProxyRuntime.pollTelemetry()` already returns the authoritative snapshot.
- `core/data:model` and `core:data:runtime-state` retain their current telemetry contracts. The published snapshot uses existing `RuntimeTelemetryStatus(Snapshot)` semantics.
- `core/diagnostics-data` retains the current Room entity and database version. The recorder consumes the existing `DiagnosticsArtifactWriteStore` and `NativeSessionEventEntity` fields without adding columns.
- `core/diagnostics` retains the current archive schema. Existing native-session event packaging and redaction carry the new event kind; tests own retention and privacy assertions.
- No Rust crates, JNI models, protobuf files, generated files, locale resources, golden fixtures, or serialized shared-file schemas are changed.

## Risks / Trade-offs

- Startup-order regressions could expose `Connected` too late or duplicate telemetry publication → keep the existing readiness boundary, publish one snapshot immediately before `Connected`, and cover callback order with lifecycle tests.
- A handover can reuse the service runtime ID → include a session-local monotonic generation in the event ID and `attemptSequence`.
- Receipt persistence can fail because Room is unavailable → propagate cancellation, but convert other failures to a structured diagnostics warning so network startup remains available.
- Generic event messages are less queryable than dedicated Room columns → use a typed producer and a canonical bounded token format now; avoid a database/archive migration until cross-event querying is a demonstrated requirement.
- Initial component statuses other than proxy may still be unavailable → mark them explicitly `NoData`; do not synthesize idle components as authoritative snapshots.
- Existing consumers may not recognize the new subsystem token → the event envelope is additive and ignorable; no existing field meaning changes.

## Migration Plan

1. Add tests for the typed supervisor start result and verify the current implementation fails by discarding the snapshot.
2. Propagate startup evidence through proxy and VPN orchestration and test that telemetry publication precedes `Connected`.
3. Add the typed receipt classifier/recorder and generation handling with baseline, remembered, command-line, mismatch, and storage-failure tests.
4. Cover initial and replacement paths plus archive retention/redaction.
5. Run affected module tests, `staticAnalysis`, architecture health, and locked Cargo metadata. No native artifact claim is made unless an APK/native build is separately executed.

Rollback is a source revert: remove the additive event emission and restore endpoint-only start returns. There is no persisted-schema rollback or data migration; previously recorded unknown event kinds remain safely ignorable.
