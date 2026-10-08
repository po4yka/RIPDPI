---
task_id: DAT-1791477854155125
change: fix-awg-subscription-refresh
commit_sha: null
local: passed
local_evidence: 36 app and 21 Room tests passed; affected ktlint and detekt passed; architecture health unchanged.
remote_ci: not_applicable
remote_ci_evidence: Local fix validation; hosted CI reported separately after push.
device: not_applicable
device_evidence: Persistence behavior is covered by Room and coordinator tests; no tunnel runtime change.
artifact: not_applicable
artifact_evidence: No release artifact requested.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-AWG-REFRESH-IDENTITY | DAT-1791477965964573 | AwgProfileRepositoryRoomTest and SubscriptionAwgRefreshTest | Passed |
| REQ-AWG-REFRESH-KEY | DAT-1791477965964573 | AwgProfileRepositoryRoomTest and SubscriptionAwgRefreshTest | Passed |
| REQ-AWG-REFRESH-SAFETY | DAT-1791477973108000 | Duplicate, source isolation, and rollback regression tests | Passed |

## Observed local checks

- RED: with the original refresh coordinator, all four SubscriptionAwgRefreshTest cases failed. They detected duplicate rows, duplicate-tag acceptance, and cross-refresh identity loss. The fixed source was restored before GREEN.
- GREEN: `:app:testGithubFullDebugUnitTest` with `SubscriptionAwgRefreshTest`, `SubscriptionRefreshCoordinatorTest`, and `*ImportConfirmViewModelTest`: 36 tests passed, zero failures, errors, or skips.
- `:core:data:testDebugUnitTest --tests '*AwgProfileRepositoryRoomTest'`: 21 tests passed, zero failures, errors, or skips.
- `:app:ktlintCheck :core:data:ktlintCheck :app:detekt :core:data:detekt`: passed.
- Gradle used `-Pripdpi.skipNativeBuild=true` for Kotlin unit tests, with four workers. No native runtime or VPS acceptance is claimed.
- `python3 scripts/ci/check_architecture_health.py`: 21 unchanged indicators; no new or worsened entry.
- Independent review approved the scope and the empty-batch and duplicate-origin guards.

## Upgrade boundary

Legacy AWG rows have no source provenance. They stay unchanged. The first managed template needs the local private key set once; later refreshes reuse that row. No name-based ownership inference or automatic deletion occurs.
