---
task_id: DGN-1795085357320979
change: dgn-1795085357320979-vendor-field-contracts-failure-stage-taxonomy
commit_sha: null
local: required
local_evidence: null
remote_ci: required
remote_ci_evidence: null
device: not_applicable
device_evidence: No Android device behavior is owned by this task.
artifact: not_applicable
artifact_evidence: No distributable artifact is required for this task.
deployment: not_applicable
deployment_evidence: RIPDPI changes are not deployed by the task workflow.
---

# Verification

`local_evidence` stays null until the owner runs the Gradle gates below; the Rust, Python and ktlint gates passed in the implementing sessions. Logs are in `/private/tmp/claude-501/-Users-po4yka-GitRep/77ff58fa-9fb9-4fb3-ac83-38ef9dcd63ce/scratchpad/runs-r2r4-fix/`.

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DGN-1795085357320979-001 | DGN-1795085386045842 | Rust `types::field_failure_stage::tests::enum_values_equal_vendored_schema_enum` passes; Kotlin `FieldFailureStageContractTest.enum values equal the vendored schema enum` awaits the owner Gradle run | required |
| REQ-DGN-1795085357320979-002 | DGN-1795085386910727 | Rust `every_class_and_stage_maps_as_the_shared_table_states` and `shared_table_names_only_native_codes` pass against `contract-fixtures/native_failure_stage_mapping.tsv`; Kotlin `FieldFailureStageContractTest` (same table, DpiProbeError sealed-subclass enumeration, ProbeStage) awaits the owner Gradle run | required |
| REQ-DGN-1795085357320979-003 | DGN-1795085387814161 | `classifyFailureText` removed (`git grep classifyFailureText` finds no code); Rust `evidence_text_does_not_change_the_stage` passes; Kotlin `FieldTelemetryTest.message text never changes the failure class` awaits the owner Gradle run | required |
| REQ-DGN-1795085357320979-004 | DGN-1795085388657402 | Kotlin `DeveloperAnalyticsAllowListTest` failure-outcome and cancellation tests await the owner Gradle run | required |
| REQ-DGN-1795085357320979-005 | DGN-1795085389411523 | Vendored files match `vendored-deploy-contracts.sha256` and deployer commit `e84aec57d824` (`check_vendored_deploy_contracts.py --source`, exit 0); Rust contract-version, golden and negative-fixture tests pass; `check_cross_language_runtime_contracts.py` passes; Kotlin contract tests await the owner Gradle run | required |

## Checks run in the implementing sessions

- `bash scripts/ci/cargo-guarded.sh cargo test --locked -p ripdpi-diagnostics-classification -p ripdpi-diagnostics-contracts --lib field_failure_stage` (from `native/rust/`): exit 0, 3 + 4 passed (`cargo-test-field-failure-stage.log`)
- `bash scripts/ci/cargo-guarded.sh cargo clippy --locked -p ripdpi-diagnostics-classification -p ripdpi-diagnostics-contracts --no-deps --all-targets -- -D warnings`: exit 0 (`cargo-clippy.log`)
- `check_rust_api_snapshots.check_snapshot(..., update=True)` for `ripdpi-diagnostics-contracts` only, with `CARGO_TARGET_DIR` in the session scratchpad because the shared target directory was locked by another session's stalled `cargo check`: exit 0; the diff adds only `FieldFailureStage` and `CLIENT_FIELD_REPORT_CONTRACT_VERSION` items (`api-snapshot-contracts-update.log`). The full `python3 scripts/ci/check_rust_api_snapshots.py` check afterwards: exit 0, all 13 snapshots match (`api-snapshot-check.log`)
- The lefthook `rust-clippy` pre-commit hook passed on the Rust commit (`commit-rust.log`)
- `ktlint` 1.8.0 on every changed Kotlin file: exit 0 (`ktlint.log`)
- `python3 scripts/ci/check_cross_language_runtime_contracts.py` and `python3 -m unittest scripts.tests.test_cross_language_runtime_contracts`: exit 0 (`cross-lang.log`, `cross-lang-unittest.log`)
- `./taskctl validate` and `just task-check`: exit 0 (`taskctl-validate.log`, `task-check.log`)

## Gradle gates left for the owner

Gradle was not run (owner build policy). Run:

```bash
./gradlew :core:diagnostics:testDebugUnitTest --tests 'com.poyka.ripdpi.diagnostics.FieldFailureStageContractTest' --tests 'com.poyka.ripdpi.diagnostics.DeveloperAnalyticsAllowListTest' --tests 'com.poyka.ripdpi.diagnostics.DiagnosticsArchiveExporterTest' -Pripdpi.skipNativeBuild=true
./gradlew :core:service:testDebugUnitTest --tests 'com.poyka.ripdpi.services.FieldTelemetryTest' --tests 'com.poyka.ripdpi.services.NaiveProxyManagerPreflightTest' --tests 'com.poyka.ripdpi.services.NaiveUpstreamLauncherTest' -Pripdpi.skipNativeBuild=true
./gradlew :core:data:testDebugUnitTest --tests 'com.poyka.ripdpi.data.ServiceTelemetryTest' -Pripdpi.skipNativeBuild=true
./gradlew :core:diagnostics:testDebugUnitTest :core:service:testDebugUnitTest :core:data:testDebugUnitTest :core:detection:testDebugUnitTest :app:testGithubDebugUnitTest
./gradlew staticAnalysis
just ci-gates
```

The full unit-test runs are needed because classification no longer reads message text: any test that relied on `lastError` or native-event text to select a `FailureClass` now gets the typed result.

## Review fixes

Findings of the independent review of `fd922df2b..HEAD`. Each Kotlin regression test below was written to fail before its fix; none was compiled or run (owner build policy: no Gradle).

| Finding | Fix and regression test | Result |
|---|---|---|
| M1 tunnel and DNS signals lost | Removing text classification dropped the tunnel `lastError` and resolver-fallback signals that drive the Status DNS and tunnel stages (`MainStateResolvers`, `RecentWarningTracker`, `RoutingProtectionPresenter`). Without a failure reason, the tunnel's typed fields now map explicitly: a recorded tunnel worker error (presence only) is `TunnelEstablish` and `resolverFallbackActive` is `DnsInterference`; no Rust field was needed. `FieldTelemetryTest` `typed tunnel signals still classify DNS and tunnel failures` | written, owner Gradle run pending |
| M2 stale lastFailureClass overrode typed reasons | `lastFailureClass` lasts for a runtime's whole session, so it overrode `Unexpected`, `NativeError` and `WarpRuntimeFailed` with an old class. A raised reason now takes a native class only from a typed native event recorded within `FreshNativeFailureMillis` (30 s) before `failureAtMillis` (the publish time, or the snapshot's `lastFailureAt`); the live path without a reason still reads `lastFailureClass`. `FieldTelemetryTest` `a raised failure takes a native class only from an event just before it` | written, owner Gradle run pending |
