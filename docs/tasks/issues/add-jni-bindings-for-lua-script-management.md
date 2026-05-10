---
title: Add JNI bindings for Lua script management
type: task
status: review
area: android
priority: medium
owner: unassigned
parent: zapret2-feature-parity-epic
blocks: []
blocked_by: []
created: 2026-05-09
updated: 2026-05-10
---

- [ ] #task Add JNI bindings for Lua script management #repo/RIPDPI #area/android #status/review 🔼

## Objective

Expose Lua script management operations to Kotlin via JNI: load a script from a file path, reload the active strategy, list registered strategy function names, and validate a script (parse without executing). These JNI methods back the `StrategyConfigScreen` UI actions.

## Context

The existing JNI bridge in `native/rust/crates/ripdpi-android/` (or similar) provides the Kotlin→Rust interface. Lua script operations need four new JNI exports following the existing naming convention. The `LuaStrategyEngine` singleton is managed by the Rust side; Kotlin only passes paths and receives results. Errors are returned as nullable strings (null = success, non-null = error message) to keep the JNI signature simple.

**JNI methods to implement:**

```kotlin
// Kotlin declarations (in StrategyEngine.kt or similar companion object):
external fun luaLoadScript(path: String): String?        // null = success, error message otherwise
external fun luaReloadConfig(): String?                  // reload YAML + Lua from current paths
external fun luaListStrategies(): Array<String>          // registered strategy IDs
external fun luaValidateScript(path: String): String?   // parse-only, no execution
```

**Rust JNI exports (in `ripdpi-android` crate):**
```rust
#[no_mangle]
pub extern "C" fn Java_com_poyka_ripdpi_StrategyEngine_luaLoadScript(
    env: JNIEnv, _class: JClass, path: JString) -> JObject;
// etc.
```

The JNI layer acquires the `LuaStrategyEngine` from the global singleton (already managed by the proxy/VPN runtime), calls the appropriate method, and converts `Result<_, LuaError>` to `JString` (error message) or `JObject::null()` (success).

## Acceptance criteria

- [ ] All four JNI methods are declared in Kotlin with `external fun` and implemented in Rust
- [ ] JNI method names follow the existing `Java_com_poyka_ripdpi_*` naming convention in the codebase
- [ ] `luaLoadScript` with a non-existent path returns a non-null error string (not a JNI crash)
- [ ] `luaLoadScript` with a valid `.lua` file path returns null (success)
- [ ] `luaListStrategies()` returns the array of registered strategy IDs (empty array when no scripts loaded)
- [ ] `luaValidateScript` runs Lua parsing (Lua `load()` equivalent) without executing the script
- [ ] JNI exceptions are caught and converted to error strings — no unhandled JNI exceptions that crash the app
- [ ] `cargo test -p ripdpi-android` covers JNI method linkage (verify symbol names are correct)
- [ ] Instrumented test on emulator: `luaLoadScript(bundledScriptPath)` returns null after `LuaAssetManager.ensureExtracted()` succeeds

## Source references

- Existing JNI bridge: `native/rust/crates/ripdpi-android/` — inspect existing JNI exports for naming convention
- RIPDPI JNI VpnProtect callback: referenced in AGENTS.md as `ripdpi-android/src/vpn_protect.rs` — naming pattern to follow
- JNI error handling pattern: check existing `#[no_mangle] pub extern "C" fn Java_com_poyka_ripdpi_*` functions in the codebase

## TDD workflow

1. **Write tests first** — before implementing JNI methods, write Kotlin unit tests for each JNI method using a test double for the Rust side, and an instrumented test that calls the real JNI on an emulator.
2. **Confirm red** — run `./gradlew test` and confirm the unit tests fail because the JNI declarations don't exist; run `./gradlew connectedAndroidTest` to confirm the instrumented test fails with `UnsatisfiedLinkError`.
3. **Implement** — add the `external fun` declarations, implement the Rust JNI exports, and make the tests pass.
4. **Confirm green** — run both test suites; zero regressions on existing JNI functionality.
5. **Refactor** — clean up while keeping tests green.

**Test files to create before implementation:**
- `app/src/test/kotlin/com/poyka/ripdpi/jni/StrategyEngineJniTest.kt` — mock the JNI layer; assert `luaLoadScript("/nonexistent")` returns a non-null error string; assert `luaListStrategies()` returns an empty array when no scripts are loaded; fails until declarations exist
- `app/src/androidTest/kotlin/com/poyka/ripdpi/jni/StrategyEngineJniInstrumentedTest.kt` — on a real emulator: extract bundled scripts, call `luaLoadScript(extractedPath)`, assert `null` (success); call `luaListStrategies()`, assert non-empty; fails until Rust JNI exports are implemented
- `app/src/test/kotlin/com/poyka/ripdpi/jni/LuaValidateScriptTest.kt` — assert `luaValidateScript(validScriptPath)` returns null; `luaValidateScript(invalidScriptPath)` returns non-null error string; fails until `luaValidateScript` JNI is implemented
- `native/rust/crates/ripdpi-android/tests/jni_symbol_names.rs` — parse the compiled `.so` symbol table with `nm` or `objdump`; assert `Java_com_poyka_ripdpi_StrategyEngine_luaLoadScript` and the other three symbols are present; fails until the `#[no_mangle]` exports are added

## Definition of done

`StrategyConfigScreen` can call `luaLoadScript()` and display the result; no JNI crash on error path. Tests were written and confirmed red before implementation began; the relevant test command is green with no regressions.

## Work log

2026-05-10:

- Added `StrategyEngineBindings` and `StrategyEngineNativeBindings` in `:core:engine` with the four Lua script-management declarations.
- Added Rust JNI exports in `ripdpi-android` for load, reload, list, and parse-only validation, with nullable error strings for load/reload/validate and an empty array fallback for list failures.
- Extended `LuaStrategyEngine` with parse-only validation, automatic global function registration, sorted strategy listing, and reload-safe replacement of already registered Lua functions.
- Added Kotlin contract coverage for the binding interface and Rust coverage for Lua parse-only validation, auto-registration, and reload replacement behavior.

Validation:

- `cargo test -p ripdpi-strategy-lua --features lua-strategies --locked` — passed.
- `cargo test -p ripdpi-android --locked` — passed; `jni_facade_exports_stable_native_entrypoints` covers the four new JNI symbols.
- `cargo clippy -p ripdpi-strategy-lua --all-targets --features lua-strategies --locked -- -D warnings` — passed.
- `cargo clippy -p ripdpi-android --all-targets --locked -- -D warnings` — passed.
- `./gradlew :core:engine:ktlintCheck -Pripdpi.skipNativeBuild=true` — passed.
- `./gradlew :core:engine:testDebugUnitTest --tests com.poyka.ripdpi.core.StrategyEngineBindingsTest -Pripdpi.skipNativeBuild=true` — blocked before test execution by existing proto error in `core/data/model/src/main/proto/app_settings.proto`: field number `214` is already used by `strategy_chain_yaml`.

Remaining validation gap:

- Emulator/instrumented JNI validation was not run in this slice. The Rust symbol/linkage test and Kotlin interface contract are in place; real-device loading can be covered once the unrelated proto generation blocker is cleared.
