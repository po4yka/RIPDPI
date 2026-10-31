---
task_id: UIX-1793256870879296
change: m3-adaptive-app-shell
commit_sha: 71d272bb82803e326ca2ea58132f48fc740cbcc2
local: passed
local_evidence: Both verifyRoborazzi variants passed after the owner-approved shell and top app bar goldens. After the rebase onto main, the stacked gate passed (the Simple unit-test compile, DesignSystemSourceRulesTest, SharedComponentCollectionApiTest and :core:data:runtime-state:detekt). The full unit suite with the navigation, top app bar, layout and motion tests ran in CI kotlin-coverage.
remote_ci: passed
remote_ci_evidence: Manual CI run 41188616435 at 71d272bb8 passed all 50 executed jobs, including verify-roborazzi, gradle-static-analysis, kotlin-coverage, rust-lint, rust-coverage, design-md-lint, build-android-tests, the github, play and fdroid debug builds, and android-instrumentation-apks.
device: passed
device_evidence: arm64-v8a githubFull debug APK b3d420cd, built from the stack tip 6eeb45fc2 (which contains this change) with the CI run 41188621404 prebuilt arm64 natives, ran on a temporary API 35 pixel_6 pool AVD that was removed afterwards. The DNS settings top bar hid on scroll and came back on a small scroll up. The back key and a slow edge swipe with predictive back returned from DNS settings to Settings with the shrinking-panel preview. The mode editor's unsaved-changes guard showed "Discard unsaved changes?": Keep editing stayed in the editor and Discard left it. Landscape at 1.3x font showed the labeled expanded rail beside the Settings list-detail with no clipping. Not exercised on device: the SSH editor's discard guard.
artifact: passed
artifact_evidence: Navigation shell, top app bar, favorite-star and maximum-font shell goldens (186 files across light, dark and Simple captures) were blessed with owner approval from failed verify actuals only, reviewed through compare images, and re-verified green in both variants.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-UIX-M3S-NAV | UIX-1793256919780082 | TopLevelNavigationScaffoldTest, RipDpiNavRailTest and BottomNavBarTest at each width class; nav bar, rail and maximum-font shell goldens; emulator capture of the expanded rail in landscape at 1.3x font | Passed |
| REQ-UIX-M3S-SIZE | UIX-1793256920344090 | RipDpiLayoutTest and NavigationStylesTest derive the width class and layout tokens from Material adaptive window info | Passed |
| REQ-UIX-M3S-APPBAR | UIX-1793256920983344 | RipDpiTopAppBarTest, top app bar goldens including 200% font, and emulator captures of the enterAlways bar on DNS settings | Passed |
| REQ-UIX-M3S-BACK | UIX-1793256921649552 | PredictiveBackNavigationTest and RipDpiNavHostLogicTest; emulator back key and edge-swipe captures with the predictive preview; unsaved-changes guard capture | Passed |
