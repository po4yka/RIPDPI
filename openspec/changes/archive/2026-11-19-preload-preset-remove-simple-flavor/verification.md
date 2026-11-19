---
task_id: AND-1795015211660121
change: preload-preset-remove-simple-flavor
commit_sha: fd00bf4939538fb3fff5e391ff802b42979a2caf
local: passed
local_evidence: Focused suites pass on 6b1924898 (PresetApplierTest 6, AppStartupInitializerTest 14, ImportIntentFilterTest 14, VpnServiceSessionLifecycleTest 9, BackupEncryptionTest 5); build-logic tests pass; verifyRoborazziGithubDebug with the screenshot filter passes 313 tests; ktlint and Android lint pass for app and core service; all eleven ci-gates scripts exit 0. On main fd00bf493, which contains this change, `./gradlew :app:testGithubDebugUnitTest :app:detekt` passes with 3764 tests and 0 failures; the earlier main-side failures in ProfileUtility tests and BackupRestoreScreen.kt detekt are gone.
remote_ci: passed
remote_ci_evidence: CI run 44369551319 on main 885a098d1, which contains this change, concluded success with ci-required green - kotlin-coverage, gradle-static-analysis, verify-roborazzi, debug builds and release-verification for fdroid, github, and play, release-gates, and android-instrumented-tests on API 27, 33, 35, 36, and 37. The change route skipped android-ui-journeys and the Rust lanes for that commit.
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
| REQ-PRESET-INVALID | AND-1795015493323024 | `PresetApplierTest`: unsupported version and unreadable text write nothing, and a later valid preset applies; `AppStartupInitializerFailureTest` shows a rejected preset as a failed `preset_apply` entry whose message omits the document, with readiness reaching Ready. The interrupted-launch scenario relies on the staged, journaled restore and has no dedicated test | passed |
| REQ-PRESET-CONFIDENTIAL | AND-1795015493323024 | `git check-ignore app/src/main/assets/preset.json` matches; `PresetOutcome.Rejected` carries a type or class name only; emulator logcat shows the outcome line only | passed |
| REQ-PRESET-PREPARE | AND-1795015495231718 | `./gradlew -p build-logic test`: `PreparePresetTaskTest` (valid export, wrong and missing passphrase, SHARE export, unsupported version) and `BackupEncryptionTest` on the shared fixture | passed |
| REQ-PRESET-SINGLE-EXPERIENCE | AND-1795015496438291 | `:app:writeReleaseIdentityManifest` lists `fdroidRelease`, `githubRelease`, `playRelease`, all `com.poyka.ripdpi`; `check_app_identity_review.py` PASS; `ImportIntentFilterTest` (14) and `AppExperienceContractTest` (2) | passed |
| REQ-RACE-PRODUCT-SCOPE | AND-1795015499321399 | Delta spec only; no code names the Simple preflight after `git grep` | passed |
| REQ-RACE-SCOPE | AND-1795015499321399 | Removed by the delta spec with the Simple flavor | passed |
| REQ-UIX-1786264762917972-004 | AND-1795015499321399 | Delta spec renames the Gradle task in the criterion to `:app:testGithubDebugUnitTest`; the task exists and ran in the unit-test evidence above | passed |

## Review

Four `pr-reviewer` passes covered `origin/main...HEAD`, limited to this change.

- Preset path, manifest merge, service gate removal: clean apart from minor findings on the preset path, fixed in `6b1924898`.
- Kotlin and Gradle: the `MainNavHost` extraction passes the same arguments as the deleted seam; the build file, the lint-property rename, and the tests that lost a flavor branch are clean. One gap found: no test for a rejected preset at startup. The test is added.
- CI and scripts: both matrix `exclude` lists equal their shards; artifact names and paths are consistent; `--minimum-total-count 54` stays satisfiable, because the validator counts skipped tests and the integration package has 100 test methods.
- Leftovers: one stale skill line and one deleted path in a screenshot input list, both fixed.

Areas the reviewers did not reach were checked by script: no string in `app/src/main/res` lost its last reference, every renamed screenshot baseline maps to a test in its class file, and `core/service` has no reference to the removed parameters.

## Open items

- An install that has not finished onboarding, has data, and then updates to a preset build has that data replaced. The specification accepts this.
