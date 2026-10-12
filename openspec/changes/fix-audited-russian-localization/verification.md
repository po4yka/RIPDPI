---
task_id: UIX-1791805113916673
change: fix-audited-russian-localization
commit_sha: 229db4f077af5752c52e40755f45cf1f504c5b2c
local: passed
local_evidence: All 2376 app and 2228 service unit tests passed with no failures or skips. Resource regression, locale parity, translation export, lint, static analysis, architecture, file-size, locked Cargo metadata, task contracts, review, and Russian screenshot verification passed on the combined source tree.
remote_ci: required
remote_ci_evidence: null
device: not_applicable
device_evidence: This change requires local API-level resource tests and screenshot verification. A physical-device acceptance matrix is not requested and is not claimed.
artifact: not_applicable
artifact_evidence: No release APK or signed distribution artifact is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-L10N-MEANING | UIX-1791805259890154 | Four resource regression tests passed; the source baseline produces 11 failures. Independent review found no remaining confirmed P1/P2 defects. | Passed |
| REQ-L10N-LOCALE | UIX-1791805277481999 | API 32 tests passed for persisted selection, AndroidX storage restore, system reset, non-Activity strings, and active/paused notifications. | Passed |
| REQ-L10N-COUNTS | UIX-1791805277481999 | Russian 1/2/5/21/22/25 event and notification count tests passed. | Passed |
| REQ-L10N-PRESENTATION | UIX-1791805293603516 | Russian DPI, routing, and signature presenter tests passed, including unknown and user-defined values. | Passed |
| REQ-L10N-PARITY | UIX-1791805309122591 | Locale parity/export, app/service lint, static analysis, all app/service unit tests, and Russian screenshot verification passed. | Passed |

## Local commands and outcomes

- `python3 scripts/ci/test_localization_regressions.py`: 4 passed.
- `scripts/ci/check-locale-parity.sh`: all nine translated locales match the English app, Quick Connect, and service resource keys.
- `scripts/ci/check-translation-export.sh`: the generated translation manifest is current.
- `:app:testGithubFullDebugUnitTest`: 2,376 passed; no failures, errors, or skips.
- `:core:service:testDebugUnitTest`: 2,228 passed; no failures, errors, or skips.
- `staticAnalysis :app:lintGithubFullDebug :core:service:lintDebug`: passed. The static gate also checks the Simple app variant. The new runtime resource files have no lint findings.
- `:app:verifyRoborazziGithubFullDebug --tests com.poyka.ripdpi.ui.screenshot.RussianHomeDisclosureScreenshotTest`: 1 passed, no skips, image unchanged. The rendered 411 dp scene with font scale 1.5 was inspected. Golden fixtures were not recorded or changed.
- `python3 scripts/ci/check_architecture_health.py`: no new or worsened indicators.
- `python3 scripts/ci/check_file_loc_limits.py`: no new violations or baseline growth.
- `cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1`: passed.
- `./taskctl validate` and `git diff --check`: passed.
- Independent source review and structured autoreview: no actionable findings.

All Gradle commands used `build-gate`, `-Pripdpi.skipNativeBuild=true`, `-Pripdpi.nativeCpuBudget=4`, and at most four workers. Screenshot verification also used `-Pripdpi.includeRoborazziUnitTests=true`. The final full app test retry used one worker and `--no-daemon`.

One combined rerun failed before a test body when Robolectric 4.17 opened an existing font ZIP filesystem. Its loader retained a loaded flag and caused native-rendering and coroutine failures in later tests. A fresh full app worker passed all 2,376 tests. No test was excluded and native rendering remained enabled.

## Scope and evidence limits

Hosted CI is separate from local gates. Report the pushed SHA and actual hosted status after push. Do not claim device or release acceptance from Robolectric or screenshots.
