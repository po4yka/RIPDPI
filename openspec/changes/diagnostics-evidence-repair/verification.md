---
task_id: DGN-1793896868711593
change: diagnostics-evidence-repair
commit_sha: null
local: passed
local_evidence: 1651 diagnostics, 306 engine, and 18 selected app tests passed; 623 Rust tests passed. Full staticAnalysis, affected-crate clippy, and independent code review passed.
remote_ci: not_applicable
remote_ci_evidence: Local repair only; no push or hosted run is authorized.
device: not_applicable
device_evidence: This change corrects evidence interpretation and configuration composition; no device or real-network acceptance is claimed.
artifact: passed
artifact_evidence: Android ARM64 core engine assembly passed. The catalog checks and ten approved diagnostics fixtures passed their owning tests.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DGN-COMPOSE | DGN-1793897160308491 | Native composition and Kotlin application regression tests | passed |
| REQ-DGN-VERIFY | DGN-1793897160655912 | Home verification regression tests | passed |
| REQ-DGN-SUITE | DGN-1793897160993343 | DPI suite regression tests | passed |
| REQ-DGN-LANES | DGN-1793897160308491 | Single-lane selection, contract, UI, and export tests | passed |
| REQ-DGN-COMPARE | DGN-1793897160993343 | Path comparison regression tests | passed |
| REQ-DGN-SNI | DGN-1793897161336946 | Native alternative-SNI regression tests | passed |
| REQ-DGN-TRANSFER | DGN-1793897161680091 | Transfer, catalog, and throughput classification tests | passed |
| REQ-DGN-RESET | DGN-1793897161336946 | Native TCP classification regression tests | passed |

Combined checks and independent review belong to DGN-1793897162019058.

## Observed checks

- `:core:diagnostics:testDebugUnitTest`: 1651 tests passed.
- `:core:engine:testDebugUnitTest`: 306 tests passed.
- `:app:testGithubFullDebugUnitTest` for `DiagnosticsStrategyReportLayoutTest`, `DiagnosticsStrategyProbeUiFactoryTest`, and `DiagnosticsStrategyPersianPresentationTest`: 18 tests passed.
- Build convention tests: 44 tests passed, including throughput catalog tests.
- `cargo test --locked --no-fail-fast` for diagnostics candidates, classification, contracts, fat-header, runner, and monitor-engine: 623 tests passed. Two existing timing-sensitive trigger tests and two opt-in soak tests were ignored.
- `staticAnalysis`, affected-crate clippy, and Rust formatting checks passed.
- `just ci-gates` passed after the approved fixture update. Architecture, contract, locale, generated catalog, and API snapshot checks passed. `cargo metadata --locked` passed.
- `:core:engine:assembleDebug` passed with the Android ARM64 native build.
- Independent code review found no remaining actionable defects. The async review found no changed async functions.
- The user approved the exact ten-fixture diff. The isolated golden writer regenerated the owned outputs and verified them without bless mode. The diff updates schema 9 to 10, the generated throughput catalog, the inconclusive throughput outcome, and the archive report digest and byte count.

No physical-device, public-network, hosted CI, or soak acceptance was performed. The diagnostics wire schema changes to version 10; version 9 is rejected by the current schema check.
