---
task_id: UIX-1791793531264679
change: material3-readability
commit_sha: cd3e418103b8b2b68aa3b521aa5bbde07ebd2d9b
local: passed
local_evidence: The rebased 1283-test UI and activity selection passed without failures, errors, or skips. The final no-cache gate passed app/service lint and complete static analysis. The real ARM64 APK build passed without skipNativeBuild.
remote_ci: passed
remote_ci_evidence: Manual full CI run 40831691401 at c9d5727ef, which contains this change, passed all 50 executed jobs, including verify-roborazzi, gradle-static-analysis, kotlin-coverage, design-md-lint, Full/Play/F-Droid debug builds, and instrumented tests on API 27, 33, 35, 36, and 37.
device: passed
device_evidence: Final APK cd3e41810 passed 88 reviewed captures on the leased API 37 ARM64 emulator at 411dp, 420dpi, and 200% font. Both themes and same-process EN to RU permission text passed. Installed bytes match the retained APK.
artifact: passed
artifact_evidence: All 33 changed or new fixtures were reviewed. Strict whole-family verification passed Full 298 cases and Simple 310 cases without failures or errors, with 30 existing variant assumptions. Exact tested source, test, and screenshot trees match the integrated fixture commit.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-UI-READABLE-TYPE | UIX-1791793632932195 | RipDpiReadabilityTokensTest, OnboardingOptionCardTest, and 12 fresh UI renders | Passed |
| REQ-UI-SETTINGS-LAYOUT | UIX-1791793633865680 | SettingsRowTest with long Russian and RTL values, leading icons, and chevrons at 320/420dp and 2x font | Passed |
| REQ-UI-DIAGNOSTICS-TABS | UIX-1791793634763649 | DiagnosticsSectionSwitcherTest: callbacks, selection, RTL, font scale, and text overflow; fresh renders | Passed |
| REQ-UI-PRESERVE-CONTRACTS | UIX-1791793635678588 | App/service lint, 447 UI/design-system tests, 12 renders, reviewed goldens, and final diff | Passed |
| REQ-UI-SHARED-ACTIONS | UIX-1791804419498505 | SharedReadabilityLayoutTest, FeedbackWordLayoutTest, BottomNavBarTest, RipDpiTopAppBarTest, and fresh light/dark/2x renders | Passed |
| REQ-UI-ADAPTIVE-CHOICES | UIX-1791804426740078 | ReadabilityFormsLayoutTest and ReadabilityFormActionsLayoutTest with actual card gutters, RTL, and full labels; DNS/relay renders | Passed |
| REQ-UI-DATA-LAYOUT | UIX-1791804434249424 | ReadabilityDataScreenTest with native density 420dpi, complete diagram nodes, RTL tick positions, and 15 data renders | Passed |
| REQ-UI-INSTALLED-EMULATOR | UIX-1791877120798384 | Installed API 37 emulator, both themes, 200% font; 88 reviewed captures, source/hash receipt, and same-process locale check | Passed |

The app-wide combined review and visual checks are owned by UIX-1791804441168759. The first-stage results below are historical evidence at the stated commits. The final app-wide evidence follows them.

## First-stage local commands

The combined UI gate passed at source commit `d50c7ea6c649df961cd9cbb0b01142765967014e`:

```sh
build-gate -- ./gradlew :app:testGithubFullDebugUnitTest \
  --tests 'com.poyka.ripdpi.ui.theme.*' \
  --tests 'com.poyka.ripdpi.ui.components.*' \
  --tests 'com.poyka.ripdpi.ui.navigation.*' \
  --tests 'com.poyka.ripdpi.ui.screens.home.*' \
  --tests 'com.poyka.ripdpi.ui.screens.diagnostics.*' \
  --tests 'com.poyka.ripdpi.ui.screens.profiles.*' \
  --tests 'com.poyka.ripdpi.ui.screens.onboarding.OnboardingOptionCardTest' \
  --tests 'com.poyka.ripdpi.ui.screens.onboarding.OnboardingScreenTest' \
  --tests 'com.poyka.ripdpi.ui.DesignSystemSourceRulesTest' \
  :app:lintGithubFullDebug :core:service:lintDebug :app:detekt :app:ktlintCheck --max-workers=4
```

