---
title: Add strategy config editor screen in Settings → Advanced
type: task
status: review
area: ui
priority: medium
owner: unassigned
parent: zapret2-feature-parity-epic
blocks: []
blocked_by: []
created: 2026-05-09
updated: 2026-05-10
---

- [ ] #task Add strategy config editor screen in Settings → Advanced #repo/RIPDPI #area/ui #status/review 🔼

## Objective

Add a `StrategyConfigScreen` in Kotlin/Compose that lets users view the active strategy YAML, import a YAML file from device storage, export the current config, and trigger a live reload without restarting the app.

## Context

Power users migrating from zapret2 on Linux need a way to paste or import their existing strategy chains. The screen lives under Settings → Advanced → Strategy Config. It is not shown in simplified (non-developer) mode. The active config path is exposed through the existing `SettingsDataStore`; the screen displays the YAML as a read-only monospaced text view by default, with an edit toggle for advanced users.

UI layout:

- Header: "Strategy Config" with subtitle showing active config path
- Config source selector: Built-in (default adaptive) | Custom YAML | Lua Script
- When "Custom YAML" is selected: monospaced text field showing current YAML, Edit / Import / Export buttons
- Import: opens system file picker for `.yaml` / `.yml` / `.toml`
- Export: shares current config YAML via Android share sheet
- Reload button: calls JNI `StrategyEngine.reloadConfig()` and shows toast with success/error
- Validation banner: shows parse errors from the last reload attempt inline
- When "Lua Script" is selected: shows path picker and function name input (links to Phase 4 Lua task)

## Acceptance criteria

- [ ] `StrategyConfigScreen` is reachable from Settings → Advanced without requiring developer mode unlock in the first version
- [ ] Editing and saving a valid YAML triggers `StrategyEngine.reloadConfig()` via JNI
- [ ] Parse errors from the Rust side are surfaced as a red inline banner (not a crash)
- [ ] Import from file picker works for `.yaml` / `.yml` files ≤ 64 KB
- [ ] Export shares the YAML text via the system share sheet
- [ ] Screen is excluded from screenshot/screen-record via `WindowManager.LayoutParams.FLAG_SECURE` (config may contain sensitive hostlists)
- [ ] Roborazzi golden screenshot for default empty state

## Source references

- `SettingsDataStore` — existing data store that exposes active config path
- `StrategyEngine` JNI bridge — `reloadConfig()` entry point to implement
- Roborazzi golden screenshot conventions in `app/src/test/` — follow existing baseline pattern

## TDD workflow

1. **Write tests first** — before any implementation, write ViewModel unit tests and a Roborazzi golden for the empty/idle state.
2. **Confirm red** — run `./gradlew test` (ViewModel tests) and `./gradlew recordRoborazziDebug` (golden); confirm ViewModel tests fail because the class doesn't exist yet.
3. **Implement** — build `StrategyConfigScreen` and `StrategyConfigViewModel` to make the tests pass.
4. **Confirm green** — run `./gradlew test verifyRoborazziDebug`; zero regressions on existing goldens.
5. **Refactor** — clean up while keeping tests green.

**Test files to create before implementation:**
- `app/src/test/kotlin/com/poyka/ripdpi/ui/strategy/StrategyConfigViewModelTest.kt` — test that `loadConfig()` emits `ConfigState.Loaded` with content, `reloadConfig()` calls JNI and emits `ConfigState.Reloaded`, and parse errors emit `ConfigState.Error`; all fail until `StrategyConfigViewModel` exists
- `app/src/test/kotlin/com/poyka/ripdpi/ui/strategy/StrategyConfigImportTest.kt` — test that importing a valid YAML file updates state to `ConfigState.Loaded`; importing a file > 64 KB emits `ConfigState.Error("File too large")`
- `app/src/screenshotTest/kotlin/com/poyka/ripdpi/ui/strategy/StrategyConfigScreenTest.kt` — Roborazzi golden for idle state (no config loaded); fails until composable exists

## Definition of done

Manual test: import the zapret2 example config, press Reload, confirm no error banner, confirm strategy appears in diagnostics active strategy list. Tests were written and confirmed red before implementation began; the relevant test command is green with no regressions.

