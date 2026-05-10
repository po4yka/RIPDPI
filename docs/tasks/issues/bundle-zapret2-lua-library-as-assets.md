---
title: Bundle zapret2 Lua library as Android assets
type: task
status: review
area: android
priority: medium
owner: unassigned
parent: zapret2-feature-parity-epic
blocks: []
blocked_by: [implement-lua-api-surface]
created: 2026-05-09
updated: 2026-05-10
---

- [ ] #task Bundle zapret2 Lua library as Android assets #repo/RIPDPI #area/android #status/review 🔼

## Objective

Copy `zapret-antidpi.lua` and `zapret-lib.lua` from the zapret2 project into RIPDPI's Android assets directory so the app can load them at runtime without requiring the user to provide their own copy. Create a manifest listing bundled scripts and a Kotlin `LuaAssetManager` helper that copies assets to internal storage on first run.

## Context

zapret2's Lua library files are at `/Users/po4yka/GitRep/zapret2/lua/zapret-antidpi.lua` (1252 lines) and `/Users/po4yka/GitRep/zapret2/lua/zapret-lib.lua`. These files are MIT-compatible licensed (check license before including). The RIPDPI app loads Lua scripts from internal storage (`/data/data/com.poyka.ripdpi/files/lua/`). On first run with the Lua feature enabled, `LuaAssetManager` extracts the bundled scripts to internal storage where the Rust engine can read them via file path.

**Asset directory:** `app/src/lua/assets/lua/` (or `app/src/main/assets/lua/` if that matches the project convention — check by reading `app/src/main/` directory structure).

**Files to bundle:**
- `zapret-antidpi.lua` — strategy function library (the main library)
- `zapret-lib.lua` — orchestration helpers (`orchestrate`, `verdict_aggregate`, `luaexec`, etc.)
- `lua-manifest.json` — version manifest: `{"version": "2.0", "files": ["zapret-antidpi.lua", "zapret-lib.lua"], "zapret2_commit": "<git-sha>"}`

**`LuaAssetManager` (Kotlin):**
```kotlin
object LuaAssetManager {
    fun ensureExtracted(context: Context): Path  // returns lua/ dir in filesDir
    fun currentManifestVersion(context: Context): String
    fun extractIfOutdated(context: Context)  // called on app upgrade
}
```

On upgrade, compare `lua-manifest.json` version in assets vs internal storage; re-extract if different (user-modified scripts are backed up with `.user.bak` extension before overwrite).

## Acceptance criteria

- [ ] `zapret-antidpi.lua` and `zapret-lib.lua` are present in RIPDPI assets after task is complete
- [ ] `LuaAssetManager.ensureExtracted()` creates `<filesDir>/lua/` and copies both files on first call
- [ ] On app upgrade with changed manifest version, user scripts are backed up before overwrite
- [ ] `lua-manifest.json` records the zapret2 git commit SHA from which the files were taken
- [ ] Asset extraction is tested with a Robolectric unit test (no emulator required): verify files appear in `filesDir/lua/` after `ensureExtracted()`
- [ ] zapret2 license terms are satisfied (attribution comment added to the top of bundled files if required)
- [ ] Bundled scripts load without error in `LuaStrategyEngine.load_script()` on a device (verified in CI instrumented test)

## Source references

- zapret2 Lua library: `/Users/po4yka/GitRep/zapret2/lua/zapret-antidpi.lua`
- zapret2 Lua helpers: `/Users/po4yka/GitRep/zapret2/lua/zapret-lib.lua`
- zapret2 license: `/Users/po4yka/GitRep/zapret2/` — check for LICENSE file
- RIPDPI app assets convention: `app/src/main/assets/` — verify existing structure

## TDD workflow

1. **Write tests first** — before copying any files or writing `LuaAssetManager`, write a Robolectric unit test that calls `ensureExtracted()` and asserts the expected files appear in `filesDir`.
2. **Confirm red** — run `./gradlew test` and confirm the test fails because `LuaAssetManager` doesn't exist and the files are not in assets.
3. **Implement** — add the asset files and implement `LuaAssetManager` to make the test pass.
4. **Confirm green** — run `./gradlew test verifyRoborazziDebug`; zero regressions.
5. **Refactor** — clean up while keeping tests green.

**Test files to create before implementation:**
- `app/src/test/kotlin/com/poyka/ripdpi/lua/LuaAssetManagerTest.kt` — using Robolectric, call `LuaAssetManager.ensureExtracted(context)`; assert `filesDir/lua/zapret-antidpi.lua` and `filesDir/lua/zapret-lib.lua` exist; fails until `LuaAssetManager` is implemented and files are in assets
- `app/src/test/kotlin/com/poyka/ripdpi/lua/LuaAssetManagerUpgradeTest.kt` — simulate upgrade: write a user-modified `zapret-antidpi.lua` to `filesDir`; update manifest version; call `extractIfOutdated()`; assert user file is renamed to `.user.bak` and new file is extracted; fails until backup logic is implemented
- `app/src/test/kotlin/com/poyka/ripdpi/lua/LuaAssetManagerVersionTest.kt` — assert `currentManifestVersion()` returns the version string from the bundled `lua-manifest.json`; fails until manifest parsing is implemented
- `native/rust/crates/ripdpi-strategy-lua/tests/bundled_scripts_load.rs` — in an integration test, point `LuaStrategyEngine::load_script` at the path where `LuaAssetManager` extracts files; assert both scripts load without error; fails until extraction path is wired to the Rust engine

## Definition of done

Fresh install on emulator: `LuaAssetManager.ensureExtracted()` runs, both `.lua` files appear in internal storage, `LuaStrategyEngine` loads them without error. Tests were written and confirmed red before implementation began; the relevant test command is green with no regressions.

## Work log

2026-05-10:

- Bundled `zapret-antidpi.lua`, `zapret-lib.lua`, `lua-manifest.json`, and `LICENSE.zapret2.txt` under `app/src/main/assets/lua/` from zapret2 commit `fd4716da5426550a3354b1b179f3dda446811a13`.
- Added `LuaAssetManager` with `ensureExtracted()`, `currentManifestVersion()`, and `extractIfOutdated()`; outdated extraction backs up existing user script files with `.user.bak` before overwrite.
- Added Robolectric coverage for first extraction, manifest version reading, and user-modified script backup.
- Added instrumented JNI coverage for fresh extraction followed by bundled zapret script loading.
- Verification:
  - `./gradlew :app:ktlintCheck -Pripdpi.skipNativeBuild=true`
  - `./gradlew :app:testDebugUnitTest --tests com.poyka.ripdpi.lua.LuaAssetManagerTest -Pripdpi.skipNativeBuild=true`
  - `./gradlew :app:ktlintCheck :app:compileDebugAndroidTestKotlin -Pripdpi.skipNativeBuild=true`
  - `CARGO_TARGET_DIR=/Users/po4yka/GitRep/.codex-targets/ripdpi-lua-review cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-strategy-lua --features lua-strategies`
- Attached emulator validation on `Pixel_10_Pro(AVD) - 17`: manually installed `app-arm64-v8a-debug.apk` and `app-debug-androidTest.apk`, then ran `$HOME/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell am instrument -w -r -e class com.poyka.ripdpi.jni.StrategyEngineJniInstrumentedTest com.poyka.ripdpi.test/com.poyka.ripdpi.HiltTestRunner` — passed, `OK (2 tests)`, covering fresh `LuaAssetManager.ensureExtracted()` followed by bundled zapret script loading through JNI.