The final fixture commit has the same production, debug, and test sources. Both screenshot variants passed with their existing flavor assumptions:

```sh
build-gate -- ./gradlew :app:verifyRoborazziGithubFullDebug :app:verifyRoborazziGithubSimpleDebug \
  -Pripdpi.skipNativeBuild=true -Pripdpi.includeRoborazziUnitTests=true \
  --tests 'com.poyka.ripdpi.ui.screenshot.*' --max-workers=4
```

The native-build flag is the repository CI setup for JVM screenshots. No APK was built with this flag. The Simple tests used the committed CI relay fixture as input; this does not prove a live network connection. The visual artifacts were rendered with:

```sh
build-gate -- ./gradlew :app:composePreviewRenderAll -PcomposePreview.filter=Material3Readability --max-workers=4
```

The source tree review covers the initial implementation and the text changes after the first fixture commit. The automated helper cannot read binary PNG changes; golden-blesser reviewed all affected image hashes separately. The final verified app tree is `1372b7b37c9c16640db4970b91879e9ac3abd192`; its screenshot tree is `3d30039998c0b3df5199cc0d23e2519bd2d8de26`.

## Final app-wide source checks

The final source commit is `c9882de66417d4670042087d2d26d8c5e4b85c95`, based on `5ea9c492c0ba1d27cf5223642c1aa36a044aa3d5`. Russian and Chinese localization changes from main remain intact.

```sh
build-gate -- ./gradlew :app:testGithubFullDebugUnitTest \
  --tests 'com.poyka.ripdpi.ui.*' \
  :app:lintGithubFullDebug :core:service:lintDebug \
  :app:detekt :app:ktlintCheck \
  -Pripdpi.skipNativeBuild=true --max-workers=4
```

This final combined gate passed 1149 tests in 196 classes, with no failures, errors, or skips. It passed in 2m 31s. It covers the whole UI package, including the new Chinese caption test and the actual settings scaffold. The four screenshot canvas height changes were tested separately by both strict screenshot variants below.

The earlier complete app run at `c011e736f9b2c11dfee28cea768aaa39a94fb875` passed 2425 tests in 388 classes, with no failures, errors, or skips. Its first shared-process attempt failed during Robolectric font archive initialization, before the test body. The loader then left native bindings unavailable. A temporary Gradle init script set `forkEvery = 1` and `maxParallelForks = 1`; no test, SDK, graphics mode, or assertion was removed. The complete retry took 21m 45s and also passed app/service lint, detekt, and ktlint. This source predates the Chinese rebase and final app-bar correction; it is not a claim of a second complete app run at the final commit.

```sh
build-gate -- ./gradlew \
  --init-script /tmp/readability-test-isolation.gradle \
  :app:testGithubFullDebugUnitTest \
  :app:lintGithubFullDebug :core:service:lintDebug \
  :app:detekt :app:ktlintCheck \
  -Pripdpi.skipNativeBuild=true --max-workers=4
```

Independent source reviews covered all three implementation lanes and the visual corrections. The final app-bar regression reproduced a 56dp empty row in both the direct component and the settings scaffold. All six app-bar/scaffold tests passed after the fix. P1/P2 review and the default P0/secret scan found no actionable issue. The previous source review and each scoped follow-up review remain in the local reports.

`python3 scripts/ci/check_architecture_health.py` passed with 21 current indicators, zero new or worsened entries, and zero stale entries. Locked Cargo metadata contains 116 workspace members. `npx --yes @google/design.md lint DESIGN.md` passed with zero errors and warnings. Task contracts passed for 126 tasks and 353 steps.

## Final visual and fixture checks

```sh
build-gate -- ./gradlew :app:composePreviewRenderAll \
  -PcomposePreview.filter=ReadabilityAudit --max-workers=4
```

All 33 fresh renders passed at the final source commit. Six shared, twelve form, and fifteen data captures cover light, dark, and 200% font layouts. Each final PNG has the same SHA-256 as the previously inspected image. The review covered full labels, word wrapping, disabled/selected controls, spacing, graph legends, and native glyph bounds. Width tests cover 320/420dp and LTR/RTL. `RipDpiThemeLocaleTest` covers resource language changes from Russian to English and Arabic, including Russian hyphenation. Device and TalkBack runs are not claimed.

