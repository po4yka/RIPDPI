---
task_id: OUT-1786264762917513
change: out-1786264762917513-add-mieru-outbound-client-crate-and-profile-editor
commit_sha: 32a9b2c8aa0a52945cb9f6c5e38c43f8194a30c3
local: passed
local_evidence: On branch claude/close-p2 (base a9ab13f0d): cargo test --locked -p ripdpi-mieru 52 passed with the two upstream-peer tests ignored (they need a live peer and run in CI); -p ripdpi-network-time 15 passed; ripdpi-relay-tls-transports mieru facade test passed (UDP rejected before socket connect). Kotlin: MieruProfileViewModelTest 6, ImportIntentFilterTest 14, RelayProfileMappingTest 4, AnyTlsPasswordPresentationTest 4 (:app), ProxyUriCodecTest 24, ProxyUriCodecExportTest 15, SelectorRelayProfileMappingTest 9 and ProxyGroupTest 9 (:core:data) passed with zero failures.
remote_ci: passed
remote_ci_evidence: Main 32a9b2c8aa0a52945cb9f6c5e38c43f8194a30c3 passed full push CI run 41170842447 (ci-required succeeded). Its relay-interoperability job 124448649012 step 'Run pinned VLESS, SSH, Mieru, and AnyTLS upstream peers' ran against upstream mieru 155ebbd60f86e472586a60d7ffe58ec8f8682cb1 (peer sha256 b6153861cfbe04e6b111dec6b226bed7bedcd1dc3e2351dac4082f5f31c881a5): tcp_stream_exchanges_payload_with_upstream, multiplexed_tcp_streams_exchange_without_cross_contamination and mieru_off_socks_payload_and_stop_with_upstream all passed.
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
| REQ-OUT-1786264762917513-001 | OUT-1786264762917550 | mux tests concurrent_streams_round_trip_without_cross_contamination, sequential_reuse_keeps_nonce_monotonic_across_many_streams and cancelling_open_stream_rolls_back_demux_registration pass; upstream multiplexed_tcp_streams_exchange_without_cross_contamination passes in CI | Passed |
| REQ-OUT-1786264762917513-002 | OUT-1786264762917605 | MieruProfileViewModelTest (6) passes | Passed |
| REQ-OUT-1786264762917513-003 | OUT-1786264762917618 | ripdpi-network-time tests (15) pass, including calibrated_ignores_device_clock_and_advances_monotonically; the facade reads the shared provider | Passed |
| REQ-OUT-1786264762917513-004 | OUT-1786264762917419 | config::debug_redacts_password_but_keeps_username, ProxyGroupTest and AnyTlsPasswordPresentationTest Mieru cases pass | Passed |
| REQ-OUT-1786264762917513-005 | OUT-1786264762917565 | ProxyUriCodecTest, ProxyUriCodecExportTest, SelectorRelayProfileMappingTest and ImportIntentFilterTest pass | Passed |
| REQ-OUT-1786264762917513-006 | OUT-1786266573979348 | Three tests pass against pinned upstream mieru 155ebbd60 in CI run 41170842447 job 124448649012 | Passed |
| REQ-OUT-1786264762917513-007 | OUT-1786266573979902 | Approved scope decision (2026-10-31): the UDP carrier stays open in follow-up task OUT-1793433244049580; UDP profiles are still rejected before DNS or dial | Passed |
