---
task_id: DNS-1795705017215412
change: dns-1795705017215412-keep-universal-dns-and-ech-check-failures-out-of-block-verdicts
commit_sha: d9aa00b99ffd01d299c33993874b39d7b3b98d72
local: passed
local_evidence: 'cargo test -p ripdpi-diagnostics-dns --locked: new resolver_panel tests pass (104 passed, 1 failed); the 1 failure, dns::measurement_tests::encrypted_measurement_retains_numeric_ede_after_padding_restore (loopback WouldBlock), fails identically on origin/main without this change. cargo fmt --check exit 0.'
remote_ci: passed
remote_ci_evidence: 'CI workflow run https://github.com/po4yka/RIPDPI/actions/runs/46009834608 succeeded on exact main SHA d9aa00b99ffd01d299c33993874b39d7b3b98d72: 35 successful jobs and 31 change-routed skips, no failures, including kotlin-coverage, verify-roborazzi and android-instrumented-tests on API 27, 33, 35, 36 and 37. Rust workspace tests skipped there (no Rust change since); they succeeded in run https://github.com/po4yka/RIPDPI/actions/runs/45985235551 on ancestor a0714e6e37.'
device: not_applicable
device_evidence: Host-only Rust survey classification; no Android runtime path consumes it.
artifact: not_applicable
artifact_evidence: No distributable artifact is required for this change.
deployment: not_applicable
deployment_evidence: RIPDPI changes are not deployed by the task workflow.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DNS-1795705017215412-001 | DNS-1795705022067853 | resolver_panel.rs: ResolverSurveyRunner::survey relabels an all-resolvers-failed panel as ResolverReachability::UniversalFailure instead of per-resolver Blocked; test survey_where_every_resolver_fails_is_universal_failure_not_blocked (cargo test -p ripdpi-diagnostics-dns --locked). Probe path already safe: classify_dns_probe_outcome (Err,Err) -> dns_unavailable, outcome_policy.rs maps it to Inconclusive | pass |
| REQ-DNS-1795705017215412-002 | DNS-1795705025532458 | Verified no change needed: CdnEchUpdater (cdn_ech/cache.rs) has no blocked/downgrade/suppress state; both sources failing serves stale or bundled config; existing test updater_returns_bundled_when_both_sources_fail_and_no_cache | pass |
| REQ-DNS-1795705017215412-003 | DNS-1795705027754514 | survey_where_every_resolver_fails_is_universal_failure_not_blocked vs survey_with_one_working_resolver_keeps_per_resolver_blocked_verdicts and survey_blocked_resolver_yields_blocked_verdict: only the partial failure yields Blocked | pass |
| REQ-DNS-1795705017215412-004 | DNS-1795705029995682 | No history or export consumer of ResolverSurveyResult exists (git grep finds none outside resolver_panel.rs); history and redacted exports carry probe outcome strings, where universal failure is already the distinct dns_unavailable or dns_oracle_unavailable outcome. Nothing to wire, no schema change | pass |
