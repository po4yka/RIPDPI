---
task_id: DGN-1791519134625558
change: selective-availability-matrix
commit_sha: null
local: passed
local_evidence: Native matrix 20 tests and affected native suites 276 tests passed; Kotlin matrix 20 tests passed, including three Robolectric Compose tests. Full staticAnalysis, app/service locale lint, workspace Clippy and architecture checks passed. The approved catalog and taxonomy fixtures pass 23 Kotlin tests and four Rust contract tests.
remote_ci: required
remote_ci_evidence: Matrix commits reached origin/main at aa9d6821b; GitHub Actions were in progress on 2026-10-09.
device: blocked
device_evidence: adb devices -l returned no attached devices on 2026-10-09.
artifact: required
artifact_evidence: Kotlin compilation and host native tests passed. Android native APK build is unverified; local Gradle gates use ripdpi.skipNativeBuild=true.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-MATRIX-CATALOG | DGN-1791519312778863 | Four catalog definition, rendering, bounds and legal registry tests | Passed, including approved fixture equality |
| REQ-MATRIX-PROBES | DGN-1791519311924988 | 20 matrix tests and 276 affected native tests | Passed locally |
| REQ-MATRIX-EVIDENCE | DGN-1791519311924988 | Conservative aggregate tests; atomic persisted scope regression | Passed locally |
| REQ-MATRIX-UI | DGN-1791519313591507 | Eight mapping/input tests; three Compose tests; five inspected render artifacts | Passed locally, including 360 dp and RTL at font scale 1.5 |
| REQ-MATRIX-STORAGE | DGN-1791519312778863 | Kotlin contract, planning and persisted scope tests | Passed locally |

## Review and remaining gates

- Independent review found no remaining confirmed P1 or P2 issue in the combined tree.
- Architecture health and native architecture contract checks passed without baseline changes.
- The user approved the catalog and outcome taxonomy fixture updates on 2026-10-09. The reviewed patch changes only `diagnostics-contract-fixtures/profile_catalog_current.json` and `diagnostics-contract-fixtures/outcome_taxonomy_current.json`. Other fixture families are unchanged.
- Full workspace Clippy passed with `--locked --workspace --no-deps --all-targets -- -D warnings` after the interrupted build was resumed.
- After integration with concurrent origin/main work, the combined monitor suite passed all 249 tests. The three earlier failures no longer reproduce. The concurrent commit owns its existing golden update.
- Compose Preview CLI timed out while fetching dependencies. The Robolectric Native fallback rendered five disposable PNG files under `app/build/compose-previews/renders/`. Input validation, repeat/coverage rendering and large RTL text checks passed. Render inspection found no clipped labels after the width fix.

## Final local gate commands

- `./gradlew :app:testGithubFullDebugUnitTest --tests '*DiagnosticsSelectiveMatrix*' :core:diagnostics:testDebugUnitTest --tests '*SelectiveMatrix*' staticAnalysis :app:lintGithubFullDebug :core:service:lintDebug -Pripdpi.skipNativeBuild=true --max-workers=2 --offline` passed.
- `./gradlew :build-logic:convention:test --tests '*DiagnosticsCatalogSelectiveMatrixTest' --max-workers=2 --offline --no-configuration-cache` passed (four tests).
- `cargo test --locked -p ripdpi-diagnostics-contracts -p ripdpi-diagnostics-probes -p ripdpi-diagnostics-runner --no-fail-fast` passed (276 tests, five existing ignored tests).
- `cargo test --locked -p ripdpi-diagnostics-contracts -p ripdpi-diagnostics-probes -p ripdpi-diagnostics-runner -p ripdpi-monitor-engine matrix --no-fail-fast` passed (20 tests).
- `cargo clippy --locked --workspace --no-deps --all-targets -- -D warnings` passed.
- `./gradlew :core:diagnostics:testDebugUnitTest --tests '*DiagnosticsContractGovernanceTest' --tests '*DiagnosticsOutcomeTaxonomyTest' -Pripdpi.skipNativeBuild=true --max-workers=2 --offline --console=plain --no-daemon` passed (23 tests).
- `cargo test --locked -p ripdpi-monitor-engine --test contract_fixtures` passed (four tests).
- `check_architecture_health.py`, `check_native_architecture_contracts.py`, `check_harness_links.py --strict`, `check_harness_cargo_locked.py`, locked Cargo metadata, and `taskctl validate` passed.

The approved shared fixtures are applied. Catalog equality, shared decoding and complete outcome coverage passed. Main integration and push are complete at aa9d6821b. Remote CI and Android device/artifact evidence remain separate gates; the task remains in review.