The user authorized the affected `app/src/test/screenshots` family. Golden-blesser reviewed all 271 changed PNGs; 125 stayed unchanged. The manifest covers each changed hash and its RGB diff. Exactly 18 dimension changes are allowed: ten bottom-navigation captures and eight diagnostic captures. The latter use only four revised canvas heights, 840/580/784/640dp, so all timeline stages, state legends, import entries, and graph captions are visible. Widths and sample data remain unchanged. Compose preview images were not copied into fixtures.

The final strict gate passed after all fixture and canvas changes:

```sh
build-gate -- ./gradlew :app:verifyRoborazziGithubFullDebug \
  :app:verifyRoborazziGithubSimpleDebug \
  -Pripdpi.skipNativeBuild=true -Pripdpi.includeRoborazziUnitTests=true \
  --tests 'com.poyka.ripdpi.ui.screenshot.*' --max-workers=4
```

Full passed 286 cases in 55 classes with one existing flavor assumption skip. Simple passed 298 cases in 57 classes with 29 existing flavor assumption skips. There were no failures or errors; 554 cases executed. The gate took 1m 45s. Owning compare tasks produced both themes before recording; compare output alone was not used as proof of a strict pass.

Fixture commit `99abf89533a76c9fd974d771bd68d5f6e471bfd5` has the exact tested app tree `668b275733436319334625d089914cae9135be34` and screenshot tree `f461abe6651068ed1511bd5abf43c2e167f1343d`. Its production and debug trees match the final source commit. The complete fixture review manifest SHA-256 is `a8d53de868f375f6e6115f4aef7f0623e559b468cf0cf7be21f4d13790a2c97d`. The integration owner rechecked all 271 fixture hashes, review flags, dimensions, and tree identities.

## Retained local reports

These reports were recorded in the isolated readability worktree under `build/reports/readability-audit`. A separate host cleanup removed the disposable build directories on 2026-10-13. The following list is a historical record, not a claim that these files remain available:

- `gallery.md` and `captures-final.json`: all 33 final images and source/hash metadata.
- `final-rebased-ui-tests`: final UI XML, totals, and combined gate log.
- `final-full-app-tests` and `full-app-first-run`: complete isolated pass and the original host-loader failure.
- `appbar-followup`: the empty-row RED regression, six-test pass, and review receipts.
- `goldens-final`: strict variant counts, review manifest, fixture receipt, and parent hash acceptance.
- `architecture-rebased.log`, `cargo-metadata-rebased.json`, and `design-lint-final.log`: final structural and design checks.

Original expected, actual, and diff PNGs were recorded in the isolated golden worktree under `build/reports/readability-appwide-goldens`; the same cleanup removed them. The RDS HTML deck, runtime contracts, locale sets, and native sources were not edited by that earlier UI pass. Main integration is authorized. Push and remote CI are not owned by this local task.

Current emulator follow-up evidence is stored outside the repository build directories at `/Users/npochaev/.codex/visualizations/2026/10/12/01a12f2d-479e-7a20-af97-5645507716e9/emulator-20261013`. Final installed acceptance and its source identities are recorded below.


## Installed follow-up source checkpoint

Source checkpoint `d226169bbb42f4cea4fb09278706dbd3f05a7225` contains seven scoped fixes. The first UI run executed 1259 tests: 1258 passed and one launcher contrast assertion failed because its crop excluded the visible preview edges. The corrected viewport test passed with no failures, errors, or skips. The retry also passed app/service lint and complete static analysis. These are separate observed runs; the final rebased combined-tree gate is recorded below.

A real ARM64 debug APK built without `skipNativeBuild` in 33m 21s. Its SHA-256 is `e3511cb1189726db220221fa350ef7fb1071d6193a7731f4909e3e6ccd33cee3`, with 744042069 bytes. The immutable APK and `apk-d226-receipt.json` are in the retained emulator evidence directory. This intermediate APK establishes the native build; final acceptance must use the rebased source and final captions.


## Rebased installed source

