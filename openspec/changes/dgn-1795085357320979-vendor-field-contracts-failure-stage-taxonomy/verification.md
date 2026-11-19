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

`local_evidence` stays null until the owner runs the Gradle gates below; the Rust and Python gates passed in the implementing session.

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DGN-1795085357320979-001 | DGN-1795085386045842 | Rust `types::field_failure_stage::tests::enum_values_equal_vendored_schema_enum` passes; Kotlin `FieldFailureStageContractTest.enum values equal the vendored schema enum` awaits the owner Gradle run | required |
| REQ-DGN-1795085357320979-002 | DGN-1795085386910727 | Rust `field_failure_stage` mapping tests in `ripdpi-diagnostics-classification` pass; Kotlin `FieldFailureStageContractTest` DpiProbeError, ProbeStage and FailureClass tables await the owner Gradle run | required |
| REQ-DGN-1795085357320979-003 | DGN-1795085387814161 | `classifyFailureText` removed (`git grep classifyFailureText` finds no code); Rust `evidence_text_does_not_change_the_stage` passes; Kotlin `FieldTelemetryTest.message text never changes the failure class` awaits the owner Gradle run | required |
| REQ-DGN-1795085357320979-004 | DGN-1795085388657402 | Kotlin `DeveloperAnalyticsAllowListTest` failure-outcome and cancellation tests await the owner Gradle run | required |
| REQ-DGN-1795085357320979-005 | DGN-1795085389411523 | Vendored files are byte-identical to deployer commit `e917cf5b`; Rust contract-version, golden and negative-fixture tests pass; `check_cross_language_runtime_contracts.py` passes; Kotlin contract tests await the owner Gradle run | required |

## Checks run in the implementing session

- `cargo test -p ripdpi-diagnostics-contracts --locked field_failure_stage` (from `native/rust/`): 4 passed
- `cargo test -p ripdpi-diagnostics-classification --locked field_failure_stage`: 3 passed
- `cargo fmt -p ripdpi-diagnostics-contracts -p ripdpi-diagnostics-classification`
- `ktlint` 1.8.0 on every changed Kotlin file: clean
- `python3 scripts/ci/check_cross_language_runtime_contracts.py`: valid, 5 surfaces, 30 gates
- `./taskctl validate`

Not run: `cargo clippy` for the two crates and the `ripdpi-diagnostics-contracts/api-snapshot.txt` regeneration. Every cargo invocation queued on the machine-wide build gate behind another session's stalled build, so `cargo public-api` never started. `api-snapshot.txt` therefore does not yet list `FieldFailureStage` and `CLIENT_FIELD_REPORT_CONTRACT_VERSION`, and `check_rust_api_snapshots.py` fails until the owner regenerates it.

## Uncommitted Rust stage

The Rust half (`ripdpi-diagnostics-contracts/src/types/field_failure_stage.rs`, `ripdpi-diagnostics-classification/src/classification/field_failure_stage.rs`, their module wiring, and the `quality/release-gates/cross-language-runtime-contracts.json` gate entries) is written and tested but not committed: the lefthook `rust-clippy` pre-commit hook queued for 940 s on the machine-wide build gate behind another session's stalled build and was stopped. Commit it once the gate is free; the hook then runs clippy for the two crates.

## Gradle gates left for the owner

Gradle was not run (owner build policy). Run:

```bash
./gradlew :core:diagnostics:testDebugUnitTest --tests 'com.poyka.ripdpi.diagnostics.FieldFailureStageContractTest' --tests 'com.poyka.ripdpi.diagnostics.DeveloperAnalyticsAllowListTest' --tests 'com.poyka.ripdpi.diagnostics.DiagnosticsArchiveExporterTest' -Pripdpi.skipNativeBuild=true
./gradlew :core:service:testDebugUnitTest --tests 'com.poyka.ripdpi.services.FieldTelemetryTest' --tests 'com.poyka.ripdpi.services.NaiveProxyManagerPreflightTest' --tests 'com.poyka.ripdpi.services.NaiveUpstreamLauncherTest' -Pripdpi.skipNativeBuild=true
./gradlew :core:data:testDebugUnitTest --tests 'com.poyka.ripdpi.data.ServiceTelemetryTest' -Pripdpi.skipNativeBuild=true
./gradlew :core:diagnostics:testDebugUnitTest :core:service:testDebugUnitTest :core:data:testDebugUnitTest :core:detection:testDebugUnitTest :app:testGithubDebugUnitTest
./gradlew staticAnalysis
(cd native/rust && cargo clippy -p ripdpi-diagnostics-contracts -p ripdpi-diagnostics-classification --all-targets --locked -- -D warnings)
python3 scripts/ci/check_rust_api_snapshots.py --update   # then commit native/rust/crates/ripdpi-diagnostics-contracts/api-snapshot.txt
just ci-gates
```

The full unit-test runs are needed because classification no longer reads message text: any test that relied on `lastError` or native-event text to select a `FailureClass` now gets the typed result.
