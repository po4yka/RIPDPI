---
task_id: EPC-1793256695505524
change: material3-ui-overhaul
commit_sha: 095620b8119eed39e7ed325b701af6df466a3c17
local: passed
local_evidence: On the final branch, the navigation unit tests (42 tests in 7 classes), the component and theme unit tests, DesignSystemSourceRulesTest and ktlint passed. Both verifyRoborazzi variants passed after the owner-approved contrast-fix bless. scripts/ci/verify_design_md.py reported 0 violations. The githubFullReleaseRuntimeClasspath dependency report resolves material3 1.5.0-beta02 and contains no material-icons artifact.
remote_ci: passed
remote_ci_evidence: Manual CI run 41221899635 at 095620b81 passed all 50 executed jobs on attempt 2. These include verify-roborazzi, gradle-static-analysis, kotlin-coverage, design-md-lint, build-android-tests, the debug builds, and android-instrumented-tests with the Kaspresso smoke on API 27, 33, 35, 36 and 37. Attempt 1 failed only the API 37 16 KB-page job: the androidx test-services ShellMain process (uid 2000) took SIGSEGV one second after start, before any app test ran. Run 41215472373 at 51a59ed1c, with the rail and chevron fixes, passed all 50 jobs.
device: passed
device_evidence: Locally built arm64-v8a githubFull debug APK 338f2bb4 at 095620b81, built with the CI run 41188621404 prebuilt arm64 natives, ran on temporary API 35 pixel_6 and pixel_tablet pool AVDs, which were removed afterwards. The earlier phone and tablet runs at 200% font (light/en and dark/ar) found four issues: the rail hid beside an open Settings detail; the closed dropdown chevron pointed up in RTL; the app crashed when leaving Settings from the list-detail placeholder; the selected row lost contrast at medium and high contrast (3.6:1 and 1.8:1). Each was fixed (62cf6c26b, 51a59ed1c, 90c8536ca, 9f20af295) and rechecked. On the final APK, the crash paths opened Home with an empty crash log. In Arabic at 200% font on both devices, medium contrast (light), high contrast (dark) and dynamic color (light) rendered Home and Settings with stronger borders, the wallpaper tint and no clipped text. The selected Settings row measured about 5.2:1, 11.1:1 and 13.3:1. The dynamic color tint is subtle because the AVD default wallpaper could not be replaced.
artifact: passed
artifact_evidence: The four child changes are archived (2026-10-30-m3-theme-foundation, 2026-10-31-m3-adaptive-app-shell, 2026-10-31-m3-component-facade, 2026-10-31-m3-screen-layouts), and their requirements are synced into openspec/specs/ui. With owner approval, the two Persian RTL Strategy config goldens were re-recorded for the chevron fix, and every other recorded file was restored unchanged. Also with owner approval, five goldens that failed verify after the contrast fix were blessed. Sub-threshold golden drift found during this work is tracked in TST-1793464904072920.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-EPC-1793256695505524-001 | EPC-1793256767038776 | The theme, shell, facade and screen-layout tasks were closed with the done outcome and purged after their terminal commits; their four OpenSpec changes are archived | Passed |
| REQ-EPC-1793256695505524-002 | EPC-1793256767620899 | Phone and tablet emulator captures at 200% font in Arabic RTL: light (English) and dark (Arabic) from the epic run, then medium contrast, high contrast and dynamic color on the final APK, with the tablet in landscape showing list-detail and supporting panes. Defects found on the way were fixed and rechecked | Passed |
| REQ-EPC-1793256695505524-003 | EPC-1793256768223592 | The githubFullReleaseRuntimeClasspath dependency report has no material-icons-core or material-icons-extended | Passed |
| REQ-EPC-1793256695505524-004 | EPC-1793256768845851 | docs/design-system.md and DESIGN.md were aligned with the facade and layout code (5d0f8ba3a), with each claim checked against source; verify_design_md.py reported 0 violations; the material-3 and android-compose-patterns skills and rds-spec.md were checked and had no false claims | Passed |
