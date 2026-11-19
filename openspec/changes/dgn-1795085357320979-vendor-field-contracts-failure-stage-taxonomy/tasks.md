# DGN-1795085357320979: Vendor field analytics contracts and unify the failure-stage taxonomy

## Objective

One failure_stage enum shared by Rust and Kotlin, typed classification instead of text matching, non-silent export errors, and contract tests on vendored fixtures.

## Ownership

native/rust/crates/ripdpi-diagnostics-contracts, ripdpi-diagnostics-classification (production wiring in ripdpi-monitor-engine is R3); contract-fixtures/native_failure_stage_mapping.tsv; core/diagnostics-data, core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/dpi/, core/data/model/src/main/kotlin/com/poyka/ripdpi/data/ServiceTelemetry.kt, core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsArchiveExporter.kt, core/data/src/test/resources/contract/. Serialized lane: schema-version constants, wire.rs, vendored fixtures (single writer; R4 follows).

## Execution

- [x] DGN-1795085385227357 Vendor client-field-report v1 schema and valid/negative fixtures into core/data/src/test/resources/contract/ with a drift pin test #feature !high @item:DGN-1795085357320979
- [x] DGN-1795085386045842 Add the failure_stage enum and total probe/connection mapping to ripdpi-diagnostics-contracts and core/diagnostics-data #feature !high @item:DGN-1795085357320979
- [x] DGN-1795085386910727 Map DpiProbeError, ProbeStage and the NaiveProxy probe path to failure_stage with exhaustive tests #feature !high @item:DGN-1795085357320979
- [x] DGN-1795085387814161 Remove classifyFailureText string matching in ServiceTelemetry.kt and update every call site to typed stages #feature !high @item:DGN-1795085357320979
- [x] DGN-1795085388657402 Log and return a typed outcome for developer analytics failure in DiagnosticsArchiveExporter.kt and audit its other runCatching uses #feature !high @item:DGN-1795085357320979
- [x] DGN-1795085389411523 Add Rust and Kotlin contract tests plus check_cross_language_runtime_contracts.py coverage against the vendored fixtures #feature !high @item:DGN-1795085357320979

## Verification

cargo test -p ripdpi-diagnostics-contracts -p ripdpi-diagnostics-classification --locked field_failure_stage; cargo clippy -p ripdpi-diagnostics-contracts -p ripdpi-diagnostics-classification --locked --all-targets -- -D warnings; python3 scripts/ci/check_rust_api_snapshots.py; ./gradlew :core:diagnostics:testDebugUnitTest :core:data:testDebugUnitTest; python3 scripts/ci/check_cross_language_runtime_contracts.py; just ci-gates; ./taskctl validate.
