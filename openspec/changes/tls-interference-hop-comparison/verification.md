---
task_id: DGN-1795014015067016
change: tls-interference-hop-comparison
commit_sha: 10e4747a3
local: passed
local_evidence: On 10e4747a3 (macOS, Rust 1.100.0) cargo test --locked for ripdpi-diagnostics-contracts, -tls, -runner, -probes and ripdpi-monitor-engine passed (794 tests incl. contract_fixtures); cargo clippy --locked --workspace --no-deps --all-targets -D warnings passed; :core:diagnostics:testDebugUnitTest 1835 tests, :app:testGithubDebugUnitTest 3767 tests and :build-logic:convention:test passed with 0 failures; :app:lintGithubDebug and :core:service:lintDebug passed; just ci-gates passed.
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: null
artifact: required
artifact_evidence: null
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

Local gates passed on the commit above. No device run, no packet capture, and no exact-revision CI run exist yet, so the on-wire ladder behavior on a real Android network is unverified. No claim about any real network follows from these tests.

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-TLS-INTERFERENCE-HOP-COMPARISON-CAPABILITY | DGN-1795014094363681 | `ripdpi-diagnostics-runner` `connectivity::tls_hop` tests: a proxy path gives a `tls_hop_unsupported` row with reason `proxy_path` and no interval; hop_ladder tests cover protect, TTL setter and linger failures as `Unsupported`. Kotlin `TlsHopComparisonTest` rejects IN_PATH and non-user origins in `ActiveProbeSafetyPolicy`. Compose test shows the TTL reason without an interval. | passed locally |
| REQ-TLS-INTERFERENCE-HOP-COMPARISON-PAIR | DGN-1795014095035306 | hop_ladder tests (ladder order, divergence 5..5 with path 9, server-only reaction inconclusive, cancellation keeps attempts); runner test `cancelled_report_withdraws_interval_but_keeps_attempts`; engine request validation requires both the task and the config; planner test sends only the user endpoint. | passed locally |
| REQ-TLS-INTERFERENCE-HOP-COMPARISON-PRIVACY | DGN-1795014095646908 | `TlsHopComparisonTest` export case: the redacted detail JSON has no `tlsHopLocal`, no `elapsedMs` and no timing value, and keeps interval, path length and limits. Runner test checks the exported evidence JSON has no elapsed field. | passed locally |
| REQ-TLS-INTERFERENCE-HOP-COMPARISON-LIMITS | DGN-1795083686784867 | hop_ladder limit tests; `TlsHopComparisonTest` network-scope case withdraws the interval, adds `network_changed`, keeps attempts, and leaves unsupported rows unchanged; Compose test shows the near-client ServerHello limit. | passed locally |

## Fixtures regenerated

- `contract-fixtures/diagnostics_schema_version.json`, `ripdpi-monitor-engine/tests/golden/{final_report,progress_starting}.json`, `tests/fixtures/connectivity_report_partial.json`: schemaVersion 10 to 11, through `RIPDPI_BLESS_GOLDENS=1 cargo test`.
- `core/diagnostics/src/test/resources/golden/archive/integrity_v12.json`: one entry hash, through `RIPDPI_BLESS_GOLDENS=1` on `DiagnosticsArchiveRendererTest`.
- `default_profiles.json` through `:core:diagnostics:generateDiagnosticsCatalog`, copied to `diagnostics-contract-fixtures/profile_catalog_current.json`.
- `ripdpi-diagnostics-contracts/api-snapshot.txt` through `scripts/ci/check_rust_api_snapshots.py --update`; `config/i18n/translatable-keys.txt` through `scripts/ci/export-strings-for-translation.sh`.
- Hand-maintained shared fixtures: `diagnostics-contract-fixtures/engine_{request,report,progress}_current.json` schemaVersion and eight `tls_hop_comparison` rows in `outcome_taxonomy_current.json`.

## Known limits

- The run-time network-change signal is a new local address between attempts. A network switch that keeps the address is not detected during the run; network-scope revocation after the scan covers part of that gap.
- The endpoint host is resolved like the HTTP/3 and PMTU probes; resolved addresses are not rechecked against local ranges.
