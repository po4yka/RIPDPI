---
task_id: DGN-1795704789266331
change: dgn-1795704789266331-require-a-volume-moving-transfer-for-the-connection-test-verdict
commit_sha: null
local: required
local_evidence: 'Local run on 2026-11-27, arm64-only native staging: :app:testGithubDebugUnitTest --tests ''*nboarding*'' --tests ''*ScreenCatalog*'' passed with 0 failures, including OnboardingModeValidationRunnerTest volume cases (verified, stalled with byte count, rejected, second control, no control, noDomain) and OnboardingStepsScreenTest checklist rows; :core:service StrictPayloadSocksTest new cases (stalled body reports receivedBytes 1 and timedOut, full wrong-digest body reports 32768 and timedOut false) passed; the pre-existing case ''second independent control can prove the same relay when first control fails'' fails identically on clean origin/main and is unrelated. staticAnalysis exit 0; comment ratchet (both modes), locale parity, architecture-delta and just task-check exit 0.'
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
| REQ-DGN-1795704789266331-001 | DGN-1795704939014047 | Runner downloads the largest operator control through the active SOCKS route with the strict payload probe and passes only on exact length and SHA-256 (runner unit tests) | pass (local) |
| REQ-DGN-1795704789266331-002 | DGN-1795704942941433 | A stall fails at the Volume step with the received byte count; probe-level byte count proven by StrictPayloadSocksTest stalled-body case | pass (local) |
| REQ-DGN-1795704789266331-003 | DGN-1795704946758728 | Only the control URL is contacted; no control or noDomain yields volume not tested, shown without the word Passed | pass (local) |
| REQ-DGN-1795704789266331-004 | DGN-1795704950734953 | Reuses CandidateHttpPayloadProbe, OkHttpRelayTcpProbe and consumeVerifiedPayload; no new downloader | pass (local) |