The final source checkpoint is `cd3e418103b8b2b68aa3b521aa5bbde07ebd2d9b`, based on main `ddd0024d494fb2e734223b8c6e77ec239a255bbe`. The theme-array conflict retained the upstream `settings_option_theme_*` resources. Their ten translations equal the follow-up captions. The duplicate follow-up resource files were removed; the ten-locale stable-key test remains. The task board was regenerated through `taskctl`.

The three final layout commits retain full launcher silhouettes, stack confidence/winner/candidate headers when needed, and wrap history filters inside the card. The nine affected source and test files have the same SHA-256 before and after this rebase. The preceding combined gate passed 1252 tests in 210 classes, without failures, errors, or skips, plus app/service lint and complete static analysis.

The rebased shared-process gate executed 1283 tests in 216 classes. Its first failure occurred before the MainActivity test body: `FileSystemAlreadyExistsException` in `DefaultNativeRuntimeLoader.maybeCopyFonts`. That loader failure caused 631 failed cases, including later RenderNode linkage and coroutine errors. The complete XML results are retained under `rebased-native-font-failure`. The isolated run used the same test selection, SDK, graphics mode, and assertions, with one class per JVM and one JVM at a time. All 1283 tests passed in 216 classes, with zero failures, errors, or skips. After 35m 43s, Gradle failed while storing a build-cache entry because its temporary `.part` file disappeared. The cause is unknown. A separate no-cache gate passed in 16 seconds and reused the identical up-to-date test results. App/service lint and complete static analysis passed. Both the successful XML and the earlier infrastructure failures are retained separately; no test was removed or weakened.

The first native build compiled the JNI libraries and all three repository helpers, but APK packaging failed with a generic `IncrementalSplitterRunnable` error. A separate real `assembleGithubFullDebug --stacktrace` retry passed in 21 seconds, with no source change and without `skipNativeBuild`. The initial packaging cause was not exposed by that failed log; the successful retry does not establish its cause.

The retained final APK has 286879785 bytes and SHA-256 `dc88d8a740cb6a712a55f10e0c482c970ef0277f683a0c028665c30012ab79c6`. Its production tree is `adbe6bacf9cb3656b0b2f6429d501a81845f5e1d`; its debug tree is `2dc524afee6040c4857cf554ee92cf0830edc951`. ZIP CRC checks passed. The five JNI libraries, Cloudflare and NaiveProxy helpers, and the root-helper asset are present for ARM64. Installed `base.apk` has the same byte hash. The normal MainActivity launch retained the existing package data and report store; no second seed or reset was used.

The final owning compare passed 34 Full cases and 34 Simple cases, with one existing Full-only assumption in Simple. All 396 existing fixture hashes remained unchanged, including the upstream Arabic metric fixture. Golden-blesser reviewed all 24 new and eight existing actual PNGs, all eight existing expected/diff pairs, and the full report canvas boundaries. The authorized owning record then passed both flavors and changed 33 PNGs: 24 new and nine existing fixtures. All 33 were reviewed, including the additional DNS summary diff. No existing dimensions changed; the upstream Arabic fixture remained unchanged. Strict whole-family verification passed as recorded below.

The final combined command used a temporary init script with `forkEvery = 1` and `maxParallelForks = 1`:

```sh
build-gate -- env GRADLE_USER_HOME=<retained-evidence>/gradle-home \
  ./gradlew --no-daemon --no-build-cache \
  --init-script /tmp/readability-test-isolation.gradle \
  :app:testGithubFullDebugUnitTest \
  --tests 'com.poyka.ripdpi.ui.*' \
  --tests '*MainViewModelTest' --tests '*MainActivityContentTest' \
  --tests '*BuildPermissionSummaryTest' \
  --tests 'com.poyka.ripdpi.platform.*LocaleTest' \
  --tests '*DiagnosticsLiveLabelsLocaleTest' \
  :app:lintGithubFullDebug :core:service:lintDebug staticAnalysis \
  -Pripdpi.skipNativeBuild=true --max-workers=4
```

The successful command log is `final-isolated-ui-no-cache-gate.log`; `final-ui-green-results/summary.json` and all 216 XML files retain the result provenance. The native APK used a separate real assemble task without the JVM-only native flag.

## Final installed acceptance

