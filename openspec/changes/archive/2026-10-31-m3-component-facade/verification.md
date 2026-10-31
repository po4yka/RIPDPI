---
task_id: UIX-1793256927624290
change: m3-component-facade
commit_sha: 24f4be7b4775af8aae14f005353d11ae985a300e
local: passed
local_evidence: Both verifyRoborazzi variants passed after the owner-approved facade goldens and again after the reveal-control fix. DesignSystemSourceRulesTest, AnyTlsPasswordPresentationTest and AmneziaWgProfileScreenTest passed on the final commit, and the githubFull androidTest sources compiled.
remote_ci: passed
remote_ci_evidence: Manual CI run 41202577345 at 24f4be7b4 passed all 50 executed jobs, including verify-roborazzi, gradle-static-analysis, kotlin-coverage, rust-lint, rust-coverage, design-md-lint, build-android-tests, the github, play and fdroid debug builds, and android-instrumentation-apks.
device: passed
device_evidence: arm64-v8a githubFull debug APK b3d420cd, built from the stack tip 6eeb45fc2 (which contains this change) with the CI run 41188621404 prebuilt arm64 natives, ran on a temporary API 35 pixel_6 pool AVD that was removed afterwards. Home, Settings, History, Logs, Diagnostics, the mode editor and the SSH editor rendered the Material 3 buttons, icon buttons, switches, chips, text fields, dropdowns, cards, menus and dialogs without clipped or overlapping text. The one finding, the labeled Reveal button filling the trailing slot of secret fields, was fixed in 24f4be7b4 by a trailing icon button; that fix is covered by goldens and unit tests, not by a second device run.
artifact: passed
artifact_evidence: 116 facade goldens (light, dark and Simple captures) were blessed with owner approval from failed verify actuals only, reviewed through compare images, and re-verified green in both variants. 18 AmneziaWG, AnyTLS and SSH editor goldens for the reveal icon were blessed with owner approval; the SSH captures sit under the 1% compare threshold, so they were re-recorded explicitly, and every other recorded file was restored unchanged.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-UIX-M3C-FACADE | UIX-1793256949141341 | RipDpiMaterialButtonTest, RipDpiSwitchColorsTest, FocusRingContrastTest, relay editor tests, facade goldens and emulator smoke captures; public RipDpi signatures, test tags and automation IDs kept, with call sites updated in the same commits | Passed |
| REQ-UIX-M3C-DELETE | UIX-1793256949885861 | RipDpiButtonStateTokens, RipDpiControlStateTokens and RipDpiStyleSprings were deleted with their tests; the app source diff removes 2261 lines and adds 948 | Passed |
| REQ-UIX-M3C-CUSTOM | UIX-1793256950622795 | DesignSystemSourceRulesTest (no direct colorScheme reads outside the theme), RipDpiMotionTest, and light and dark goldens of the custom components (log rows, the logs stream, and the Home and screen catalog captures) | Passed |
| REQ-UIX-M3C-STABLE | UIX-1793256951282038 | A source search finds no ExperimentalMaterial3ExpressiveApi opt-in, DesignSystemSourceRulesTest enforces that, and the app compiles in CI | Passed |

## Deviations

- Chips and settings rows stay on the RDS layout over Material 3 `Surface` with Material 3 colors instead of `FilterChip` and `ListItem`. This is the owner's decision of 2026-10-30, which keeps their density and test tags.
- Bottom sheets, the exposed dropdown and the search sheet use `ModalBottomSheet`, `SheetState` and `ExposedDropdownMenuBox` under `ExperimentalMaterial3Api`. The requirement forbids only Expressive opt-ins, and these are the only Material 3 APIs for those components.
