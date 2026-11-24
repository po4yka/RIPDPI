---
task_id: AND-1795489914228572
change: correct-android-permission-prompts
commit_sha: d0ee0f52e5363b363e34889044c5ebd03f501c4b
local: passed
local_evidence: The implementation source d4863dae passed 283 focused app tests, 16 detection tests, Detekt, and Android Lint. The recorded main source passed 37 focused permission and scanner recovery JVM tests and just ci-gates. The host tests use the Xray stub.
remote_ci: passed
remote_ci_evidence: "Android static analysis, Kotlin coverage, and Roborazzi passed at d1b75749a2ef4b01e01b3b6648dfdd475f35487e in hosted run 45441455734. These checks cover this task. The standalone fuzz lockfile is the only change from the locally tested source."
device: not_applicable
device_evidence: Physical system-dialog checks are outside the approved Android app acceptance scope. Earlier emulator attempts ended before APK installation or system-dialog interaction.
artifact: not_applicable
artifact_evidence: No release artifact belongs to this change.
deployment: not_applicable
deployment_evidence: No deployment belongs to this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-ANDROID-PERMISSION-CONTEXT | AND-1795489979733001 | MainActivityContentTest and MainViewModelTest | passed |
| REQ-ANDROID-PERMISSION-RECOVERY | AND-1795489980269709 | MainActivityPermissionTest, MainViewModelTest, OnboardingViewModelTest and QR recovery suites | passed |
| REQ-ANDROID-PERMISSION-RECOVERY | AND-1795489980775429 | DetectionPermissionStateOwnerTest, DetectionPermissionPlannerTest and LocationSignalsCheckerTest | passed |
| REQ-ANDROID-PERMISSION-CONTEXT | AND-1795489981276204 | SettingsCustomizationActionsTest, SettingsPreferencesGroupingTest and UpdateViewModelTest | passed |
| REQ-ANDROID-PERMISSION-RECOVERY | AND-1795489981806709 | Source checks, four Roborazzi comparisons, and focused app and detection tests | passed |

## Local debug build

The arm64 GitHub debug APK assembled on the rebased change before the Detekt
refactor. It uses the existing JNI build outputs, a rebuilt root helper, and
Xray and pluggable transport stubs. This build is for permission UI checks.

## UI references

The existing RDS permission, scanner, detection, and first-run views provide
the layout reference. The app adds explicit recovery choices in existing
rows and buttons. The Roborazzi VPN fixture does not show the dialog content,
so its comparison gives no evidence for the system window.
