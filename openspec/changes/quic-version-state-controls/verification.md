---
task_id: DGN-1795014101783145
change: quic-version-state-controls
commit_sha: f27d282fd
local: passed
local_evidence: On f27d282fd (macOS, Rust 1.100.0) cargo test --locked --no-fail-fast for ripdpi-diagnostics-transport, -contracts, -runner, -probes and ripdpi-monitor-engine passed (864 tests incl. contract_fixtures); cargo clippy --locked --all-targets -D warnings on those crates passed; :core:diagnostics:testDebugUnitTest 1841 tests, :core:diagnostics-data:testDebugUnitTest, :app:testGithubDebugUnitTest 3770 tests and :build-logic:convention:test 61 tests passed with 0 failures; :app:lintGithubDebug and :core:service:lintDebug passed; just ci-gates passed. The transport matrix tests ran in the cargo run above; the RFC 9369 KAT ran separately with cargo test --locked -p ripdpi-packets v2_kat (3 tests passed).
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

Local gates passed on the commit above. No device run, no packet capture, and no exact-revision CI run exist yet, so the on-wire behavior of v1 and v2 Initials on a real Android network is unverified. No claim about any real network follows from these tests.

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-QUIC-VERSION-STATE-CONTROLS-VERSION | DGN-1795014102533538 | `ripdpi-packets` `quic::v2_kat_tests` check the v2 Initial secrets, keys, IVs and HP keys against RFC 9369 Appendix A. The transport matrix rejects a built packet whose version field differs from the requested version. Runner test `codec_builds_requested_versions_and_reads_version_negotiation` checks that the `ripdpi-packets` codec builds v1 and v2 Initials and classifies a Version Negotiation reply; `version_negotiation_for_v2_is_unsupported_by_endpoint_not_a_difference` gives `quic_version_unsupported_by_endpoint` and no difference. | Passed |
| REQ-QUIC-VERSION-STATE-CONTROLS-ISOLATION | DGN-1795014103091464 | Transport `quic_version_matrix_tests` cover fresh sockets per cell and the opt-in residual cell. Runner tests keep execution order in the cell `sequence` (0, 1, 4, 5 for v1), report the residual cell as its own `quic_version_residual` row with the same-flow limit, and emit the `local_port_separation_only` limit on every row. Compose test shows the NAT-mapping limit. | Passed |
| REQ-QUIC-VERSION-STATE-CONTROLS-CONTROL | DGN-1795083688813560 | Runner `control_timeout_is_inconclusive`; engine request validation needs both the task and a valid config; `ActiveProbeSafetyPolicy` rejects IN_PATH, an invalid config and non-user origins (`QuicVersionMatrixTest`); the planner sends only the user endpoint. The timeout, byte budget and total deadline are fixed in the runner and clamped to the scan deadline. | Passed |
| REQ-QUIC-VERSION-STATE-CONTROLS-CLAIMS | DGN-1795014103668973 | Runner `v1_test_timeout_with_v2_answer_is_an_initial_stage_difference` and the `initial_stage_only` limit; the export test drops unknown fields and keeps roles only (no SNI, local port or timing); network-scope revocation turns every version claim inconclusive and adds `network_changed`; buckets never mark an unanswered version as blocked. The probe writes no relay or strategy setting. The Compose test shows the Initial-stage limit. | Passed |

## Fixtures regenerated

- `default_profiles.json` through `:core:diagnostics:generateDiagnosticsCatalog`, copied to `diagnostics-contract-fixtures/profile_catalog_current.json`.
- `ripdpi-diagnostics-contracts/api-snapshot.txt` through `scripts/ci/check_rust_api_snapshots.py --update`; `config/i18n/translatable-keys.txt` through `scripts/ci/export-strings-for-translation.sh`.
- Hand-maintained `diagnostics-contract-fixtures/outcome_taxonomy_current.json`: 36 `quic_version_*` rows.
- The schema stays at 11, so no schema-version fixture or golden changed.

## Known limits

- The runner resolves the host itself and sends to the first resolved address, like the TLS hop and HTTP/3 probes; it does not re-check that address against local ranges or pick the network's IP family.
- Every PermissionDenied I/O error is reported as reason `protect`, including a later `sendto` rejected by a firewall.
- Sockets are protected but not bound to a selected network, like the rest of the diagnostics transport.
- The residual row cannot tell same-flow state from endpoint-wide state; the UI shows it as a hint with that limit.
