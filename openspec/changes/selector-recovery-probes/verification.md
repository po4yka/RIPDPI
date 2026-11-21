---
task_id: VPN-1795271597027505
change: selector-recovery-probes
commit_sha: null
local: passed
local_evidence: 215 selector tests and 11 ledger tests passed. App and runtime-state Detekt, verifyAppEngineBoundary, just ci-gates, and the GitHub debug instrumentation APK build passed.
remote_ci: required
remote_ci_evidence: null
device: passed
device_evidence: API 35 arm64 emulator ran XrayProviderE2ETest.stalledBodyOnActiveVpnUsesVerifiedReserve against the local peer. JUnit recorded one test with no skips. The peer recorded both stalled and completed transfers, and the client logged verification of the applied reserve.
artifact: not_applicable
artifact_evidence: The change uses the ordinary Android build.
deployment: not_applicable
deployment_evidence: The change runs locally in the client.
---

# Verification

Exercise coalescing, current scope, recovery after a live failure, the regular schedule, and durable admission.

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
| --- | --- | --- | --- |
| REQ-VPN-1795271597027505-001 | VPN-1795271653257934 | SelectorRecoveryScheduleTest and SelectorLiveVpnPayloadTest cover event bursts, queued requests, the regular interval, cancellation, and stale VPN routes. | Passed |
| REQ-VPN-1795271597027505-002 | VPN-1795271653817124 | The recovery headroom regression failed against the previous ledger. All 11 ledger tests passed after the change, including shared caps and downgrade reads. | Passed |
