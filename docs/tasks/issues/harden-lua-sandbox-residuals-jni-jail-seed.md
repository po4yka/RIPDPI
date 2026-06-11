---
title: "Harden Lua sandbox residuals: JNI jail seed + egress base dir"
type: task
status: todo
area: rust-native
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-06-11
updated: 2026-06-11
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

- [ ] JNI jail base is seeded from `<filesDir>/lua` (or first-pin asserted under `filesDir`);
      a test/assert covers the "first load is an arbitrary path" case.
- [ ] Production egress strategy loader uses an absolute strategy-file directory as its jail base.
- [ ] `cargo nextest run -p ripdpi-strategy-lua -p ripdpi-strategy-config -p ripdpi-android
      --locked` green; clippy clean; AI-generated diff gets a `pr-reviewer` pass
      (security boundary) per `llm-rust-prompts.md`.

## References

- Skeptical security review, 2026-06-11 (Lua sandbox base-lib hardening).
- `.claude/rules/llm-rust-prompts.md` (security-boundary diff gate),
  `.claude/rules/vpnservice-protect-invariant.md` (adjacent JNI boundary discipline).
- Prior commits: `472622b1` (initial sandbox + path jail), the base-lib hardening that
  closed `dofile`/`loadfile` + bytecode-`load`.
