---
title: "Harden Lua sandbox residuals: JNI jail seed + egress base dir"
type: task
status: done
area: rust-native
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-06-11
updated: 2026-06-14
source_wiki_pages: []
linked_task: null
---

## Motivation

The Lua strategy-config VM sandbox landed in `472622b1` (os/io/package/debug/coroutine
excluded; `load_script*` + `resolve_hosts` path jail; memory limit + instruction-count
watchdog) and was extended to close two confirmed local code-execution escapes:

- `dofile` / `loadfile` survived `mlua`'s `StdLib` mask (the base library is loaded
  unconditionally), giving any loaded script an arbitrary-file read+execute primitive that
  bypassed the path jail. **Closed**: `harden_base_library` sets both to `nil`.
- The retained base `load` defaulted to mode `"bt"`, so a script could execute
  attacker-crafted Lua **bytecode** (`load(string.dump(...))` or a bytecode literal in an
  imported config) — a memory-unsafety escape past `#![forbid(unsafe_code)]`. **Closed**:
  `load` is replaced with a text-only wrapper (`ChunkMode::Text`, non-string chunks
  rejected); the bundled zapret pack's `load(desync.arg.code, name)` (text) still works.

A skeptical security review of the merged + hardened state flagged the following
**residual, lower-severity** items that are out of scope of the code-execution fix above
and warrant their own change.

## Proposed change (priority order)

1. **[MEDIUM] Seed the JNI script jail explicitly instead of trust-on-first-use.**
   `native/rust/crates/ripdpi-android/src/ffi/lua_bridge.rs` pins `LUA_JNI_SCRIPT_JAIL`
   (an `OnceLock<PathBuf>`) to the canonical parent of the **first** `luaLoadScript` path
   (TOFU). The only reachable Kotlin caller (`StrategyConfigRoute.kt`, the advanced-script
   field) passes a user-supplied path straight to `luaLoadScript`, and nothing guarantees
   the bundled `<filesDir>/lua/` extraction loads first. If a user/attacker-influenced path
   is the first load in a process, the jail pins to *that* directory. Fix: initialize the
   jail base from `LuaAssetManager`'s `<filesDir>/lua` (pass it across JNI once at engine
   init), or assert the first pin is under `filesDir`, rather than TOFU.

2. **[LOW] Thread an absolute base dir into the production TUN egress strategy loader.**
   `ripdpi-tunnel-intercept` (`egress.rs`) / `ripdpi-tunnel-core` (`io_loop/setup.rs`)
   build the strategy loader with base_dir `"."`. `new_jailed(".")` canonicalizes to the
   Android process CWD, which is ill-defined, so any `lua`-step `script_paths` resolve
   against it. The YAML here is app-generated (not the untrusted-import surface), so the jail
   is active but meaningless; pass the real, absolute strategy-file directory as the jail base.

## Acceptance criteria

- [x] JNI jail base is seeded from `<filesDir>/lua` (or first-pin asserted under `filesDir`);
      a test/assert covers the "first load is an arbitrary path" case.
- [x] Production egress strategy loader uses an absolute strategy-file directory as its jail base.
- [x] `cargo nextest run -p ripdpi-strategy-lua -p ripdpi-strategy-config -p ripdpi-android
      --locked` green; clippy clean; AI-generated diff gets a `pr-reviewer` pass
      (security boundary) per `llm-rust-prompts.md`.

## Work log

**2026-06-14 — both residuals landed.**

1. **[MEDIUM] JNI jail seed (TOFU removed).** `ripdpi-android/src/ffi/lua_bridge.rs`:
   - `luaLoadScript` now takes the canonical `<filesDir>/lua` `base_dir` alongside `path`; the
     first load seeds `LUA_JNI_SCRIPT_JAIL` from it (first-seed-wins via `OnceLock::set`) and
     every load — first included — is confined to it. Folding the base into the load (rather than
     a separate `luaSeedScriptJail` export) keeps the **`nm` symbol surface unchanged**, so
     `jni-symbols.baseline` needs no edit (that file is owner-maintained on `main`, and the
     `gradle-static-analysis` PR guard forbids baseline changes in PRs).
   - `jail_jni_script_path` no longer trust-on-first-use pins. Jail resolution is split into the
     pure, unit-tested `resolve_in_jail` (unseeded → `JailNotSeeded`, kept as defence though the
     folded load always seeds first; seeded → containment via `enforce_jni_jail`).
   - Kotlin wiring: `StrategyEngineBindings.luaLoadScript(baseDir, path)` (+ native `external` +
     `ProcessGlobal` forward under the mutation lock), `StrategyConfigRuntime.loadLuaScript(baseDir, path)`,
     and `StrategyConfigRoute` computes `LuaAssetManager.ensureExtracted(<filesDir>/lua)` (IO
     dispatcher) and passes it into the load. All fakes + the JNI instrumented test updated; a new
     instrumented test asserts an existing file *outside* the jail is rejected.
   - The `lib.rs` compile-time JNI signature assertion for `luaLoadScript` updated to the 2-string
     form. Tests: `unseeded_jail_rejects_any_load`, `seeded_jail_rejects_first_arbitrary_load`,
     `seeded_jail_accepts_in_jail_load` (the "first load is an arbitrary path" case).

2. **[LOW] Egress base dir.** New optional `MiscConfig.lua_script_base_dir` (additive serde
   field — no `Tun2SocksConfigSchemaVersion` bump) threaded Kotlin → `TunnelConfigPayload`
   (`luaScriptBaseDir`) → `misc_config_from_payload` → `io_loop/setup.rs`, which now builds the
   egress interceptor with `new_with_base_dir(<filesDir>/lua)` instead of `"."`. Kotlin supplies
   it from the protect socket's parent dir (both live directly under `<filesDir>`).
   `contract-fixtures/tunnel_config_fields.json` updated; the Rust manifest test and the Kotlin
   subset contract test both pass. Tests: `threads_absolute_lua_script_base_dir_from_payload`,
   `blank_lua_script_base_dir_is_dropped`, extended `maps_synack_runtime_fields_to_misc_config`.

Verification: `cargo nextest -p ripdpi-android -p ripdpi-tunnel-config -p ripdpi-tunnel-android
-p ripdpi-tunnel-core -p ripdpi-strategy-lua -p ripdpi-strategy-config --locked` green; `clippy
-D warnings` clean; `cargo fmt --check` clean; core+app Kotlin compile (incl. androidTest) +
ktlint + detekt clean. Code-execution escapes (`dofile`/`loadfile`, bytecode-`load`) were
already closed earlier; this closes the lower-severity residuals.

## References

- Skeptical security review, 2026-06-11 (Lua sandbox base-lib hardening).
- `.claude/rules/llm-rust-prompts.md` (security-boundary diff gate),
  `.claude/rules/vpnservice-protect-invariant.md` (adjacent JNI boundary discipline).
- Prior commits: `472622b1` (initial sandbox + path jail), the base-lib hardening that
  closed `dofile`/`loadfile` + bytecode-`load`.
