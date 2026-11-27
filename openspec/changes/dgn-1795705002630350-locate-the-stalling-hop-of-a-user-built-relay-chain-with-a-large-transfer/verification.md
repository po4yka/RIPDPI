---
task_id: DGN-1795705002630350
change: dgn-1795705002630350-locate-the-stalling-hop-of-a-user-built-relay-chain-with-a-large-transfer
commit_sha: d9aa00b99ffd01d299c33993874b39d7b3b98d72
local: passed
local_evidence: 'Local run on 2026-11-27 with -Pripdpi.skipNativeBuild=true: full :core:service:testStandardDebugUnitTest passed (2341 tests, 0 failures), including RelayChainStallLocatorTest; after review fixes the class reran with 7 of 7 passing plus :core:service:detekt and ktlintCheck exit 0 (cases: stall at hop 3 of 4 with byte count and prefix shapes, entry-hop stop with 0 bytes and no later probes, mid-chain hop that never comes up stops with 0 bytes, every prefix delivered, single endpoint not split, inline hop or rejected configuration unmeasured, full body failing verification or unknown byte count unmeasured). Full :app:testGithubDebugUnitTest ran 4004 tests with 1 failure in a new test fixture (a one-control policy violates PayloadFailoverPolicy); after the fix OnboardingModeValidationRunnerTest passed 22 of 22, including the chain hop and single-endpoint cases. staticAnalysis failed only on one test line length, then :app:detekt and :app:ktlint checks exit 0 after the fix. Comment ratchet (both modes), locale parity, translation export, Rust API snapshots and architecture health --staged exit 0. No Rust source changed.'
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
| REQ-DGN-1795705002630350-001 | DGN-1795705006939658 | RelayChainStallLocator measures prefixes entry, entry+hop2, ... in order with the 32 KiB+ control and reports the first stopping hop and its byte count; onboarding names the hop and records the bytes (RelayChainStallLocatorTest, OnboardingModeValidationRunnerTest) | pass (local) |
| REQ-DGN-1795705002630350-002 | DGN-1795705013000092 | A non-chain profile is SingleEndpoint with no prefix measured; onboarding keeps the single whole-path stall message (locator and runner tests) | pass (local) |
| REQ-DGN-1795705002630350-003 | DGN-1795705016563517 | Each prefix runs in CandidateRelayPayloadProbe through CandidateHttpPayloadProbe.probeControl with the connection test control and timeout; no new transfer engine | pass (local) |
