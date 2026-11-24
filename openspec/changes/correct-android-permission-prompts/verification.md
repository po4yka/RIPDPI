---
task_id: AND-1795489914228572
change: correct-android-permission-prompts
commit_sha: d4863dae04238436dc52c9f1ad6cbe1ff68afdc0
local: passed
local_evidence: 283 focused app tests and 16 detection tests passed. App and detection Detekt passed. GitHub debug, service extended debug, and detection Android Lint passed. The host tests use the Xray stub.
remote_ci: required
remote_ci_evidence: null
device: blocked
device_evidence: Both Android 15 Pixel 6 pool slots remained ADB unauthorized before APK installation. The temporary Android 17 emulator exited before boot. No system permission interaction was observed.
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
| REQ-ANDROID-PERMISSION-RECOVERY | AND-1795489981806709 | Source checks, four Roborazzi comparisons, and attempted Android 15 and 17 checks | Local pass, device blocked |

## Local debug build

The arm64 GitHub debug APK assembled on the rebased change before the Detekt
refactor. It uses the existing JNI build outputs, a rebuilt root helper, and
Xray and pluggable transport stubs. This build is for permission UI checks.

## UI references

The existing RDS permission, scanner, detection, and first-run views provide
the layout reference. The app adds explicit recovery choices in existing
rows and buttons. The Roborazzi VPN fixture does not show the dialog content,
so its comparison gives no evidence for the system window.
