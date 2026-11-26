---
task_id: DGN-1795704974460888
change: dgn-1795704974460888-split-selective-availability-targets-into-domestic-foreign-datacenter-and-cdn-classes
commit_sha: null
local: passed
local_evidence: "Rust: cargo test --locked -p ripdpi-diagnostics-contracts -p ripdpi-diagnostics-runner -p ripdpi-monitor-engine selective (exit 0) and -p ripdpi-diagnostics-runner verified_download (6 passed, exit 0); cargo fmt --check exit 0; check_rust_api_snapshots.py exit 0. Kotlin: :core:diagnostics:testStandardDebugUnitTest on SelectiveMatrix*, TargetClassFreezeReport*, BundledDiagnosticsProfileImporterTest (upgrade from a saved v1 profile), DiagnosticsContractGovernanceTest, DiagnosticsSummaryProjector* exit 0; build-logic :convention:test DiagnosticsCatalog* exit 0. Logs under /tmp/dgn-logs (not retained). Not run: just ci-gates, staticAnalysis, app lint."
remote_ci: required
remote_ci_evidence: null
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
| REQ-DGN-1795704974460888-003 | DGN-1795704982276408 | Not met in production: TargetClassFreezeReportTest groups matrix and volume-leg results by class and AS; volume legs get class and AS only when the caller passes the matched catalog target, and no session code does yet; locally | Partial |
| REQ-DGN-1795704974460888-004 | DGN-1795704985443487 | Kotlin responseClass tests plus Rust test that a 403 gives verified_download_failed with httpStatusCodeReadings 403; size and hash mismatch map to server response; matrix targets compare framing only, not exact bytes; locally | Passed |
