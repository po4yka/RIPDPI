---
task_id: DGN-1795615859682455
change: dgn-1795615859682455-monitor-health-of-each-server
commit_sha: d9aa00b99ffd01d299c33993874b39d7b3b98d72
local: passed
local_evidence: "cargo test -p ripdpi-warp-core --locked (198 passed); cargo clippy -p ripdpi-warp-core --locked --all-targets -D warnings (clean); :core:service:testStandardDebugUnitTest AwgEndpointCheckTest (5/5); :core:diagnostics:testStandardDebugUnitTest DiagnosticsBoundaryMapperTest (4/4); :core:engine-api:testDebugUnitTest (13 suites, 0 failures); :app:testGithubDebugUnitTest awg, profiles, diagnostics, history, status, MainViewModelTest (1174 tests, 0 failures); check_architecture_health.py --check on changed files (0 new indicators)."
remote_ci: passed
remote_ci_evidence: "CI workflow run https://github.com/po4yka/RIPDPI/actions/runs/46009834608 succeeded on exact main SHA d9aa00b99ffd01d299c33993874b39d7b3b98d72: 35 successful jobs and 31 change-routed skips, no failures, including kotlin-coverage, verify-roborazzi and android-instrumented-tests on API 27, 33, 35, 36 and 37. Rust workspace tests skipped there (no Rust change since); they succeeded in run https://github.com/po4yka/RIPDPI/actions/runs/45985235551 on ancestor a0714e6e37."
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
| REQ-DGN-1795615859682455-001 | DGN-1795615860260013 | `AwgEndpointCheckTest` (answered handshake reports its RTT, no answer fails, keys/obfuscation/PSK reach the probe, WS carrier and incomplete profiles are unsupported); `SavedAwgProfilesViewModelTest` (Check shows the handshake RTT or a timeout on that row); `ProfileUtilityScreenTest` (keyed AWG rows have Check, rows without a key do not, a result reads as a handshake time). Relay, Xray and selector rows keep the existing HTTP check. Device handshake against a real AWG server: pending. | local pass, device pending |
| REQ-DGN-1795615859682455-002 | DGN-1795615860750752 | `tunnel::tests::handshake_response_records_the_round_trip`, `rtk_south_cohort_handshakes_with_a_synthetic_awg_endpoint` (running AWG runtime reports `upstream_rtt_ms`), `telemetry_*` tests (omitted while unknown); `MainViewModelTest` "status names the connected server with the round trip its egress reports". Status strip renders server, server latency, uptime and the existing traffic total. Device screenshot: pending. | local pass, device pending |
| REQ-DGN-1795615859682455-003 | DGN-1795615861169007 | `DiagnosticsBoundaryMapperTest` (event keeps its relay profile token); `DiagnosticsWatchListTest` (same failure on two servers stays two rows, each with its server). `SavedServerNames` maps `opaqueRelayProfileToken(memberId)` to the member's exit name; event rows and the watch list show it as a badge. | local pass |
| REQ-DGN-1795615859682455-004 | DGN-1795615861594713 | `DiagnosticsBoundaryMapperTest` (the token is `@Transient` and never serializes). Export code is unchanged: `DiagnosticsArchiveRedactor` still projects `relayProfileToken` to a stable correlator and the CSV builder aliases it; no export reads `DiagnosticsEventUiModel`, so server names stay on device. AWG telemetry still omits the endpoint (`telemetry_json_does_not_expose_endpoint_host_or_port`). | local pass |
