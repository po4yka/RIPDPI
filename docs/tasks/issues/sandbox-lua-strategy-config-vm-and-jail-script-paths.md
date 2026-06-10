---
title: "Sandbox the Lua strategy-config VM and jail script paths"
type: task
status: todo
area: rust-native
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-06-10
updated: 2026-06-10
source_wiki_pages: []
linked_task: null
---

## Motivation

A 2026-06-10 external security review found a real local code-execution defect in the Lua strategy engine, and it is verified in source.

`ripdpi-strategy-lua/src/lib.rs:58` (the runtime VM) and `:130` (the validation VM) build the interpreter with `Lua::new()`, which loads the **full Lua 5.4 standard library** — `os`, `io`, `package`, `debug`, `coroutine`. The apply path (`load_bytes` / `load_script_registering_globals`, lib.rs:90/96) calls `.exec()` on the script. So a Lua step inside an imported strategy-config (a `lua`-kind step) executes **with `os`/`io`/`package`/`debug` available, in the app UID**. Reachable via the JNI surface (`StrategyEngineNativeBindings.luaLoadScript` / `luaReloadConfig`) from a save→apply of an imported strategy-config.

Concrete impact for a censorship-circumvention app: read app-private files (profiles, Reality keys, server addresses of contacts in RU), `os.execute` within the app UID, exfiltrate via `io`/`os`. This is precisely the compromise class the project exists to prevent.

The validation path is *not* the hole — `validate_bytes` (lib.rs:130) uses `.into_function()` (parse only, no exec), so `validateStrategyConfigText → validate_bytes` correctly does not run the script at validation time. But it neither sandboxes nor jails anything, so save→apply still executes it. Separately, `load_script` / `load_script_registering_globals` accept an arbitrary `path` with no base-directory confinement.

The crate is otherwise disciplined (`#![forbid(unsafe_code)]` at lib.rs:3), which is what makes the default VM sandbox config look like a missed corner rather than systemic negligence.

## Proposed change (priority order)

1. **Drop dangerous stdlib (one line, ~90% of impact).** Replace `Lua::new()` at lib.rs:58 and :130 with `Lua::new_with(StdLib::TABLE | StdLib::STRING | StdLib::MATH | StdLib::BIT, LuaOptions::default())` — exclude `os`, `io`, `package`, `debug`, `coroutine` unless a real strategy needs them (they do not — strategies compute desync verdicts over packet bytes). mlua exposes `new_with`.
2. **Jail script paths.** In `load_script` / `load_script_registering_globals`, canonicalize the path and assert `starts_with(lua_base_dir)`; reject absolute paths and `..` traversal. Apply the same discipline to `resolve_hosts` in `ripdpi-strategy-config/src/lib.rs`.
3. **DoS guards on the desync thread.** Set `lua.set_memory_limit(...)` and an instruction-count hook (`lua.set_hook`) so a runaway script cannot hang or OOM the per-flow desync path.
4. **Untrusted-import warning (if third-party config import is a product requirement).** Surface an explicit "untrusted import" warning specifically for `lua`-kind steps (the UI already has `WarningBanner`).

## Acceptance criteria

- [ ] PR confirms current state at `ripdpi-strategy-lua/src/lib.rs:58` and `:130` (`Lua::new()`, full stdlib).
- [ ] Both VMs are constructed via `Lua::new_with(...)` excluding `os`/`io`/`package`/`debug`/`coroutine`; a unit test asserts `os` and `io` resolve to `nil` inside a loaded script.
- [ ] `load_script*` reject paths outside the lua base dir (absolute or `..` escape) — covered by a path-escape rejection test; same applied to `resolve_hosts`.
- [ ] Memory limit + instruction-count hook installed on the runtime VM, with a test that a non-terminating script is aborted rather than hanging the desync thread.
- [ ] (If import-from-untrusted stays a feature) `lua`-kind steps render the untrusted-import warning.
- [ ] `cargo nextest run -p ripdpi-strategy-lua -p ripdpi-strategy-config --locked` green; clippy clean. AI-generated diff gets a `pr-reviewer` pass (touches a security boundary) per `llm-rust-prompts.md`.

## Risks / open questions

- Confirm no shipped strategy script actually depends on `os`/`io`/`coroutine` (grep the strategy-pack Lua assets); if one does, that dependency is itself a red flag to remove, not a reason to keep the stdlib.
- `vendored`+`send` mlua features are already enabled (`ripdpi-strategy-lua/Cargo.toml`); `new_with`, `set_memory_limit`, `set_hook` are all available on that build.
- Jailing must not break the legitimate bundled-strategy-pack load path — those live under a known assets dir; make that the base dir.

## References

- External security review, 2026-06-10 (Lua sandbox finding; kill-switch/DNS-leak area judged correct).
- `.claude/rules/llm-rust-prompts.md` (security-boundary diff gate), `desync-engine` skill, `ws-tunnel-telegram`/strategy-lua skill.
- Reachability surface: `core/engine/.../StrategyEngineNativeBindings.kt` (`luaLoadScript`/`luaReloadConfig`/`validateStrategyConfigText`).
