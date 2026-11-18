---
task_id: AND-1795015211660121
change: preload-preset-remove-simple-flavor
commit_sha: 6b1924898
local: blocked
local_evidence: Focused suites pass on 6b1924898 (PresetApplierTest 6, AppStartupInitializerTest 14, ImportIntentFilterTest 14, VpnServiceSessionLifecycleTest 9, BackupEncryptionTest 5); build-logic tests pass; verifyRoborazziGithubDebug with the screenshot filter passes 313 tests; ktlint and Android lint pass for app and core service; all eleven ci-gates scripts exit 0. The full app unit run has 23 failures in ProfileUtilityViewModelTest and ProfileUtilityCatalogRecoveryTest that come from main commit 8bce25db4, and app detekt reports two magic numbers in the untouched BackupRestoreScreen.kt, so a clean full local run is blocked on main.
remote_ci: required
remote_ci_evidence: null
device: passed
device_evidence: Pool emulator RIPDPI_Pool_2, API 35 arm64-v8a, githubDebug built with localNativeAbis arm64-v8a. Fresh install without a preset opens onboarding. Fresh install with a test preset logs "Packaged preset applied" and opens Home with no restart. Connected instrumentation suites were not run locally.
artifact: passed
artifact_evidence: app/build/reports/app-identity/release-identity.json lists three release variants with application ID com.poyka.ripdpi; the preset APK contains assets/preset.json and the other APK does not.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-PRESET-APPLY | AND-1795015493323024 | `PresetApplierTest` reads restored groups, rules, and settings through production repositories without a restart; API 35 arm64 emulator, fresh install of a preset APK: log `Packaged preset applied`, first screen is Home | passed |
| REQ-PRESET-ONCE | AND-1795015493323024 | `PresetApplierTest`: second apply skipped and a deleted group stays deleted; completed onboarding skipped; reset through `DefaultUserArtifactResetStore` re-applies | passed |
| REQ-PRESET-ABSENT | AND-1795015493323024 | `PresetApplierTest` no-asset case; API 35 arm64 emulator, fresh install of an APK without the asset: `preset_apply=succeeded`, first screen is onboarding; unit, screenshot, and lint runs used no asset or fixture | passed |
| REQ-PRESET-INVALID | AND-1795015493323024 | `PresetApplierTest`: unsupported version and unreadable text write nothing, and a later valid preset applies. The interrupted-launch scenario relies on the staged, journaled restore and has no dedicated test | passed |
| REQ-PRESET-CONFIDENTIAL | AND-1795015493323024 | `git check-ignore app/src/main/assets/preset.json` matches; `PresetOutcome.Rejected` carries a type or class name only; emulator logcat shows the outcome line only | passed |
| REQ-PRESET-PREPARE | AND-1795015495231718 | `./gradlew -p build-logic test`: `PreparePresetTaskTest` (valid export, wrong and missing passphrase, SHARE export, unsupported version) and `BackupEncryptionTest` on the shared fixture | passed |
| REQ-PRESET-SINGLE-EXPERIENCE | AND-1795015496438291 | `:app:writeReleaseIdentityManifest` lists `fdroidRelease`, `githubRelease`, `playRelease`, all `com.poyka.ripdpi`; `check_app_identity_review.py` PASS; `ImportIntentFilterTest` (14) and `AppExperienceContractTest` (2) | passed |
| REQ-RACE-PRODUCT-SCOPE | AND-1795015499321399 | Delta spec only; no code names the Simple preflight after `git grep` | passed |
| REQ-RACE-SCOPE | AND-1795015499321399 | Removed by the delta spec with the Simple flavor | passed |

## Review

`pr-reviewer` reviewed `origin/main...a37c45156`. It found the manifest merge and the service gate removal clean and reported eleven minor findings on the preset path; `6b1924898` fixes the ones that change behavior. It did not finish the CI, script, and leftover-reference areas. Those were checked by hand with repository-wide searches and the script test suites, not by an independent reviewer.

## Open items

- Remote CI has not run. The instrumented job keeps `--minimum-total-count 54` after one Simple-only instrumented test was deleted; the count is unverified.
- An install that has not finished onboarding, has data, and then updates to a preset build has that data replaced. The specification accepts this.
- `AND-1795015500558064` stays open until remote CI reports.
