---
task_id: DGN-1795704974460888
change: dgn-1795704974460888-split-selective-availability-targets-into-domestic-foreign-datacenter-and-cdn-classes
commit_sha: d9aa00b99ffd01d299c33993874b39d7b3b98d72
local: passed
local_evidence: 'Rust: cargo test --locked -p ripdpi-diagnostics-contracts -p ripdpi-diagnostics-runner -p ripdpi-monitor-engine selective (exit 0) and -p ripdpi-diagnostics-runner verified_download (6 passed, exit 0); cargo fmt --check exit 0; check_rust_api_snapshots.py exit 0. Kotlin: :core:diagnostics:testStandardDebugUnitTest on SelectiveMatrix*, TargetClassFreezeReport*, BundledDiagnosticsProfileImporterTest (upgrade from a saved v1 profile), DiagnosticsContractGovernanceTest, DiagnosticsSummaryProjector* exit 0; build-logic :convention:test DiagnosticsCatalog* exit 0. Logs under /tmp/dgn-logs (not retained). Not run: just ci-gates, staticAnalysis, app lint.'
remote_ci: passed
remote_ci_evidence: 'CI workflow run https://github.com/po4yka/RIPDPI/actions/runs/46009834608 succeeded on exact main SHA d9aa00b99ffd01d299c33993874b39d7b3b98d72: 35 successful jobs and 31 change-routed skips, no failures, including kotlin-coverage, verify-roborazzi and android-instrumented-tests on API 27, 33, 35, 36 and 37. Rust workspace tests skipped there (no Rust change since); they succeeded in run https://github.com/po4yka/RIPDPI/actions/runs/45985235551 on ancestor a0714e6e37.'
device: required
device_evidence: null
artifact: not_applicable
artifact_evidence: No distributable artifact is required for this change.
deployment: not_applicable
deployment_evidence: RIPDPI changes are not deployed by the task workflow.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DGN-1795704974460888-001 | DGN-1795704976973961 | Rust validate test and Kotlin contract test reject missing, unknown and user-cohort classes; regenerated catalog assigns domestic_hosting, cdn and foreign_datacenter to the six existing targets; locally | Passed |
| REQ-DGN-1795704974460888-002 | DGN-1795704980201514 | Runner emits targetClass and destinationAsn details; export summary fields include them; no address detail is written; locally. AS values are catalog-declared, not observed | Passed |
| REQ-DGN-1795704974460888-003 | DGN-1795704982276408 | VerifiedVolumeComparisonPlanningTest covers the declared pair (half pair, reserved AS, valid, both legs carry it); TargetClassFreezeReportTest groups catalog matrix rows and declared volume legs by class and AS in one report, volume_declared apart from catalog, undeclared legs volume_unattributed; Rust request validation tests accept a valid pair on volume targets and reject it on other profiles, half pairs and reserved AS; a verified_download test emits targetClass and destinationAsn only when declared; locally. Class and AS on volume legs are user-declared, not observed | Passed |
| REQ-DGN-1795704974460888-004 | DGN-1795704985443487 | Kotlin responseClass tests plus Rust test that a 403 gives verified_download_failed with httpStatusCodeReadings 403; size and hash mismatch map to server response; matrix targets compare framing only, not exact bytes; locally | Passed |