## Implementation Notes

- Added `Route.StrategyConfig` and wired it into the Settings graph behind the existing Advanced Settings screen.
- Added an Advanced Settings entry row, stable test tags, and preview/test no-op actions for the new navigation callback.
- Added a secure `StrategyConfigScreen` with source selection, current strategy-chain editor, import/export actions, reload/save banners, and Lua script path/function controls.
- Added `FLAG_SECURE` while the screen is visible so config text is excluded from screenshots and screen recording.
- Added a `core:service` bridge (`StrategyConfigRuntime`) so `:app` can trigger JNI-backed strategy runtime operations without depending on `:core:engine` directly.
- Added a 64 KB UTF-8 import helper with unit tests for accepted text, over-limit files, and blank files.
- Added awaited settings persistence for Strategy Config saves and wired saved YAML/DSL changes to request a controlled restart of an active service. This applies changed YAML to the active tunnel path without requiring an app restart; halted services still pick up the saved config on next start.
- Added Compose callback coverage for the text config Import and Export buttons so the in-app action wiring is locked down separately from Android's external picker/share-sheet UI.

## Validation

- `./gradlew :app:ktlintCheck :core:service:ktlintCheck -Pripdpi.skipNativeBuild=true`
- `./gradlew :app:testDebugUnitTest --tests com.poyka.ripdpi.ui.screens.settings.StrategyConfigImportTest -Pripdpi.skipNativeBuild=true`
- `./gradlew :app:testDebugUnitTest --tests com.poyka.ripdpi.ui.screenshot.RipDpiScreenCatalogScreenshotTest.strategyConfigScreen -Pripdpi.skipNativeBuild=true -Pripdpi.includeRoborazziUnitTests=true`
- Clean detached worktree: `ANDROID_HOME=$HOME/Library/Android/sdk ANDROID_SDK_ROOT=$HOME/Library/Android/sdk ./gradlew :app:ktlintCheck :app:assembleDebugAndroidTest -Pripdpi.skipNativeBuild=true`; direct `am instrument` run of `StrategyEngineJniInstrumentedTest` passed with `OK (4 tests)`, covering native validation of imported YAML registry entries and reloadable Lua assets.
- 2026-05-10 current checkout: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.poyka.ripdpi.jni.StrategyEngineJniInstrumentedTest -Pripdpi.localNativeAbis=arm64-v8a` passed on `emulator-5554` (`Pixel_10_Pro(AVD) - 17`) after wiping the AVD data partition to restore install space. The run executed 4 tests and covered JNI validation for YAML entries including `fake`, `udplen`, and `ipv6Ext`, invalid YAML error surfacing, bundled `zapret-lib.lua` / `zapret-antidpi.lua` extraction, Lua validation, Lua load, and strategy listing.
- 2026-05-10 clean detached worktree: `ANDROID_HOME=$HOME/Library/Android/sdk ANDROID_SDK_ROOT=$HOME/Library/Android/sdk ./gradlew :app:ktlintCheck -Pripdpi.skipNativeBuild=true` passed.
- 2026-05-10 clean detached worktree: `ANDROID_HOME=$HOME/Library/Android/sdk ANDROID_SDK_ROOT=$HOME/Library/Android/sdk ./gradlew :app:testDebugUnitTest --tests com.poyka.ripdpi.activities.MainViewModelTest -Pripdpi.skipNativeBuild=true` passed, including active-service Strategy Config restart coverage.
- 2026-05-10 clean detached worktree: `ANDROID_HOME=$HOME/Library/Android/sdk ANDROID_SDK_ROOT=$HOME/Library/Android/sdk ./gradlew :app:testDebugUnitTest --tests com.poyka.ripdpi.ui.screens.settings.StrategyConfigScreenTest -Pripdpi.skipNativeBuild=true` passed, covering in-app Import/Export button callback wiring.

## Remaining Gaps

- Manual UI file-picker import/export validation with a zapret2 config was not run. The current evidence covers native validation/load, in-app Import/Export action wiring, and active-service restart apply; it does not cover Android's external document picker or share-sheet UI.
