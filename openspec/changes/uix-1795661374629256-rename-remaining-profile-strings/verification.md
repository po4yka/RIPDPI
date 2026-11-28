---
task_id: UIX-1795661374629256
change: uix-1795661374629256-rename-remaining-profile-strings
commit_sha: d9aa00b99ffd01d299c33993874b39d7b3b98d72
local: passed
local_evidence: On 013b3bcecc, ./gradlew :app:verifyRoborazziGithubDebug (4326 tests) BUILD SUCCESSFUL; ./gradlew :app:lintGithubDebug :core:service:lintStandardDebug BUILD SUCCESSFUL; just ci-gates exit 0; scripts/ci/export-strings-for-translation.sh leaves config/i18n/translatable-keys.txt unchanged because no key was added or removed.
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
| REQ-UIX-1795661374629256-001 | UIX-1795661387364302 | design.md Decisions lists all 192 English strings that contained "profile": 73 server, 11 preset, 108 other. | pass |
| REQ-UIX-1795661374629256-002 | UIX-1795661387832510 | 013b3bcecc changes the 84 server and preset keys in values and the nine values-* locales (90 XML files); other keys are unchanged. RelayFieldsTest and OnboardingScreenTest now assert the new English text and pass. | pass |
| REQ-UIX-1795661374629256-003 | UIX-1795661388329874 | Export script leaves translatable-keys.txt unchanged (no key set change); :app:lintGithubDebug and :core:service:lintStandardDebug pass. | pass |
| REQ-UIX-1795661374629256-004 | UIX-1795661388823900 | 51 Roborazzi goldens re-recorded in 013b3bcecc (43 on the first verify run, 8 dark twins on the second); each sampled diff changes only the renamed text. Third verify run passes. | pass |
