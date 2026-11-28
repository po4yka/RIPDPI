---
task_id: UIX-1795615850526190
change: uix-1795615850526190-add-own-server-entry-to-onboarding-and-home
commit_sha: d9aa00b99ffd01d299c33993874b39d7b3b98d72
local: passed
local_evidence: "./gradlew :app:verifyRoborazziGithubDebug (all 4293 app unit tests plus every Roborazzi golden) BUILD SUCCESSFUL; ./gradlew :app:lintGithubDebug BUILD SUCCESSFUL; just ci-gates exit 0; ktlint on changed files exit 0"
remote_ci: passed
remote_ci_evidence: "CI workflow run https://github.com/po4yka/RIPDPI/actions/runs/46009834608 succeeded on exact main SHA d9aa00b99ffd01d299c33993874b39d7b3b98d72: 35 successful jobs and 31 change-routed skips, no failures, including kotlin-coverage, verify-roborazzi and android-instrumented-tests on API 27, 33, 35, 36 and 37. Rust workspace tests skipped there (no Rust change since); they succeeded in run https://github.com/po4yka/RIPDPI/actions/runs/45985235551 on ancestor a0714e6e37."
device: required
device_evidence: "Pending: not run on a device or emulator in the implementation session"
artifact: not_applicable
artifact_evidence: No distributable artifact is required for this change.
deployment: not_applicable
deployment_evidence: RIPDPI changes are not deployed by the task workflow.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-UIX-1795615850526190-001 | UIX-1795615851095684 | `OnboardingStepsScreenTest` own server step test (paste, scan, import, add manually, Continue skips); golden `onboardingOwnServerScreen` | pass (local) |
| REQ-UIX-1795615850526190-002 | UIX-1795615851521470 | `StatusScreenTest` add server shows only while offered; `SavedServersViewModel` gates it on no saved server; route `config/add_server` covered by `RipDpiNavHostLogicTest` | pass (local) |
| REQ-UIX-1795615850526190-003 | UIX-1795615851952693 | `ConnectionSummaryTest` add server row opens the add actions on the Direct path and the add-server route opens them; goldens `configScreen`, `configMediumScreen` | pass (local) |
| REQ-UIX-1795615850526190-004 | UIX-1795615852383192 | `onboarding_test_permission_note` and `onboarding_test_failed_permission` no longer claim no remote server, in all 10 locales; golden `onboardingValidationIdleScreen` | pass (local) |
| REQ-UIX-1795615850526190-005 | UIX-1795615852801246 | 3 new keys and 1 deleted key in all 10 locales, `translatable-keys.txt` regenerated, `lintGithubDebug` pass; 30 goldens re-recorded and 1 new golden, `verifyRoborazziGithubDebug` green | pass (local) |
