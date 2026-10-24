---
task_id: TRN-1792759596946903
change: profile-quality-mtu
commit_sha: e22c8d66d8fa0c9245eb5bf8ef802e24028d0f48
local: passed
local_evidence: Quality and PMTU tests, native diagnostics, real socket and Android acceptance, UI renders, full static analysis and the production storage migration regression passed; source and counts are below.
remote_ci: passed
remote_ci_evidence: Exact main e22c8d66d8fa0c9245eb5bf8ef802e24028d0f48 passed run 40085973754 on its first attempt, with 48 successful jobs and 16 routed skips; all five API jobs and the separate CodeQL, Secret Scan, harness, L7 dry-run and fleet-fixture workflows passed.
device: passed
device_evidence: Exact-main e22c8d66 CI artifacts prove ten required quality-native passes across API 27/33/35/36/37 and the API 35 REALITY quality pass; none was skipped. Official artifact digests and source metadata were verified. No physical device was used.
artifact: passed
artifact_evidence: Final hopping PR 661 produced all four native ABIs and artifact 12556472475; the repository verifier checked six APK hashes, packages and sealed source e87c9428636e72d641d6940afd3269efa759b27a.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-PAYLOAD-IMPORT | TRN-1792760231865743 | Schema/import contract tests and mirrored vectors | Passed |
| REQ-QUALITY-BUDGET | TRN-1792760231865743 | Durable ledger restart, reboot, policy, cancellation and persistence tests | Passed |
| REQ-QUALITY-MEASURE | TRN-1792760232586356 | Real socket verified GET and overlapping HEAD measurements | Passed |
| REQ-QUALITY-LIFETIME | TRN-1792760232586356 | Native relay/Reality ownership and cancellation acceptance | Passed |
| REQ-QUALITY-SELECT | TRN-1792760233292624 | Selector goodput/loaded-latency positive, partial, scope and cooldown tests | Passed |
| REQ-QUALITY-PRESENT | TRN-1792760233980732 | Existing profile UI and locale checks | Passed |
| REQ-QUALITY-PMTU | TRN-1792760233980732 | Scoped PMTU interpretation and existing UI tests | Passed |

Native Android positive acceptance, local combined gates and exact-main hosted
checks belong to TRN-1792760234710191. No fixture substitutes for live proof.

## Source and regression evidence

The initial quality source merged in PR 657 as
`261f301910fafe5b40c724b35f65a87e669f3876`. Local diagnostics, service and app
suites passed 1655, 2277 and 3002 tests, respectively. Full `staticAnalysis`,
73 Rust diagnostics tests, independent Go peer tests and enlarged English and
Arabic quality/PMTU renders passed. Main CI `39990623691` passed, including
native instrumentation on API 27, 33, 35, 36 and 37. Each API ran both required
Shadowsocks quality methods; API 35 also passed the REALITY quality method and
all five real Xray provider cases. These tests use actual encrypted controlled
peers. They do not prove production-network or physical-device acceptance.

A later audit found that the production storage schema decoded the old health
policy before the intended migration. The pending closure PR 658 was withdrawn
without merging. Its completion claim is not acceptance evidence for the
storage boundary. The fix moved migration before typed decode and added tests
through the actual schema/read boundary, including both outer schema versions,
persistence failure, retry, unknown fields and a fresh reload. PR 659 merged
as `da73e19858b00d362d6f8eb41271e6e273b3b3cd`; main CI `40073180307` passed.
Before integration, run `40069960190` and artifact `12551347260` confirmed
979 data and 303 runtime-state tests, including all four
`ProxyGroupStoredPolicyMigrationTest` regressions, with zero failures or skips.
The final hopping integration keeps this fix and adds checked migration of the
hopping model without granting old profile-check authority.

The final local client checks passed 325 runtime-state, 988 data, 2280 service,
315 engine and three app migration/share tests, plus full `staticAnalysis` and
3188 Simple app tests. No failure or skip occurred in these local suites.
The final PR head `b8b2eca0294973df68f52ecddcafcef6ccc62c8a` passed all required
checks and merged as `e22c8d66d8fa0c9245eb5bf8ef802e24028d0f48`.

Final Android bundle `12556472475` has SHA-256
`4d3b54c898c32c68d66c0c660f62148b905297a0d8d5aef152444e86628f19c6`.
All four native ABI builds passed, and the canonical bundle verifier checked
all six APKs and their source. Final exact-main run `40085973754` completed successfully on its first attempt
at 2026-10-24 11:14:34 UTC: 48 successful jobs, 16 routed skips, zero failures.
Its native quality JUnit reports passed the inspection below. CodeQL, Secret
Scan, harness, L7 dry-run and fleet-fixture workflows also passed on this commit.

Independent local reviews covered the source changes. Hosted automated review
availability was limited by account usage; it is not recorded as a passed
review. The linked producer task retains its own acceptance and lifecycle.

## Current main Android evidence

Run `40085973754` at `e22c8d66d8fa0c9245eb5bf8ef802e24028d0f48`
produced API 27/33/35/36/37 artifacts `12557757932`, `12558256159`,
`12557419144`, `12557564930` and `12557329255`, respectively. Each archive's
official SHA-256 and exact source metadata were checked before its XML was read.
Mirrored XML reports were deduplicated by test identity.

- All five Full suites contain 91 tests, zero failures or errors and seven
  declared skips. The required quality methods are not among those skips.
- `healthySlowerNativeMemberLosesOnlyAfterSustainedVerifiedTransferAndAppliedAck`
  and `nativeFastBodyReportsPartialLoadAndCancellationRetiresOwnedHandle`
  passed once on each API: ten required native quality executions.
- The unchanged `grantedPermissionLaunchShowsReadyHomeConnectionAction`
  passed once on each API, including API 35 and 36, which failed at an older
  PR revision. This is observed acceptance, not a claimed repair of that timeout.
- API 35's separate Xray-provider suite passed all five cases, including
  `realRealityQualityMeasuresAuthenticatedBodyAndConcurrentHeadBeforeRetirement`.

These results use actual native transports against controlled peers on Android
emulators. They do not claim physical-device or production-network acceptance.
