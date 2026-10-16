---
task_id: UIX-1792130566603442
change: ui-stage-two
commit_sha: a53b50b4747024db40918e3223f524c76149d65c
local: passed
local_evidence: Source 234e1b7d7 passed 201 focused JVM tests, staticAnalysis, app Full lint, service lint and real Full app/test APK assembly. Combined source a53b50b47 passed all sixteen affected screenshot tests without failures, errors or skips. See the commands and artifact paths below.
remote_ci: required
remote_ci_evidence: Hosted CI for the integrated revision is required after push; https://github.com/po4yka/RIPDPI/actions?query=branch%3Amain
device: passed
device_evidence: Five instrumentation tests passed at 234e1b7d7. Nine Appium tests passed with harness d9da3f36, unchanged APKs, and zero failures, errors or skips in 116.21 seconds. The fresh light/dark, 360dp, 1x/2x-font matrix and live-event log flow passed; see the device evidence below.
artifact: passed
artifact_evidence: The ten user-approved PNGs passed narrow record and verification, nine methods each. Combined-tree Full verification passed sixteen methods with zero failures, errors or skips at a53b50b47 in 2 minutes 47 seconds.
deployment: not_applicable
deployment_evidence: This app-only change owns no deployment.
---

# Verification

## Local source evidence

The implementation was checked at `234e1b7d79ba838c2df188ad24583b5e4e867d01`.
The combined command passed on 2026-10-16 in 5 minutes 25 seconds:

```sh
build-gate -- env -u CARGO_BUILD_JOBS -u RIPDPI_BLESS_GOLDENS \
  RUSTC_WRAPPER=sccache CMAKE_BUILD_PARALLEL_LEVEL=4 \
  ANDROID_HOME=/Users/npochaev/Library/Android/sdk \
  ./gradlew --max-workers=4 -Pripdpi.nativeCpuBudget=4 \
  -Pripdpi.localNativeAbis=arm64-v8a \
  -Pripdpi.prebuiltXrayAarDir=/Users/npochaev/GitHub/RIPDPI/native/xray/artifacts \
  :app:testGithubFullDebugUnitTest \
  --tests 'com.poyka.ripdpi.ui.screens.config.ConfigScreenTest' \
  --tests 'com.poyka.ripdpi.ui.screens.config.ConfigConnectionSummaryTest' \
  --tests 'com.poyka.ripdpi.ui.screens.config.ConfigRuntimeErrorVisibilityTest' \
  --tests 'com.poyka.ripdpi.ui.screens.config.ConfigEndpointRtlTest' \
  --tests 'com.poyka.ripdpi.activities.ConfigRuntimeActionsTest' \
  --tests 'com.poyka.ripdpi.ui.screens.logs.*' \
  --tests 'com.poyka.ripdpi.activities.LogsViewModel*Test' \
  --tests 'com.poyka.ripdpi.ui.screens.diagnostics.DiagnosticsOutcomeTest' \
  --tests 'com.poyka.ripdpi.activities.DiagnosticsOutcomeProjectionTest' \
  --tests 'com.poyka.ripdpi.ui.screens.diagnostics.DiagnosticsScreenTest' \
  --tests 'com.poyka.ripdpi.ui.screens.diagnostics.DiagnosticsGuidedActionsTest' \
  --tests 'com.poyka.ripdpi.ui.screens.diagnostics.DiagnosticsWarningPresentationTest' \
  --tests 'com.poyka.ripdpi.activities.MainHomeDiagnosticsUiStateTest' \
  --tests 'com.poyka.ripdpi.activities.DiagnosticsUiStateFactoryTest' \
  --tests 'com.poyka.ripdpi.ui.theme.*' \
  staticAnalysis :app:lintGithubFullDebug :core:service:lintDebug \
  :app:assembleGithubFullDebug :app:assembleGithubFullDebugAndroidTest --continue
```

The implementation and APK sources are unchanged in the integration tree.
Only approved PNGs, the Home scrolling test helper and task records were added.

