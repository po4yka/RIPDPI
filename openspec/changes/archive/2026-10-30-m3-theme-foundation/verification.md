---
task_id: UIX-1793256778917552
change: m3-theme-foundation
commit_sha: fb00ea9ec9af97d9238f6ee5edea6b7680ca93e7
local: passed
local_evidence: Rebased unit suites, lint, staticAnalysis and both verifyRoborazzi variants passed on the theme branch; the icon branch passed unit tests, lint, staticAnalysis, a runtime dependency check with no material-icons artifact, and both verify variants after six owner-approved glyph goldens.
remote_ci: passed
remote_ci_evidence: Manual CI run 41016181081 at fb00ea9ec, which contains the theme foundation and Material Symbols icons, passed all 50 executed jobs, including verify-roborazzi, gradle-static-analysis, kotlin-coverage, design-md-lint, every release-verification shard, and build-android-tests.
device: passed
device_evidence: APK 7dec48b07 (githubFull arm64 debug) on the leased RIPDPI_Pool_2 API 35 emulator: secure contrast_level 0.0, 0.5 and 1.0 visibly strengthened panels, borders and icon fills on Home; the dynamic color switch applied the wallpaper tint, persisted across force-stop, and restored the seed scheme when off; light, dark and Material Symbols icons rendered correctly. Only those three contrast values were exercised, and adb could not set a saturated wallpaper.
artifact: passed
artifact_evidence: 422 theme goldens and 6 icon glyph goldens were blessed with owner approval from failed verify actuals only, reviewed through compare images, and re-verified green in both variants.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-UIX-M3F-COLOR | UIX-1793256828482280 | Generated SchemeNeutral #36414F schemes at three contrast levels, color and extended-role tests, theme goldens | Passed |
| REQ-UIX-M3F-CONTRAST | UIX-1793256829150094 | contrastLevelFor mapping tests and emulator contrast_level 0.0/0.5/1.0 captures | Passed |
| REQ-UIX-M3F-DYNAMIC | UIX-1793256829780496 | DynamicColorThemeTest, settings, backup and proto tests, ten-locale strings, emulator on, off and relaunch captures | Passed |
| REQ-UIX-M3F-MOTION | UIX-1793256830355597 | RipDpiMotionScheme tests with reduced and disabled motion | Passed |
| REQ-UIX-M3F-TYPE-SHAPE | UIX-1793256830959600 | Geist typography and corner-scale tests, font-scale goldens | Passed |
| REQ-UIX-M3F-ICONS | UIX-1793256831561754 | Runtime classpath without material-icons, RipDpiIconsTest, six approved glyph goldens, emulator icon captures | Passed |
| REQ-UIX-M3F-CONTRACT | UIX-1793256832167487 | DESIGN.md, design-system.md and UI skills updated, design-md-lint passed in CI, GlanceWidgetThemeParityTest | Passed |
