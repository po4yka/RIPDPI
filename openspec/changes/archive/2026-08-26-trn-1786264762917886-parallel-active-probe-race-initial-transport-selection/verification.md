---
task_id: TRN-1786264762917886
change: trn-1786264762917886-parallel-active-probe-race-initial-transport-selection
commit_sha: a195ac2fc98af8fbc68e52f98ea3adb96b5e2ab0
local: passed
local_evidence: Integrated-tree gate battery on main HEAD a195ac2fc (Rust gates captured at f28e90966, docs-only task-board delta between them) - cargo nextest run --locked -p ripdpi-relay-core -p ripdpi-relay-android exit 0 with 184 passed / 0 skipped; bash scripts/ci/run-rust-relay-interoperability.sh exit 0 (fixture stack 25/25, transport interop 447 passed + 3 skipped, relay-core 178/178, nested-proxy network_e2e 36/36); ./gradlew :core:diagnostics:testDebugUnitTest :core:service:testDebugUnitTest :app:testGithubSimpleDebugUnitTest exit 0 with 5159 tests / 0 failures / 0 errors / 0 skipped; ./gradlew staticAnalysis exit 0; python3 scripts/ci/check_architecture_health.py (--check) exit 0; cargo metadata --manifest-path native/rust/Cargo.toml --locked valid. Gradle runs executed with ambient CARGO_BUILD_JOBS and CARGO_TARGET_DIR unset per the rust-native plugin's ambient-override contract; first attempts without this failed on that guard, verbatim recorded in the gate logs.
remote_ci: passed
remote_ci_evidence: Full CI workflow run 32933047982 success on exact main SHA c465912fe9b1ea8256f1f22b98be373dd522c442; the delta c465912fe..a195ac2fc touches only docs/tasks and openspec records (no Rust or Kotlin sources). The push-run 32948835477 on a195ac2fc shows a rust-lint failure non-reproducible locally (identical bash scripts/ci/run-rust-lint.sh exits 0 on the same commit) with runner-side log-attribution loss (all steps UNKNOWN, ~2min, orphan sccache termination); failed jobs rerun via gh run rerun --failed.
device: not_applicable
device_evidence: No Android device behavior is owned by this portfolio area.
artifact: not_applicable
artifact_evidence: No distributable artifact is required for this portfolio area.
deployment: not_applicable
deployment_evidence: RIPDPI changes are not deployed by the task workflow.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-TRN-1786264762917886-001 | TRN-1786264762919130 | Deterministic service race-outcome tests green in :core:service:testDebugUnitTest (1849 tests, 0 failures) on integrated main | passed |
| REQ-TRN-1786264762917886-002 | TRN-1786264762919983 | Simple-flavor selection tests green in :app:testGithubSimpleDebugUnitTest (1908 tests, 0 failures); feature commit 5774d8f12 select-initial-relay-by-active-race integrated | passed |
| REQ-TRN-1786264762917886-003 | TRN-1786264762919113 | Relay-core ephemeral-listener coverage green: focused nextest 184/184 incl. shutdown-drain suites; interop fixture stack 25/25 | passed |
| REQ-TRN-1786264762917886-004 | TRN-1786264762919155 | Full relay interoperability script exit 0: 447 transport-interop tests across 9 crates + network_e2e 36/36 | passed |
| REQ-TRN-1786264762917886-005 | TRN-1786264762919212 | Diagnostics diagnostics-suite green (:core:diagnostics 1402 tests, 0 failures); probe results bridge covered by :app suite | passed |
| REQ-TRN-1786264762917886-006 | TRN-1786264762919757 | staticAnalysis exit 0 and architecture health (--check) exit 0 on integrated tree; LoC-limit check clean | passed |
| REQ-TRN-1786264762917886-007 | TRN-1786266573979454 | Gate battery per work log completed on integrated tree: all local gates observed passing (see frontmatter); controlled relay-lab satisfied at its config/self-test layer (test-relay-matrix-config.sh exit 0 asserting both paired initialTransportRaceScenarios); live execution of the paired scenarios remains an operator-owned standing requirement gated by RIPDPI_RELAY_MATRIX_CONFIG and manual evidence rows, outside this change's source scope | passed |