The retained final APK passed 42 Light and 42 Dark scene captures, plus four Dark EN/RU permission captures. All 88 PNGs were viewed and accepted; their fresh UI XML and JSON are retained. Settings, DNS, advanced fields, appearance and all six launcher previews, configuration, Home health, bottom navigation, diagnostics tabs, scan reports, candidate details and QUALITY, probe details, history filters and event details, logs, and connection health were exercised at 200% font. The integration owner also viewed the critical Light health card and icon grid, and Dark candidate header, selected history filter, and icon grid.

The final manifest `device-acceptance/final-device-manifest.json` has SHA-256 `d116e8496ef7bc76c56add9036a08b1f06929977c0cf6629a5b14ffb921206e4`. The integration owner checked all 264 file hashes and all 88 review results. `final-device-gallery.html` shows the unchanged native PNGs.

The final EN to RU permission check retained PID `2976` before, during, and after the change. Labels and guidance updated without a process restart. Final crash and ANR reports contain no app failure; historical exit records show package update and requested force stops before final launch. The owned emulator lease was reacquired after a wrapper interruption; APK bytes, font scale, density, and report data remained intact. The existing emulator-5586 was not changed.

Only the completed diagnostic status was present in the retained device store; selected and unselected states passed in both themes. Running, failed, and cancelled status labels were covered by host layout tests and fixtures, and are not claimed as device observations. The retained demo report proves layout and navigation. No VPN connection, diagnostic network scan, physical device, or live network result is claimed.

The final owned lease closed through the wrapper. The wrapper returned exit 1 with an unknown process-group state. Read-only checks confirmed that group `1036` was gone, the owned emulator was stopped, and Pool_1 was idle and eligible. The existing emulator-5586 remained alive. `final-lease-close-receipt.json` retains both the wrapper error and the observed closure; no AVD, branch, or worktree was deleted.

## Final fixture and integration receipt

The strict whole UI screenshot gate passed in 2m 28s: Full has 298 cases in 56 classes, with one existing assumption skip; Simple has 310 cases in 58 classes, with 29 existing variant assumptions. There are no failures or errors. All twelve new test cases executed in each variant. No assumption or threshold was added or changed.

```sh
build-gate -- ./gradlew :app:verifyRoborazziGithubFullDebug \
  :app:verifyRoborazziGithubSimpleDebug \
  -Pripdpi.skipNativeBuild=true -Pripdpi.includeRoborazziUnitTests=true \
  --tests 'com.poyka.ripdpi.ui.screenshot.*' --max-workers=4 --no-build-cache
```

Golden commit `852a9cdba271989f5920e83b155213b14d4fa78d` was integrated as `d911102d9` after the separate installed-acceptance documentation commit. The integration owner checked all 33 accepted hashes and dimensions, the new test SHA-256, and eleven input trees against the tested golden commit. The screenshot tree is `45b998672ca8255bf3c0ce449b18ffde92b30730`. Production and debug trees still match the installed APK source `cd3e41810`.

The retained golden evidence directory is `/Users/npochaev/.codex/visualizations/2026/10/12/01a12f2d-479e-7a20-af97-5645507716e9/emulator-followup-goldens/cd3e4181`. It contains owning compare and record logs, strict XML and counts, each expected/actual/diff review, and the integration tree receipt. `record-manifest.json` has SHA-256 `51b67a82bc16fbb19452e07f6632d79ec8234dfed1950ad3febc1f653c369cb4`. All 24 new and nine existing fixtures were accepted; no existing dimensions changed. The unrelated upstream Arabic metric fixture is unchanged.

The final architecture check has 21 indicators, no new or worsened entries, and no stale entries. Locked Cargo metadata has 116 workspace members. DESIGN.md lint has no errors or warnings. Task contracts passed for 128 tasks and 362 steps; all nine execution steps are complete and the portfolio item is in review.

## Closure evidence (2026-10-29)

The local, device, and artifact results recorded above were observed by the integration owner at the stated commits; their states were left at `required` pending review. Closure review accepted them without new claims. Hosted evidence: manual full CI run 40831691401 at `c9d5727ef890568c104e04e487623ec6d2e2227e` passed all 50 executed jobs with no failures, including `verify-roborazzi`, `gradle-static-analysis`, `kotlin-coverage`, `design-md-lint`, the three debug flavor builds, and the five instrumented-test API levels.