The exact filters and command are in
`/tmp/ui-stage-two-final-234e1b7d79ba838c2df188ad24583b5e4e867d01.sh`.
The log is `/tmp/ui-stage-two-combined-gates.log`.
Preserved JUnit reports and the source SHA are under
`build/reports/ui-stage-two/combined/` in the stage-two job worktree.
The reports contain 201 tests with zero failures, errors or skips.
They cover config controls, saved-mode versus settings navigation, stop mode,
profile disclosure, diagnostic provenance and actions, log layout, empty-state
large-font text, append/history retention, strict live-edge behavior and themes.

Architecture health passed with 21 current/baseline indicators, zero new or
worsened entries. Locked Cargo metadata passed with 116 workspace members.
Guide tests passed (13); Python compilation, 31 YAML parses and collection of
the nine selected Appium tests passed. Task contracts and strict OpenSpec passed.

Reproducing failure artifacts are preserved in
`build/reports/ui-stage-two/red/`: connection, stop mode, diagnostic duplicate
headlines, narrow logs layout and the 8-pixel near-bottom scroll case. The last
case moved an existing row from 580 to 515 pixels before the fix.

Independent final review of the combined source found no actionable defects.
No service, JNI, Rust, protobuf or dependency contract changed. Lint and
architecture baselines are unchanged.

## Evidence boundaries

The Full app and test APKs contain native outputs built from source:

- App SHA-256: `aa818cee95bddbaa9054f84d1e84bccb7d9c565453910425f8e236d5d3b72758`.
- Test SHA-256: `f7dbd348c73be5921661feab768d750a350ae26c4ea505c2cb99c636c9e88bb4`.

The Simple screenshot lane is blocked before tests because this checkout has
no nonempty `app/src/simple/assets/embedded-relay-bundle.json`. No substitute
asset was created. Full verification does not prove Simple verification.
User comprehension, TalkBack speech, physical-device behavior and release
performance remain unverified. Keep hosted CI separate from local checks.
Do not archive while required evidence is open.


## Device and visual evidence

The leased device was API 37, arm64-v8a, google_apis, Pixel 10 Pro XL.
The five `UiStageOneInstrumentedTest` tests passed with source `234e1b7d7` in
`/tmp/ripdpi-ui-stage-two-device/20261016T091958Z/instrumentation.txt`.
The final Appium and visual run used harness
`d9da3f36ae6a06e7fa66a587b03e8fec82729756` and the same APK hashes listed above.
It passed all nine selected tests in 116.21 seconds, with zero failures, errors
or skips. Its JUnit, command, test hashes, launch records, PNG/XML pairs,
restoration checks and final error-free metadata are in:
`/tmp/ripdpi-ui-stage-two-device/20261016T093330Z/`.
The consolidated report is `build/reports/ui-stage-two/device/final-report.md`
in the job worktree.

The matrix checks Config profile disclosure and Connect, the diagnostic action,
and Logs filters/actions in light and dark at 360x640dp and font scales 1 and 2.
All twelve initial screen cases passed. All four theme/font combinations
reached the profile, connection, diagnostic and modal controls. The capture
manifest has 222 successful fresh dumps with zero busy retries, 66 stable
captures and 64 unique PNG/XML pairs. The complete button targets are above
navigation before each interaction.
Empty-log guidance remains complete at font scale 2. Each capture removes its
own remote XML first, requires a fresh successful dump, and checks matching
screen and control bounds on both sides of the PNG capture.

The separate live-service flow starts and stops the real local proxy. The log
stream contains new `android_network_callback` events with increasing sequence
numbers. The populated feed PNG/XML and extracted rows are in the same run.
The visible live buffer has twenty real entries. Native logcat separately
shows the local proxy listener start at 13:44:46.505 and stop at 13:45:53.978,
then lifecycle STOPPED at 13:45:54.017. The crash buffer is empty.
This proves that real events reach the UI. No client traffic or external network
acceptance is claimed. The test data and permission presets used by the other
scenarios are identified in their metadata.

All inspected profile, action, empty-log and modal captures are readable.
The user-approved golden specialist also inspected all ten PNG changes,
including Arabic, Persian, Chinese, both live-log themes and maximum-font
navigation. Narrow record and verify each passed nine methods without skips.
Their commands, JUnit, logs and SHA-256 registry are in
`/Users/npochaev/GitHub/RIPDPI-ui-stage-two-goldens/build/reports/ui-stage-two-goldens/`.
Only those ten approved PNGs changed.

