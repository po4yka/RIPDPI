---
task_id: TRN-1792759596946903
change: profile-quality-mtu
commit_sha: 261f301910fafe5b40c724b35f65a87e669f3876
local: passed
local_evidence: "Full staticAnalysis and core:diagnostics/core:service unit suites passed with 1655/2277 tests and zero failures, errors or skips; the full app suite passed 3002 tests. The Rust diagnostics suite passed 73 tests and the independent Go peer suite passed. English and Arabic quality/PMTU renders at enlarged font scale and independent local reviews passed. The later workflow-only correction changed no runtime or test source."
remote_ci: passed
remote_ci_evidence: "Protected PR 657 head 72cb0d354c1a8e19350c0979d583716892e3c4b3 passed CI 39982466513. Squash main 261f301910fafe5b40c724b35f65a87e669f3876 has the identical tree 76742a9543ebe5f8a6b0bf833b1c69f074049a82 and passed CI 39990623691 including ci-required, plus CodeQL, Secret Scan, harness, locale/export and fleet-fixture workflows."
device: passed
device_evidence: "Real native instrumentation passed on Android emulator API 27/33/35/36/37. Both required Shadowsocks quality methods passed once per API; API 35 also passed the required REALITY quality method and all five real Xray provider acceptance cases. Downloaded exact-main JUnit artifacts passed the canonical required-test validator; required executions were not skipped. Separate local API 35 arm64 acceptance passed all three quality methods and owned-runtime cleanup. No physical-device or production-network acceptance is claimed."
artifact: passed
artifact_evidence: "Exact-main CI built and verified all four Android native ABIs, all three Debug/Release distribution channels and the source-bound instrumentation APK bundle consumed by the five emulator jobs. The separate local arm64 APK and instrumentation APK were fingerprinted before and after native acceptance; their hashes are recorded below."
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-PAYLOAD-IMPORT | TRN-1792760231865743 | Schema/import vectors, atomic rejection and stored-health/catalog/journal migration tests; main unit and fleet-fixture gates | Passed |
| REQ-QUALITY-BUDGET | TRN-1792760231865743 | ProfileQualityBudgetLedgerTest: restart, reboot, policy, cancellation and persistence failures; main unit gate | Passed |
| REQ-QUALITY-MEASURE | TRN-1792760232586356 | QualityHttpPayloadProbeTest real sockets, independent Go peer tests and native verified GET/baseline/overlapping HEAD acceptance | Passed |
| REQ-QUALITY-LIFETIME | TRN-1792760232586356 | Relay/Xray owner tests, native cancellation/retirement and REALITY runtime acceptance | Passed |
| REQ-QUALITY-SELECT | TRN-1792760233292624 | SelectorQualityTest and native sustained transfer improvement through applied acknowledgement; partial/scope/cooldown tests | Passed |
| REQ-QUALITY-PRESENT | TRN-1792760233980732 | ProfileQualityUiTest, ProfileQualityComposeTest, MainQualityResolverTest, enlarged English/Arabic renders and all-locale gates | Passed |
| REQ-QUALITY-PMTU | TRN-1792760233980732 | PmtuDiagnosisTest, PmtuProbeEvidenceTest, DiagnosticsPmtuUiTest and DiagnosticsPmtuComposeTest | Passed |

Native Android acceptance, local combined gates, independent review, protected
PR integration and exact-main hosted checks complete TRN-1792760234710191.

## Exact-main acceptance

- [Main CI 39990623691](https://github.com/po4yka/RIPDPI/actions/runs/39990623691)
  and its `ci-required` job completed successfully on the recorded commit.
- [PR CI 39982466513](https://github.com/po4yka/RIPDPI/actions/runs/39982466513)
  passed on the identical tree before protected integration.
- Each `android-instrumented-artifacts-pixel6Api*` artifact preserves the full
  integration JUnit report. The two `ProfileQualityNativeTest` methods
  `healthySlowerNativeMemberLosesOnlyAfterSustainedVerifiedTransferAndAppliedAck`
  and `nativeFastBodyReportsPartialLoadAndCancellationRetiresOwnedHandle`
  passed exactly once on each API: ten required executions in total.
- API 35 additionally preserves the five-case `xray-provider` report, including
  `realRealityQualityMeasuresAuthenticatedBodyAndConcurrentHeadBeforeRetirement`.
  This lane used an independent real encrypted peer and authenticated transfers.
- Local API 35 arm64 acceptance used application APK SHA-256
  `0e9f0dbadaeacabd5ac37f9689efd25bb5e55f0ed4bd9f0f260adbf9577022b5`
  and test APK SHA-256
  `64780018988ddcce6f97f33b8e6b01ebc7942d5204bddd80453f9828b92af1c9`.
  All three required methods and owned-runtime/device cleanup passed.

The native tests exercise actual encrypted transports against controlled peers;
they are not production-network or physical-device receipts. Hosted automated
code/security reviews could not run because of account usage limits; independent
local reviews and the required CodeQL/Secret Scan checks passed. The linked
deployment task retains its own acceptance and lifecycle authority.
