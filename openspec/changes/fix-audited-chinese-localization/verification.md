---
task_id: UIX-1791818839787311
change: fix-audited-chinese-localization
commit_sha: 3a88439409e1aab6517b96b9e5e4bda72e8b06b2
local: passed
local_evidence: All 2393 app and 2228 service unit tests passed without failures or skips. Resource tests, locale parity, export, lint, static analysis, architecture, file-size, locked Cargo metadata, source review, and five Chinese screenshot tests passed.
remote_ci: required
remote_ci_evidence: null
device: not_applicable
device_evidence: Local resource and native-rendered Compose tests cover this change. Physical-device acceptance is not claimed.
artifact: not_applicable
artifact_evidence: No release APK or signed artifact is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-ZH-MEANING | UIX-1791818937149291 | Five Chinese resource tests, safety/action references, and five Chinese screenshots | Passed |
| REQ-ZH-RESULTS | UIX-1791818939625586 | Chinese and English tool, RKN, history, unknown-value, and native Compose caption tests | Passed |
| REQ-ZH-DATES | UIX-1791818942950140 | Date locale switching, retained state, and unchanged policy keys/timestamps | Passed |
| REQ-ZH-PARITY | UIX-1791818944914900 | Ten-locale resource/type/argument contracts, export, lint, and static analysis | Passed |

## Scope

Corrected 116 active Chinese entries, one dormant redaction message, and the Quick Connect tile label. Added 57 dynamic presentation keys in all ten locales. Known tool results, errors, RKN conclusions, history states, captions, and saved dates use app-localized presentation. Protocol identifiers, custom values, raw evidence, and stored timestamps remain unchanged.

No Rust, JNI, protobuf, storage schema, network behavior, dependency, baseline, or golden fixture change.

## Local commands and results

- `python3 scripts/ci/test_chinese_localization_regressions.py`: five passed; 116 failures against the audit baseline.
- `python3 scripts/ci/test_localization_regressions.py`: four passed.
- `scripts/ci/check-locale-parity.sh`: all ten locales match the app, Quick Connect, and service source sets.
- `scripts/ci/check-translation-export.sh`: passed; 4894 source string keys.
- Targeted Kotlin behavior tests: 26 passed, no failures or skips. Baseline tool tests produced seven translation assertion failures and one test-fixture selection error; the fixture assertion was corrected without changing production behavior.
- `:app:testGithubFullDebugUnitTest`: all 2393 passed, no failures, errors, or skips.
- `:core:service:testDebugUnitTest`: all 2228 passed, no failures, errors, or skips. The unchanged service test task reused Gradle cache outputs.
- `staticAnalysis :app:lintGithubFullDebug :core:service:lintDebug`: passed. Static analysis also checks the Simple app variant.
- `:app:verifyRoborazziGithubFullDebug --tests com.poyka.ripdpi.ui.screenshot.locale.SimplifiedChineseLocaleScreenshotTest`: five passed; all images unchanged. Actual images were inspected for glyphs, wrapping, and clipping. The scenes cover primary-screen previews, not every app state.
- `python3 scripts/ci/check_architecture_health.py`: no new, worsened, or stale indicators.
- `python3 scripts/ci/check_file_loc_limits.py`: no violations or baseline growth.
- `cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1`: passed.
- `./taskctl validate`, `git diff --check`, independent source review, and structured autoreview: passed.

All Gradle invocations used `build-gate`, `-Pripdpi.skipNativeBuild=true`, `-Pripdpi.nativeCpuBudget=4`, `--max-workers=1`, and `--no-daemon`. Screenshot verification also used `-Pripdpi.includeRoborazziUnitTests=true`. No native build or release artifact acceptance is claimed.

## Test environment issue

Combined reruns failed before a test body while Robolectric 4.17 opened an existing font ZIP filesystem. Its loader sets a loaded flag before extraction and does not reset that flag after this unchecked failure. The first failure caused 360 native-rendering and 196 coroutine failures. The owner of the open ZIP was not established.

Fresh standalone full app runs passed all 2393 tests. The final combined gate passed on the committed source SHA after fetch and rebase against origin/main. Native rendering remained enabled; no tests, assertions, or quality gates were removed. No test-process isolation override, cache removal, or production harness change was needed.

## Hosted CI boundary

The baseline main commit 76169878cae9b947e58140d2a2d788dbaf7466bc has a failed CI run at https://github.com/po4yka/RIPDPI/actions/runs/38255858878. Its cargo-deny job rejects unsafe-libyaml as unmaintained under RUSTSEC-2024-0449. This change does not modify Rust dependencies or advisory policy.

Check the exact pushed head after publication. Local gates are separate from hosted CI. Keep the task in review until required hosted evidence is resolved. Native-speaker review and physical-device acceptance are not claimed.