The final wrapper exited 0 and released the pool lease. App locale `[ru]`,
physical size 1344x2992, density 480 and font scale 1.0 were restored. The live
service was stopped. Earlier failed attempts remain preserved separately and
are not counted as passes: the guest-shell timeout and the old Home helper
failure were corrected before the final run. Stale XML/PNG pairs from the first
matrix attempt are excluded from acceptance evidence.

## Requirement evidence

| Requirement | Execution step | Observed evidence | Result |
|---|---|---|---|
| REQ-UI2-CONNECTION | UIX-1792130696967335 | Saved-mode and stop-mode unit tests; final Appium test26 | Passed |
| REQ-UI2-PROFILE | UIX-1792130696967335 | Profile/disclosure unit tests; complete large-font emulator targets | Passed |
| REQ-UI2-DIAGNOSTICS | UIX-1792130697552157 | Mixed-source, stale, partial and active outcome tests; low-confidence review | Passed |
| REQ-UI2-DISCLOSURE | UIX-1792130697552157 | Persona label/callback unit tests; final Appium test29 | Passed |
| REQ-UI2-LOGS | UIX-1792130698111801 | Layout/append/history unit tests; final Appium tests15/31; live-event feed | Passed |
| REQ-UI2-ACCESS | UIX-1792130698718422 | Semantics, locale lint, theme tests and static analysis | Passed |
| REQ-UI2-ACCESS | UIX-1792130699357356 | Rebuilt-APK light/dark, 360dp and 2x-font inspection | Passed |


## Combined-tree screenshot check

After the approved fixture commit and rebase on `origin/main`, the affected
family passed 16/16 at `a53b50b4747024db40918e3223f524c76149d65c` in
2 minutes 47 seconds, with zero failures, errors or skips. The command did not
record or change fixtures. Its source SHA and copied JUnit reports are in
`build/reports/ui-stage-two/roborazzi-approved/`; its exact script and log are
`/tmp/ui-stage-two-verify-reviewed-goldens.sh` and
`/tmp/ui-stage-two-reviewed-goldens-verify.log`.

```sh
build-gate -- env -u CARGO_BUILD_JOBS -u RIPDPI_BLESS_GOLDENS \
  RUSTC_WRAPPER=sccache CMAKE_BUILD_PARALLEL_LEVEL=4 \
  ANDROID_HOME=/Users/npochaev/Library/Android/sdk \
  ./gradlew --max-workers=4 -Pripdpi.nativeCpuBudget=4 \
  -Pripdpi.localNativeAbis=arm64-v8a \
  -Pripdpi.prebuiltXrayAarDir=/Users/npochaev/GitHub/RIPDPI/native/xray/artifacts \
  -Pripdpi.skipNativeBuild=true -Pripdpi.includeRoborazziUnitTests=true \
  :app:verifyRoborazziGithubFullDebug \
  --tests '*.RipDpiScreenCatalogScreenshotTest.config*' \
  --tests '*.RipDpiScreenCatalogScreenshotTest.logs*' \
  --tests '*.RipDpiScreenCatalogScreenshotTest.diagnostics*' \
  --tests '*.BusyStateScreenshotTest.logsStreamLive' \
  --tests '*.MaximumFontNavigationShellScreenshotTest.diagnosticsAtPixel7MaximumFont' \
  --tests '*.locale.*LocaleScreenshotTest.config' \
  --tests '*.locale.*LocaleScreenshotTest.diagnostics' \
  --tests '*.ConfigSetupHealthScreenshotTest.vpnConfigPopulatedAdvanced'
```

The renderer uses the supported skip-native mode. Device acceptance used the
real native APKs built above. Architecture health and locked Cargo metadata
were checked again on the combined tree and passed. The source diff against
`234e1b7d7` is empty for app main/test/Android-test sources, core, native,
build logic and Gradle configuration. Later task-record changes do not change
the validated UI or fixtures.
