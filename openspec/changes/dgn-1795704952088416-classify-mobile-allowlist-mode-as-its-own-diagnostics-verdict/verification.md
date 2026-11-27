---
task_id: DGN-1795704952088416
change: dgn-1795704952088416-classify-mobile-allowlist-mode-as-its-own-diagnostics-verdict
commit_sha: null
local: required
local_evidence: Full classification and monitor-engine Rust suites (407 tests), core diagnostics (1900) and app (4003) unit suites passed before review fixes, apart from one load-timing failure each in untouched code that passed on rerun; after review fixes the eight allowlist rule tests, the engine report test, the matrix, importer, governance and session-detail tests, Clippy, ktlint, detekt, Rust API snapshots, comment ratchet, locale parity and translation export passed.
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
| REQ-DGN-1795704952088416-001 | DGN-1795704955437869 | `mobile_allowlist::tests::permitted_reachable_with_foreign_and_udp_failing_is_allowlist_mode`, `tls_stage_failure_reads_as_dpi_not_allowlist`, `foreign_range_block_with_reachable_domestic_hosting_is_not_allowlist`, `udp_reply_or_missing_udp_evidence_is_not_allowlist`; engine `connectivity_report_carries_allowlist_verdict_only_for_selective_evidence` | local passed; device pending |
| REQ-DGN-1795704952088416-002 | DGN-1795704958591524 | `mobile_allowlist::tests::universal_failure_is_not_allowlist_mode`, `mixed_or_inconclusive_matrix_is_not_allowlist`; engine report test outage case | local passed; device pending |
| REQ-DGN-1795704952088416-003 | DGN-1795704961040923 | Diagnosis evidence `narrowing=standard_https_to_permitted_destination` and `excludedTransports=udp,direct_foreign_address` plus recommendation; `DiagnosticsSessionDetailUiFactoryTest` allowlist case shows the localized narrowing | local passed; device pending |
| REQ-DGN-1795704952088416-004 | DGN-1795704963217300 | Diagnosis persists in the report used by history detail; `SelectiveMatrixContractTest` allowlist export case shows evidence and reason lines with a private address removed | local passed; device pending |
