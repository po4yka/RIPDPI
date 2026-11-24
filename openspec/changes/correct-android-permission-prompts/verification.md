---
task_id: AND-1795489914228572
change: correct-android-permission-prompts
commit_sha: null
local: passed
local_evidence: 283 focused app tests and 16 detection tests passed with the Xray stub. Combined source gates and device checks follow integration with the latest main.
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: null
artifact: not_applicable
artifact_evidence: No release artifact belongs to this change.
deployment: not_applicable
deployment_evidence: No deployment belongs to this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-ANDROID-PERMISSION-CONTEXT | AND-1795489979733001 | MainActivityContentTest and MainViewModelTest | Pass |
| REQ-ANDROID-PERMISSION-RECOVERY | AND-1795489980269709 | MainActivityPermissionTest, MainViewModelTest, OnboardingViewModelTest and QR recovery suites | Pass |
| REQ-ANDROID-PERMISSION-RECOVERY | AND-1795489980775429 | DetectionPermissionStateOwnerTest, DetectionPermissionPlannerTest and LocationSignalsCheckerTest | Pass |
| REQ-ANDROID-PERMISSION-CONTEXT | AND-1795489981276204 | SettingsCustomizationActionsTest, SettingsPreferencesGroupingTest and UpdateViewModelTest | Pass |
| REQ-ANDROID-PERMISSION-RECOVERY | AND-1795489981806709 | Source gates and available Android device checks | Pending |
