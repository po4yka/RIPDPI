---
title: Add ripdpi-strategy-lua crate with mlua integration
type: task
status: backlog
area: rust-native
priority: medium
owner: unassigned
parent: zapret2-feature-parity-epic
blocks: []
blocked_by: [add-ripdpi-strategy-registry-crate, add-ripdpi-strategy-trait-crate]
created: 2026-05-09
updated: 2026-05-09
---

- [ ] #task Add ripdpi-strategy-lua crate with mlua integration #repo/RIPDPI #area/rust-native #status/backlog 🔼

## Objective

Create the `ripdpi-strategy-lua` crate that embeds a Lua 5.4 VM (via `mlua` crate with vendored Lua) as an optional Cargo feature (`lua-strategies`). The crate provides a `LuaStrategyEngine` that manages the VM lifecycle, loads strategy scripts, and implements `DesyncStrategy` for each registered Lua function.

## Context

zapret2's entire strategy library lives in Lua (`/Users/po4yka/GitRep/zapret2/lua/zapret-antidpi.lua`, 1252 lines). To allow users to run their existing zapret2 Lua scripts on Android without modification, RIPDPI needs a Lua VM. The `mlua` crate provides safe Lua 5.4 bindings for Rust with the `send` feature that makes the `Lua` state `Send+Sync` (required for use in `StrategyRegistry` across threads). The Lua VM is a singleton per process: initialized once at startup, reused for all script calls. Per-connection state (`desync.conn`) is stored as a Lua table referenced by `RegistryKey` keyed on `FlowId`.

**Cargo feature:**
```toml
[features]
default = []
lua-strategies = ["dep:mlua"]

[dependencies]
mlua = { version = "0.10", features = ["lua54", "vendored", "send"], optional = true }
```

The `ripdpi-strategy-registry` crate conditionally links `ripdpi-strategy-lua` only when `lua-strategies` is enabled. The Android app `build.gradle` enables this feature for the `lua-variant` build flavor.

**`LuaStrategyEngine` API:**
```rust
pub struct LuaStrategyEngine {
    lua: Arc<Lua>,
    registered: HashMap<String, RegistryKey>,  // function name → Lua function ref
    conn_states: DashMap<FlowId, RegistryKey>, // per-connection state tables
}

impl LuaStrategyEngine {
    pub fn new() -> Result<Self, LuaError>;
    pub fn load_script(&self, path: &Path) -> Result<(), LuaError>;
    pub fn load_bytes(&self, name: &str, bytes: &[u8]) -> Result<(), LuaError>;
    pub fn register_function(&self, name: &str) -> Result<(), LuaError>;
    pub fn make_strategy(&self, func_name: &str, args: LuaTable) -> Box<dyn DesyncStrategy>;
}
```

## Acceptance criteria

- [ ] `ripdpi-strategy-lua` compiles with `features = ["lua-strategies"]`; zero compilation with empty features (no mlua dependency)
- [ ] `LuaStrategyEngine::new()` initializes a Lua 5.4 VM without panicking
- [ ] `load_bytes()` executes Lua bytecode without error; script-level errors are returned as `LuaError`, not panics
- [ ] `Lua` state is `Send + Sync` (verified by `static_assertions::assert_impl_all!(LuaStrategyEngine: Send, Sync)`)
- [ ] Per-connection state table created on first connection, cleaned up on connection close (no `FlowId` leak in `conn_states`)
- [ ] `make_strategy()` returns a `Box<dyn DesyncStrategy>` whose `plan()` calls the Lua function synchronously
- [ ] Script load errors surface as `StrategyError::ScriptLoad(String)` at strategy load time, not at packet processing time
- [ ] `cargo test -p ripdpi-strategy-lua --features lua-strategies` covers: VM init, load simple script, call function, verify return value

## Source references

- zapret2 Lua strategy library: `/Users/po4yka/GitRep/zapret2/lua/zapret-antidpi.lua` — full 1252-line reference; the API surface must match this
- zapret2 Lua lib helpers: `/Users/po4yka/GitRep/zapret2/lua/zapret-lib.lua` — `orchestrate()`, `verdict_aggregate()` for chain logic
- zapret2 conntrack (per-conn Lua state): `/Users/po4yka/GitRep/zapret2/nfq2/conntrack.h` — `t_ctrack.lua_state` field
- mlua crate docs: https://docs.rs/mlua/latest/mlua/ — the `send` feature and `RegistryKey` usage
- RIPDPI workspace Cargo: `native/rust/Cargo.toml` — where to add workspace member

## TDD workflow

1. **Write tests first** — before any implementation code, write tests that verify the Lua VM initializes, loads scripts, and returns errors correctly.
2. **Confirm red** — run `cargo test -p ripdpi-strategy-lua --features lua-strategies` and confirm tests fail because `LuaStrategyEngine` does not exist.
3. **Implement** — build the VM wrapper to make the failing tests pass.
4. **Confirm green** — run the full crate test suite; zero regressions.
5. **Refactor** — clean up while keeping tests green.

**Test files to create before implementation:**
- `native/rust/crates/ripdpi-strategy-lua/tests/vm_init.rs` — call `LuaStrategyEngine::new()` and assert `Ok`; fails until the struct and mlua init exist
- `native/rust/crates/ripdpi-strategy-lua/tests/load_valid_script.rs` — call `load_bytes("test", b"function hello() return 42 end")`; assert `Ok`; fails until `load_bytes` is implemented
- `native/rust/crates/ripdpi-strategy-lua/tests/load_invalid_script.rs` — call `load_bytes("bad", b"this is not lua {{{"))`; assert `Err(LuaError::ScriptLoad(_))`; fails until error propagation is implemented
- `native/rust/crates/ripdpi-strategy-lua/tests/send_sync.rs` — `static_assertions::assert_impl_all!(LuaStrategyEngine: Send, Sync)`; fails until mlua `send` feature is enabled
- `native/rust/crates/ripdpi-strategy-lua/tests/conn_state_persistence.rs` — load a script that increments `desync.conn.count`; call it twice for the same FlowId; assert count is 2 on second call; fails until per-connection state persistence is implemented
- `native/rust/crates/ripdpi-strategy-lua/tests/no_features_no_compile.rs` — in `build.rs` or a cfg-gated test, assert that building without `lua-strategies` feature produces no mlua symbols (zero-cost guard); fails if feature gate is misconfigured

## Definition of done

`cargo test -p ripdpi-strategy-lua --features lua-strategies` green; `LuaStrategyEngine` instantiates without error in a unit test. Tests were written and confirmed red before implementation began; the relevant test command is green with no regressions.
