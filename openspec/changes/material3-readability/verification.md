---
task_id: UIX-1791793531264679
change: material3-readability
commit_sha: d226169bbb42f4cea4fb09278706dbd3f05a7225
local: required
local_evidence: Historical gates below passed at their stated commits. Follow-up UI regressions, app/service lint, static analysis, and a real ARM64 APK build passed. Neutral status captions and the combined-tree gate remain pending.
remote_ci: not_applicable
remote_ci_evidence: Local implementation and main commits only; push is not requested.
device: required
device_evidence: The baseline APK was installed on API 37 ARM64 at 411dp and 200% font scale. Its light and dark UI exposed the follow-up defects. The corrected APK is built; pool-device installation and both-theme acceptance remain pending.
artifact: required
artifact_evidence: Historical renders and strict fixture gates passed as recorded below. Current before captures and ten new screenshot scenes are prepared; corrected device captures and new fixture verification remain pending.
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
| REQ-UI-INSTALLED-EMULATOR | UIX-1791877120798384 | Installed API 37 emulator, both themes, 200% font; runtime regression tests and reviewed captures | Pending |

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

Current emulator follow-up evidence is stored outside the repository build directories at `/Users/npochaev/.codex/visualizations/2026/10/12/01a12f2d-479e-7a20-af97-5645507716e9/emulator-20261013`. The follow-up is pending until the corrected APK, regression gates, and both-theme device captures are verified.


## Installed follow-up source checkpoint

Source checkpoint `d226169bbb42f4cea4fb09278706dbd3f05a7225` contains seven scoped fixes. The first UI run executed 1259 tests: 1258 passed and one launcher contrast assertion failed because its crop excluded the visible preview edges. The corrected viewport test passed with no failures, errors, or skips. The retry also passed app/service lint and complete static analysis. These are separate observed runs; a fresh combined-tree run remains required.

A real ARM64 debug APK built without `skipNativeBuild` in 33m 21s. Its SHA-256 is `e3511cb1189726db220221fa350ef7fb1071d6193a7731f4909e3e6ccd33cee3`, with 744042069 bytes. The immutable APK and `apk-d226-receipt.json` are in the retained emulator evidence directory. This intermediate APK establishes the native build; final acceptance must use the rebased source and final captions.
