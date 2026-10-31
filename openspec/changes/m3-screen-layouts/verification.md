---
task_id: UIX-1793256952721314
change: m3-screen-layouts
commit_sha: c4984440d9633a4ededc939ab3337b0d85832b2f
local: passed
local_evidence: Both verifyRoborazzi variants passed after the owner-approved layout goldens, and GithubSimple passed again after the relay editor goldens were re-recorded on the rebased branch. DesignSystemSourceRulesTest, SharedComponentCollectionApiTest, AnyTlsPasswordPresentationTest and AmneziaWgProfileScreenTest passed on the final commit, the githubFull androidTest sources compiled, and check_architecture_health.py --check exited 0.
remote_ci: passed
remote_ci_evidence: Manual CI run 41206808364 at c4984440d passed all 50 executed jobs, including verify-roborazzi, gradle-static-analysis, kotlin-coverage, build-android-tests, the github, play and fdroid debug builds, and android-instrumented-tests on API 27, 33, 35, 36 and 37. Those jobs ran the Kaspresso journey smoke, which covers Logs filtering, History search, the Diagnostics scan section and the Mieru editor Save. The earlier run 41203124431 at ae5e61921 failed on an unused import, fixed in c4984440d, and on an API 27 emulator whose package manager never became ready.
device: passed
device_evidence: arm64-v8a githubFull debug APK b3d420cd, built from 6eeb45fc2 with the CI run 41188621404 prebuilt arm64 natives, ran on a temporary API 35 pixel_6 pool AVD that was removed afterwards. Landscape at 1.3x font showed Settings as list-detail beside the expanded rail. Logs had Save and the overflow menu in the top app bar, the search field filtered the stream to one entry, and the overflow menu offered Share support bundle and Clear. The SSH and mode editors had Save in the top app bar. Home, History and Diagnostics rendered without clipped or overlapping text. Not exercised on a device: a tablet-sized window, the Diagnostics detail pane and Home's supporting pane, which have layout tests and goldens. Of the edited Kaspresso journeys, only the CI smoke subset ran.
artifact: passed
artifact_evidence: 26 layout goldens were blessed with owner approval from failed verify actuals only, reviewed through compare images, and re-verified green. The Diagnostics scan FAB capture was not blessed: that change was reverted at the owner's request. The 18 AmneziaWG, AnyTLS and SSH editor captures were re-recorded with owner approval to combine the top-bar Save with the facade's trailing reveal icon, and every other recorded file was restored unchanged.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-UIX-M3L-LISTDETAIL | UIX-1793256975183066 | SettingsListDetailNavigationTest, HistoryScreenTest, LogsViewportTest and DiagnosticsScreenTest across width classes; list-detail goldens; emulator capture of Settings list-detail in landscape | Passed |
| REQ-UIX-M3L-SUPPORTING | UIX-1793256975677756 | HomeScreenTest and ModeEditorScreenTest at wide widths, and supporting-pane goldens | Passed |
| REQ-UIX-M3L-ACTIONS | UIX-1793256976186695 | Editor, Logs and AWG top-bar action tests and goldens; emulator captures of Save in the top bar and the Logs overflow menu. The Diagnostics scan keeps its in-card action, as the owner decided on 2026-10-31 | Passed |
| REQ-UIX-M3L-SEARCH | UIX-1793256976764520 | LogsViewportTest search filtering, History search, and the emulator capture of Logs search filtering to one entry. Search stays a text field, as the owner decided on 2026-10-31 | Passed |
| REQ-UIX-M3L-REMAINING | UIX-1793256977322473 | ArabicLargeFontScreensTest at 200% font across the remaining screen families, OnboardingScreenTest, RipDpiContentWidth caps, and the DNS option card clipping fix | Passed |
