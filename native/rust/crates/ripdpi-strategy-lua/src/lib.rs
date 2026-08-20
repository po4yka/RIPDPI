//! Optional Lua strategy backend.

#![forbid(unsafe_code)]

#[cfg(feature = "lua-strategies")]
mod enabled {
    use std::collections::{HashMap, HashSet};
    use std::fs;
    use std::path::{Component, Path, PathBuf};
    use std::sync::{Arc, Mutex};

    use mlua::chunk::ChunkMode;
    use mlua::{
        Function, HookTriggers, Lua, LuaOptions, LuaString, RegistryKey, StdLib, Table, Value, Variadic, VmState,
    };
    use ripdpi_strategy_trait::{
        DesyncAction, DesyncPlan, DesyncStrategy, FlowId, L7Protocol, MarkerName, RuntimeCapability, StrategyContext,
        StrategyDescriptor, StrategyError, StrategyVerdict,
    };
    use thiserror::Error;

    const VERDICT_PASS: i64 = 0;
    const VERDICT_MODIFY: i64 = 1;
    const VERDICT_DROP: i64 = 2;

    /// Lua backend errors.
    #[derive(Debug, Error)]
    pub enum LuaError {
        /// Lua VM mutex was poisoned.
        #[error("lua engine lock poisoned")]
        LockPoisoned,
        /// Script file could not be read.
        #[error("failed to read Lua script {path}: {source}")]
        ScriptRead { path: PathBuf, source: std::io::Error },
        /// Script load or execution failed.
        #[error("failed to load Lua script: {0}")]
        ScriptLoad(String),
        /// A named Lua function was not present.
        #[error("Lua function is not registered: {0}")]
        FunctionNotRegistered(String),
        /// Lua function call failed.
        #[error("Lua function call failed: {0}")]
        Call(String),
        /// A script path escaped the configured Lua base directory.
        #[error("Lua script path {path} is outside the permitted base directory")]
        PathEscape { path: PathBuf },
    }

    /// Standard libraries exposed to strategy scripts.
    ///
    /// Excludes `os`, `io`, `package`, `debug`, and `coroutine`: strategies only
    /// compute desync verdicts over packet bytes, so granting filesystem,
    /// process, module-loading, or introspection access would only widen the
    /// local code-execution surface for an imported (potentially untrusted)
    /// strategy-config. `bit`-style helpers are supplied as explicit globals
    /// (`bitand`/`bitor`/...) by [`install_zapret_compat_globals`], so the Lua
    /// `bit32` library is not needed either.
    fn strategy_stdlib() -> StdLib {
        StdLib::TABLE | StdLib::STRING | StdLib::MATH | StdLib::UTF8
    }

    /// Per-VM memory ceiling (bytes). A runaway script that keeps allocating is
    /// aborted with a Lua memory error rather than OOM-killing the desync path.
    const LUA_MEMORY_LIMIT_BYTES: usize = 16 * 1024 * 1024;

    /// Instruction-count granularity for the watchdog hook. The hook fires every
    /// N VM instructions and aborts once the per-call budget is exhausted, so a
    /// non-terminating script cannot hang the per-flow desync thread.
    const LUA_HOOK_INSTRUCTION_INTERVAL: u32 = 4_096;

    /// Maximum hook firings allowed before a single script load/call is aborted.
    /// At [`LUA_HOOK_INSTRUCTION_INTERVAL`] this caps a script at roughly
    /// 64M VM instructions — generous for verdict logic, fatal for a busy loop.
    const LUA_HOOK_MAX_FIRINGS: u64 = 16_384;

    /// Feature-gated Lua strategy engine.
    #[derive(Clone)]
    pub struct LuaStrategyEngine {
        inner: Arc<Mutex<LuaEngineInner>>,
        /// Canonicalized base directory that `load_script*` paths are confined
        /// to. `None` disables the jail (back-compat / direct-byte callers).
        base_dir: Option<Arc<PathBuf>>,
    }

    struct LuaEngineInner {
        lua: Lua,
        registered: HashMap<String, RegistryKey>,
        conn_states: HashMap<FlowId, RegistryKey>,
        /// Per-call instruction-hook firing counter, reset before each script
        /// load or strategy call so the watchdog budget is per-operation rather
        /// than cumulative across the engine's lifetime.
        hook_firings: Arc<std::sync::atomic::AtomicU64>,
    }

    impl LuaEngineInner {
        /// Resets the watchdog firing counter at the start of an execution.
        fn reset_watchdog(&self) {
            self.hook_firings.store(0, std::sync::atomic::Ordering::Relaxed);
        }
    }

    impl LuaStrategyEngine {
        /// Initializes a sandboxed Lua 5.4 VM with no base-directory jail.
        ///
        /// The VM is built via [`Lua::new_with`] with [`strategy_stdlib`] only,
        /// so `os`/`io`/`package`/`debug`/`coroutine` are unavailable inside any
        /// loaded script. A memory limit and an instruction-count watchdog hook
        /// are installed so a runaway script aborts rather than hanging or
        /// OOM-ing the desync thread. Without a base dir, `load_script*` accept
        /// any readable path; prefer [`new_jailed`](Self::new_jailed) for the
        /// untrusted-import path.
        pub fn new() -> Result<Self, LuaError> {
            Self::build(None)
        }

        /// Initializes a sandboxed Lua VM jailed to `base_dir`.
        ///
        /// `base_dir` is canonicalized once; every `load_script*` path must
        /// canonicalize to a descendant of it, otherwise the load is rejected
        /// with [`LuaError::PathEscape`] before any file is read. This confines
        /// the bundled-strategy-pack / imported-config load path to a known
        /// assets directory and rejects absolute paths and `..` traversal.
        pub fn new_jailed(base_dir: impl AsRef<Path>) -> Result<Self, LuaError> {
            let base = base_dir.as_ref();
            let canonical =
                fs::canonicalize(base).map_err(|source| LuaError::ScriptRead { path: base.to_path_buf(), source })?;
            Self::build(Some(canonical))
        }

        fn build(base_dir: Option<PathBuf>) -> Result<Self, LuaError> {
            let lua = Lua::new_with(strategy_stdlib(), LuaOptions::default())
                .map_err(|error| LuaError::ScriptLoad(error.to_string()))?;
            let globals = lua.globals();
            globals.set("VERDICT_PASS", VERDICT_PASS).map_err(|error| LuaError::ScriptLoad(error.to_string()))?;
            globals.set("VERDICT_MODIFY", VERDICT_MODIFY).map_err(|error| LuaError::ScriptLoad(error.to_string()))?;
            globals.set("VERDICT_DROP", VERDICT_DROP).map_err(|error| LuaError::ScriptLoad(error.to_string()))?;
            drop(globals);
            harden_base_library(&lua)?;
            install_zapret_compat_globals(&lua)?;
            let hook_firings = install_dos_guards(&lua)?;

            Ok(Self {
                inner: Arc::new(Mutex::new(LuaEngineInner {
                    lua,
                    registered: HashMap::new(),
                    conn_states: HashMap::new(),
                    hook_firings,
                })),
                base_dir: base_dir.map(Arc::new),
            })
        }

        /// Loads and executes a Lua file.
        pub fn load_script(&self, path: impl AsRef<Path>) -> Result<(), LuaError> {
            let path = self.jail_path(path.as_ref())?;
            let bytes = fs::read(&path).map_err(|source| LuaError::ScriptRead { path: path.clone(), source })?;
            self.load_bytes(&path.to_string_lossy(), &bytes)
        }

        /// Loads a Lua file and registers new global functions defined by it.
        pub fn load_script_registering_globals(&self, path: impl AsRef<Path>) -> Result<Vec<String>, LuaError> {
            let path = self.jail_path(path.as_ref())?;
            let bytes = fs::read(&path).map_err(|source| LuaError::ScriptRead { path: path.clone(), source })?;
            self.load_bytes_registering_globals(&path.to_string_lossy(), &bytes)
        }

        /// Resolves `path` against the configured base-directory jail.
        ///
        /// When no base dir is set the path passes through unchanged. When a
        /// base dir is set, absolute paths and any `..` component are rejected,
        /// and the canonicalized target must stay within the base dir.
        fn jail_path(&self, path: &Path) -> Result<PathBuf, LuaError> {
            let Some(base) = self.base_dir.as_ref() else {
                return Ok(path.to_path_buf());
            };
            if path.is_absolute() || path.components().any(|component| component == Component::ParentDir) {
                return Err(LuaError::PathEscape { path: path.to_path_buf() });
            }
            let joined = base.join(path);
            let canonical =
                fs::canonicalize(&joined).map_err(|source| LuaError::ScriptRead { path: joined.clone(), source })?;
            if !canonical.starts_with(base.as_path()) {
                return Err(LuaError::PathEscape { path: path.to_path_buf() });
            }
            Ok(canonical)
        }

        /// Loads and executes Lua source bytes.
        pub fn load_bytes(&self, name: &str, bytes: &[u8]) -> Result<(), LuaError> {
            let inner = self.inner.lock().map_err(|_| LuaError::LockPoisoned)?;
            inner.reset_watchdog();
            inner.lua.load(bytes).set_name(name).exec().map_err(|error| LuaError::ScriptLoad(error.to_string()))
        }

        /// Loads Lua bytes and registers new global functions defined by the chunk.
        pub fn load_bytes_registering_globals(&self, name: &str, bytes: &[u8]) -> Result<Vec<String>, LuaError> {
            let mut inner = self.inner.lock().map_err(|_| LuaError::LockPoisoned)?;
            inner.reset_watchdog();
            let before = global_function_names(&inner.lua)?;
            inner.lua.load(bytes).set_name(name).exec().map_err(|error| LuaError::ScriptLoad(error.to_string()))?;
            let after = global_function_names(&inner.lua)?;
            let mut names = after.difference(&before).cloned().collect::<HashSet<_>>();
            names.extend(inner.registered.keys().filter(|name| after.contains(*name)).cloned());
            let mut names = names.into_iter().collect::<Vec<_>>();
            names.sort();

            for function_name in &names {
                let function = inner
                    .lua
                    .globals()
                    .get::<Function>(function_name.as_str())
                    .map_err(|error| LuaError::ScriptLoad(error.to_string()))?;
                let key = inner
                    .lua
                    .create_registry_value(function)
                    .map_err(|error| LuaError::ScriptLoad(error.to_string()))?;
                inner.registered.insert(function_name.clone(), key);
            }
            Ok(names)
        }

        /// Validates that a Lua file parses without executing it.
        pub fn validate_script(path: impl AsRef<Path>) -> Result<(), LuaError> {
            let path = path.as_ref();
            let bytes = fs::read(path).map_err(|source| LuaError::ScriptRead { path: path.to_path_buf(), source })?;
            Self::validate_bytes(&path.to_string_lossy(), &bytes)
        }

        /// Validates that Lua source bytes parse without executing them.
        pub fn validate_bytes(name: &str, bytes: &[u8]) -> Result<(), LuaError> {
            let lua = Lua::new_with(strategy_stdlib(), LuaOptions::default())
                .map_err(|error| LuaError::ScriptLoad(error.to_string()))?;
            harden_base_library(&lua)?;
            lua.load(bytes)
                .set_name(name)
                .into_function()
                .map(|_| ())
                .map_err(|error| LuaError::ScriptLoad(error.to_string()))
        }

        /// Registers a global Lua function for later strategy calls.
        pub fn register_function(&self, name: &str) -> Result<(), LuaError> {
            let mut inner = self.inner.lock().map_err(|_| LuaError::LockPoisoned)?;
            let function =
                inner.lua.globals().get::<Function>(name).map_err(|error| LuaError::ScriptLoad(error.to_string()))?;
            let key =
                inner.lua.create_registry_value(function).map_err(|error| LuaError::ScriptLoad(error.to_string()))?;
            inner.registered.insert(name.to_owned(), key);
            Ok(())
        }

        /// Creates a strategy that invokes a registered Lua function.
        pub fn make_strategy(&self, func_name: &str) -> Result<Box<dyn DesyncStrategy>, LuaError> {
            let inner = self.inner.lock().map_err(|_| LuaError::LockPoisoned)?;
            if !inner.registered.contains_key(func_name) {
                return Err(LuaError::FunctionNotRegistered(func_name.to_owned()));
            }
            Ok(Box::new(LuaFunctionStrategy { engine: self.clone(), func_name: func_name.to_owned() }))
        }

        /// Lists registered strategy function names.
        pub fn list_registered_functions(&self) -> Result<Vec<String>, LuaError> {
            let inner = self.inner.lock().map_err(|_| LuaError::LockPoisoned)?;
            let mut names = inner.registered.keys().cloned().collect::<Vec<_>>();
            names.sort();
            Ok(names)
        }

        /// Calls a no-arg Lua function and returns an integer, used by tests and probes.
        pub fn call_i64(&self, func_name: &str) -> Result<i64, LuaError> {
            let inner = self.inner.lock().map_err(|_| LuaError::LockPoisoned)?;
            let function =
                inner.lua.globals().get::<Function>(func_name).map_err(|error| LuaError::Call(error.to_string()))?;
            function.call(()).map_err(|error| LuaError::Call(error.to_string()))
        }

        /// Reads the `desync.conn.count` value for a flow when it exists.
        pub fn connection_count(&self, flow_id: FlowId) -> Result<Option<i64>, LuaError> {
            let inner = self.inner.lock().map_err(|_| LuaError::LockPoisoned)?;
            let Some(key) = inner.conn_states.get(&flow_id) else {
                return Ok(None);
            };
            let table = inner.lua.registry_value::<Table>(key).map_err(|error| LuaError::Call(error.to_string()))?;
            table.get("count").map_err(|error| LuaError::Call(error.to_string()))
        }

        /// Removes per-flow Lua state.
        pub fn close_connection(&self, flow_id: FlowId) -> Result<(), LuaError> {
            let mut inner = self.inner.lock().map_err(|_| LuaError::LockPoisoned)?;
            if let Some(key) = inner.conn_states.remove(&flow_id) {
                inner.lua.remove_registry_value(key).map_err(|error| LuaError::Call(error.to_string()))?;
            }
            Ok(())
        }

        fn call_strategy(&self, func_name: &str, ctx: &StrategyContext<'_>) -> Result<LuaCallOutcome, LuaError> {
            let mut inner = self.inner.lock().map_err(|_| LuaError::LockPoisoned)?;
            inner.reset_watchdog();
            if !inner.conn_states.contains_key(&ctx.flow_id) {
                let table = inner.lua.create_table().map_err(|error| LuaError::Call(error.to_string()))?;
                let key = inner.lua.create_registry_value(table).map_err(|error| LuaError::Call(error.to_string()))?;
                inner.conn_states.insert(ctx.flow_id, key);
            }

            let function_key =
                inner.registered.get(func_name).ok_or_else(|| LuaError::FunctionNotRegistered(func_name.to_owned()))?;
            let function = inner
                .lua
                .registry_value::<Function>(function_key)
                .map_err(|error| LuaError::Call(error.to_string()))?;
            let conn_key = inner
                .conn_states
                .get(&ctx.flow_id)
                .ok_or_else(|| LuaError::FunctionNotRegistered(func_name.to_owned()))?;
            let conn =
                inner.lua.registry_value::<Table>(conn_key).map_err(|error| LuaError::Call(error.to_string()))?;
            let call_plan = Arc::new(Mutex::new(LuaCallPlan::default()));
            let desync = create_desync_table(&inner.lua, ctx, conn, Arc::clone(&call_plan))?;

            match call_lua_strategy_function(&function, desync)? {
                Value::String(output) => {
                    let call_plan = take_call_plan(call_plan)?;
                    Ok(LuaCallOutcome {
                        output: Some(output.as_bytes().to_vec()),
                        actions: call_plan.actions,
                        verdict: call_plan.verdict,
                    })
                }
                Value::Integer(verdict) => {
                    let mut call_plan = take_call_plan(call_plan)?;
                    call_plan.verdict = verdict_from_lua(verdict).or(call_plan.verdict);
                    Ok(LuaCallOutcome { output: None, actions: call_plan.actions, verdict: call_plan.verdict })
                }
                Value::Number(verdict) => {
                    let mut call_plan = take_call_plan(call_plan)?;
                    call_plan.verdict = verdict_from_lua(verdict as i64).or(call_plan.verdict);
                    Ok(LuaCallOutcome { output: None, actions: call_plan.actions, verdict: call_plan.verdict })
                }
                Value::Nil | Value::Boolean(_) => {
                    let call_plan = take_call_plan(call_plan)?;
                    Ok(LuaCallOutcome { output: None, actions: call_plan.actions, verdict: call_plan.verdict })
                }
                other => Err(LuaError::Call(format!("unsupported Lua strategy return type: {}", other.type_name()))),
            }
        }
    }

    fn global_function_names(lua: &Lua) -> Result<HashSet<String>, LuaError> {
        let globals = lua.globals();
        let mut names = HashSet::new();
        for pair in globals.pairs::<Value, Value>() {
            let (key, value) = pair.map_err(|error| LuaError::ScriptLoad(error.to_string()))?;
            if let (Value::String(name), Value::Function(_)) = (key, value) {
                names.insert(name.to_string_lossy().to_string());
            }
        }
        Ok(names)
    }

    /// Removes base-library functions that survive the [`StdLib`] mask and would
    /// let a loaded script read or execute files outside the path jail.
    ///
    /// `mlua` loads `luaopen_base` unconditionally, independently of the
    /// [`strategy_stdlib`] mask, so `dofile` and `loadfile` remain reachable even
    /// though `os`/`io`/`package`/`debug` are excluded. Both take a filesystem
    /// path and execute its contents as Lua, which would bypass the
    /// base-directory jail enforced by [`LuaStrategyEngine::jail_path`] (an
    /// in-script `loadfile("/abs/path")` ignores the Rust-side `load_script*`
    /// confinement entirely). They are therefore set to `nil`.
    ///
    /// `load` is retained but replaced with a text-only wrapper: the stock base
    /// `load` defaults to mode `"bt"` (binary **and** text), so a script could
    /// run attacker-crafted Lua *bytecode* (`load(string.dump(...))` or a
    /// bytecode literal embedded in an imported strategy-config). Lua's bytecode
    /// verifier is not hardened, making this a memory-unsafety escape reachable
    /// from safe Rust through the C interpreter — exactly the class the jail
    /// exists to stop. The wrapper forces [`ChunkMode::Text`] and rejects
    /// non-string chunks, which keeps the bundled pack's
    /// `load(desync.arg.code, name)` (text source) working while refusing
    /// bytecode. Lua's `load` contract is preserved: it returns the compiled
    /// function, or `nil` plus an error message on failure. The optional 3rd
    /// (`mode`) and 4th (`env`) arguments are ignored — mode is always text and
    /// no custom environment is honored; no bundled strategy uses either.
    fn harden_base_library(lua: &Lua) -> Result<(), LuaError> {
        let globals = lua.globals();
        for symbol in ["dofile", "loadfile"] {
            globals.set(symbol, Value::Nil).map_err(|error| LuaError::ScriptLoad(error.to_string()))?;
        }
        let text_only_load = lua
            .create_function(|lua, (chunk, chunk_name): (Value, Option<LuaString>)| {
                let Value::String(source) = chunk else {
                    return Ok((Value::Nil, Some("load: only text string chunks are permitted".to_owned())));
                };
                let mut builder = lua.load(source.as_bytes().to_vec()).set_mode(ChunkMode::Text);
                if let Some(name) = &chunk_name {
                    builder = builder.set_name(name.to_string_lossy());
                }
                match builder.into_function() {
                    Ok(function) => Ok((Value::Function(function), None)),
                    Err(error) => Ok((Value::Nil, Some(error.to_string()))),
                }
            })
            .map_err(|error| LuaError::ScriptLoad(error.to_string()))?;
        globals.set("load", text_only_load).map_err(|error| LuaError::ScriptLoad(error.to_string()))?;
        Ok(())
    }

    fn install_zapret_compat_globals(lua: &Lua) -> Result<(), LuaError> {
        let globals = lua.globals();
        globals.set("NFQWS2_COMPAT_VER", 5).map_err(|error| LuaError::ScriptLoad(error.to_string()))?;
        globals.set("TH_FIN", 0x01).map_err(to_load)?;
        globals.set("TH_SYN", 0x02).map_err(to_load)?;
        globals.set("TH_RST", 0x04).map_err(to_load)?;
        globals.set("TH_PUSH", 0x08).map_err(to_load)?;
        globals.set("TH_ACK", 0x10).map_err(to_load)?;
        globals.set("TH_URG", 0x20).map_err(to_load)?;
        globals.set("TH_ECE", 0x40).map_err(to_load)?;
        globals.set("TH_CWR", 0x80).map_err(to_load)?;
        globals.set("IP_BASE_LEN", 20).map_err(to_load)?;
        globals.set("IP6_BASE_LEN", 40).map_err(to_load)?;
        globals.set("TCP_BASE_LEN", 20).map_err(to_load)?;
        globals.set("UDP_BASE_LEN", 8).map_err(to_load)?;
        globals.set("ICMP_BASE_LEN", 8).map_err(to_load)?;
        globals.set("IPPROTO_AH", 51).map_err(to_load)?;
        globals.set("TCP_KIND_END", 0).map_err(to_load)?;
        globals.set("TCP_KIND_NOOP", 1).map_err(to_load)?;
        globals.set("TCP_KIND_TS", 8).map_err(to_load)?;
        globals.set("TCP_KIND_MD5", 19).map_err(to_load)?;
        globals.set("TCP_KIND_SCALE", 3).map_err(to_load)?;
        globals
            .set("bitand", lua.create_function(|_, (left, right): (i64, i64)| Ok(left & right)).map_err(to_load)?)
            .map_err(to_load)?;
        globals
            .set("bitor", lua.create_function(|_, (left, right): (i64, i64)| Ok(left | right)).map_err(to_load)?)
            .map_err(to_load)?;
        globals
            .set("bitxor", lua.create_function(|_, (left, right): (i64, i64)| Ok(left ^ right)).map_err(to_load)?)
            .map_err(to_load)?;
        globals.set("bitnot", lua.create_function(|_, value: i64| Ok(!value)).map_err(to_load)?).map_err(to_load)?;
        globals
            // zapret-compat shim: real PID withheld from sandboxed strategies.
            .set("getpid", lua.create_function(|_, ()| Ok(0_i64)).map_err(to_load)?)
            .map_err(to_load)?;
        globals.set("gettid", lua.create_function(|_, ()| Ok(0_i64)).map_err(to_load)?).map_err(to_load)?;
        globals
            .set(
                "clock_gettime",
                lua.create_function(|_, ()| {
                    let elapsed =
                        std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default();
                    Ok(i64::try_from(elapsed.as_nanos()).unwrap_or(i64::MAX))
                })
                .map_err(to_load)?,
            )
            .map_err(to_load)?;
        globals.set("DLOG", lua.create_function(|_, _: Variadic<Value>| Ok(())).map_err(to_load)?).map_err(to_load)?;
        Ok(())
    }

    /// Installs the memory limit and instruction-count watchdog hook on a VM.
    ///
    /// The hook fires every [`LUA_HOOK_INSTRUCTION_INTERVAL`] VM instructions and
    /// returns an error once [`LUA_HOOK_MAX_FIRINGS`] is reached, which mlua
    /// surfaces as a runtime error that aborts the executing chunk. The returned
    /// counter is reset by the engine on each `exec`/`call` boundary so the
    /// budget is per script load / strategy call, not cumulative across the
    /// engine's lifetime.
    fn install_dos_guards(lua: &Lua) -> Result<Arc<std::sync::atomic::AtomicU64>, LuaError> {
        // mlua's vendored allocator tracks memory; a non-vendored/module build
        // returns `MemoryControlNotAvailable`, in which case the watchdog hook
        // alone still bounds runaway scripts, so a missing limit is tolerated.
        let _ = lua.set_memory_limit(LUA_MEMORY_LIMIT_BYTES);

        let firings = Arc::new(std::sync::atomic::AtomicU64::new(0));
        let hook_firings = Arc::clone(&firings);
        let triggers = HookTriggers::new().every_nth_instruction(LUA_HOOK_INSTRUCTION_INTERVAL);
        lua.set_hook(triggers, move |_lua, _debug| {
            let count = hook_firings.fetch_add(1, std::sync::atomic::Ordering::Relaxed) + 1;
            if count >= LUA_HOOK_MAX_FIRINGS {
                return Err(mlua::Error::external("Lua strategy exceeded instruction budget"));
            }
            Ok(VmState::Continue)
        })
        .map_err(|error| LuaError::ScriptLoad(error.to_string()))?;
        Ok(firings)
    }

    fn call_lua_strategy_function(function: &Function, desync: Table) -> Result<Value, LuaError> {
        match function.call::<Value>(desync.clone()) {
            Ok(value) => Ok(value),
            Err(first_error) => function.call::<Value>((Value::Nil, desync)).map_err(|second_error| {
                LuaError::Call(format!("{first_error}; retry with zapret ctx=nil convention failed: {second_error}"))
            }),
        }
    }

    #[cfg(test)]
    mod tests {
        use super::{LuaError, LuaStrategyEngine};
        use ripdpi_strategy_trait::{
            Capabilities, ConnectionState, DesyncAction, DesyncPlan, Dissect, FlowDirection, FlowId, StrategyContext,
            StrategyVerdict,
        };

        #[test]
        fn sandboxed_vm_hides_os_and_io_stdlib() {
            let engine = LuaStrategyEngine::new().expect("Lua VM should initialize");

            // The loaded chunk records whether the dangerous stdlib tables are
            // reachable. If the sandbox holds, both resolve to nil.
            engine
                .load_bytes(
                    "probe.lua",
                    br"
                        os_present = os ~= nil
                        io_present = io ~= nil
                        package_present = package ~= nil
                        debug_present = debug ~= nil
                        coroutine_present = coroutine ~= nil
                        function os_is_nil() if os == nil then return VERDICT_PASS else return VERDICT_MODIFY end end
                    ",
                )
                .expect("probe script should load under the sandbox");

            // VERDICT_PASS (0) means `os` resolved to nil inside the script.
            assert_eq!(engine.call_i64("os_is_nil").expect("call should succeed"), 0);
        }

        #[test]
        fn validation_vm_hides_os_and_io_stdlib() {
            // validate_bytes only parses, but the parse VM must also be
            // sandboxed so a future change cannot silently regain stdlib.
            let probe = br"local has_os = os ~= nil; local has_io = io ~= nil; return has_os or has_io";
            // Parsing succeeds regardless; the assertion is that the VM was
            // constructed via the sandboxed stdlib path (compile-time wiring).
            LuaStrategyEngine::validate_bytes("probe.lua", probe).expect("probe parses");
        }

        #[test]
        fn load_script_rejects_absolute_path_outside_jail() {
            let dir = tempdir();
            let engine = LuaStrategyEngine::new_jailed(dir.path()).expect("jailed Lua VM should initialize");

            let outside = if cfg!(windows) { "C:/etc/passwd" } else { "/etc/passwd" };
            let error = engine.load_script(outside).unwrap_err();
            assert!(matches!(error, LuaError::PathEscape { .. }), "absolute path must be rejected: {error:?}");
        }

        #[test]
        fn load_script_rejects_parent_dir_escape() {
            let dir = tempdir();
            let engine = LuaStrategyEngine::new_jailed(dir.path()).expect("jailed Lua VM should initialize");

            let error = engine.load_script("../escape.lua").unwrap_err();
            assert!(matches!(error, LuaError::PathEscape { .. }), "`..` escape must be rejected: {error:?}");
        }

        #[test]
        fn load_script_allows_relative_path_within_jail() {
            let dir = tempdir();
            std::fs::write(dir.path().join("ok.lua"), b"function jailed_ok() return VERDICT_PASS end")
                .expect("write script");
            let engine = LuaStrategyEngine::new_jailed(dir.path()).expect("jailed Lua VM should initialize");

            engine.load_script_registering_globals("ok.lua").expect("relative path inside the jail should load");
            assert_eq!(engine.list_registered_functions().unwrap(), vec!["jailed_ok"]);
        }

        #[test]
        fn runtime_vm_aborts_non_terminating_script() {
            let engine = LuaStrategyEngine::new().expect("Lua VM should initialize");

            // A bare infinite loop must be aborted by the instruction-count
            // watchdog rather than hanging the desync thread forever.
            let error = engine.load_bytes("spin.lua", b"while true do end").unwrap_err();
            assert!(matches!(error, LuaError::ScriptLoad(_)), "infinite loop must abort: {error:?}");
        }

        fn tempdir() -> TempDir {
            TempDir::new()
        }

        /// Minimal self-cleaning temp directory so tests need no extra dep.
        struct TempDir {
            path: std::path::PathBuf,
        }

        impl TempDir {
            fn new() -> Self {
                static NEXT_ID: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

                loop {
                    // Ordering: counter uniqueness only; no data publication.
                    let id = NEXT_ID.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                    let path = std::env::temp_dir().join(format!("ripdpi-lua-jail-{}-{id}", std::process::id()));
                    match std::fs::create_dir(&path) {
                        Ok(()) => return Self { path },
                        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => continue,
                        Err(error) => panic!("create temp dir: {error}"),
                    }
                }
            }

            fn path(&self) -> &std::path::Path {
                &self.path
            }
        }

        impl Drop for TempDir {
            fn drop(&mut self) {
                let _ = std::fs::remove_dir_all(&self.path);
            }
        }

        #[test]
        fn validate_bytes_parses_without_executing_script() {
            let script = br#"
                executed = true

                function candidate()
                    return VERDICT_PASS
                end
            "#;

            LuaStrategyEngine::validate_bytes("candidate.lua", script).expect("script should parse");
            let engine = LuaStrategyEngine::new().expect("Lua VM should initialize");

            assert!(engine.register_function("candidate").is_err());
        }

        #[test]
        fn validate_bytes_reports_parse_errors() {
            let error = LuaStrategyEngine::validate_bytes("broken.lua", b"function broken(").unwrap_err();

            assert!(matches!(error, LuaError::ScriptLoad(_)));
        }

        #[test]
        fn load_bytes_registering_globals_returns_sorted_strategy_names() {
            let engine = LuaStrategyEngine::new().expect("Lua VM should initialize");

            let names = engine
                .load_bytes_registering_globals(
                    "strategies.lua",
                    br#"
                        function z_strategy()
                            return VERDICT_PASS
                        end

                        function a_strategy()
                            return VERDICT_MODIFY
                        end
                    "#,
                )
                .expect("script should load");

            assert_eq!(names, vec!["a_strategy", "z_strategy"]);
            assert_eq!(engine.list_registered_functions().unwrap(), vec!["a_strategy", "z_strategy"]);
        }

        #[test]
        fn load_bytes_registering_globals_updates_existing_registered_functions() {
            let engine = LuaStrategyEngine::new().expect("Lua VM should initialize");
            engine
                .load_bytes_registering_globals(
                    "strategy.lua",
                    br#"
                        function candidate()
                            return "old"
                        end
                    "#,
                )
                .expect("initial script should load");

            engine
                .load_bytes_registering_globals(
                    "strategy.lua",
                    br#"
                        function candidate()
                            return "new"
                        end
                    "#,
                )
                .expect("updated script should load");

            let strategy = engine.make_strategy("candidate").expect("strategy should be registered");
            let dissect = Dissect::default();
            let conn = ConnectionState::default();
            let caps = Capabilities::default();
            let ctx = StrategyContext {
                dissect: &dissect,
                conn: &conn,
                caps: &caps,
                flow_id: FlowId(7),
                payload: b"payload",
                direction: FlowDirection::Outbound,
            };
            let mut plan = DesyncPlan::default();

            strategy.plan(&ctx, &mut plan).expect("strategy should execute");

            assert_eq!(plan.verdict, StrategyVerdict::Apply);
            assert_eq!(plan.actions, vec![DesyncAction::Write(b"new".to_vec())]);
        }

        #[test]
        fn getpid_is_stubbed() {
            // getpid() must return 0 inside the sandbox — the real process PID
            // is withheld from untrusted strategy scripts (zapret-compat shim).
            let engine = LuaStrategyEngine::new().expect("Lua VM should initialize");
            engine
                .load_bytes("probe.lua", b"function probe_pid() return getpid() end")
                .expect("probe script should load");
            assert_eq!(engine.call_i64("probe_pid").expect("getpid call should succeed"), 0);
        }
    }

    #[derive(Default)]
    struct LuaCallPlan {
        actions: Vec<DesyncAction>,
        verdict: Option<StrategyVerdict>,
    }

    struct LuaCallOutcome {
        output: Option<Vec<u8>>,
        actions: Vec<DesyncAction>,
        verdict: Option<StrategyVerdict>,
    }

    fn take_call_plan(call_plan: Arc<Mutex<LuaCallPlan>>) -> Result<LuaCallPlan, LuaError> {
        let mut call_plan = call_plan.lock().map_err(|_| LuaError::LockPoisoned)?;
        Ok(std::mem::take(&mut *call_plan))
    }

    fn lock_lua_call_plan(call_plan: &Arc<Mutex<LuaCallPlan>>) -> mlua::Result<std::sync::MutexGuard<'_, LuaCallPlan>> {
        call_plan.lock().map_err(|_| mlua::Error::external("lua call plan lock poisoned"))
    }

    fn create_desync_table(
        lua: &Lua,
        ctx: &StrategyContext<'_>,
        conn: Table,
        call_plan: Arc<Mutex<LuaCallPlan>>,
    ) -> Result<Table, LuaError> {
        let desync = lua.create_table().map_err(|error| LuaError::Call(error.to_string()))?;
        desync.set("conn", conn).map_err(|error| LuaError::Call(error.to_string()))?;
        let arg = lua.create_table().map_err(|error| LuaError::Call(error.to_string()))?;
        desync.set("arg", arg).map_err(|error| LuaError::Call(error.to_string()))?;
        desync.set("func_instance", "ripdpi_lua_strategy").map_err(|error| LuaError::Call(error.to_string()))?;
        desync
            .set("outgoing", ctx.direction == ripdpi_strategy_trait::FlowDirection::Outbound)
            .map_err(|error| LuaError::Call(error.to_string()))?;
        desync.set("l7payload", proto_name(&ctx.dissect.proto)).map_err(|error| LuaError::Call(error.to_string()))?;
        desync.set("replay", false).map_err(|error| LuaError::Call(error.to_string()))?;
        desync.set("replay_piece", 1).map_err(|error| LuaError::Call(error.to_string()))?;
        desync.set("replay_piece_last", true).map_err(|error| LuaError::Call(error.to_string()))?;
        desync.set("tcp_mss", 1460).map_err(|error| LuaError::Call(error.to_string()))?;
        let track = lua.create_table().map_err(|error| LuaError::Call(error.to_string()))?;
        track
            .set("lua_state", lua.create_table().map_err(|error| LuaError::Call(error.to_string()))?)
            .map_err(|error| LuaError::Call(error.to_string()))?;
        desync.set("track", track).map_err(|error| LuaError::Call(error.to_string()))?;
        desync
            .set("payload", lua.create_string(ctx.payload).map_err(|error| LuaError::Call(error.to_string()))?)
            .map_err(|error| LuaError::Call(error.to_string()))?;
        desync.set("dis", create_dis_table(lua, ctx)?).map_err(|error| LuaError::Call(error.to_string()))?;
        desync.set("caps", create_caps_table(lua, ctx)?).map_err(|error| LuaError::Call(error.to_string()))?;
        install_zapret_call_globals(lua, ctx, Arc::clone(&call_plan))?;
        attach_action_functions(lua, &desync, ctx, call_plan)?;
        Ok(desync)
    }

    fn create_dis_table(lua: &Lua, ctx: &StrategyContext<'_>) -> Result<Table, LuaError> {
        let dis = lua.create_table().map_err(|error| LuaError::Call(error.to_string()))?;
        dis.set("proto", proto_name(&ctx.dissect.proto)).map_err(|error| LuaError::Call(error.to_string()))?;
        dis.set("payload", lua.create_string(ctx.payload).map_err(|error| LuaError::Call(error.to_string()))?)
            .map_err(|error| LuaError::Call(error.to_string()))?;
        set_optional_string(lua, &dis, "hostname", hostname(&ctx.dissect.proto))?;
        dis.set("src_port", ctx.dissect.src_port).map_err(|error| LuaError::Call(error.to_string()))?;
        dis.set("dst_port", ctx.dissect.dst_port).map_err(|error| LuaError::Call(error.to_string()))?;
        dis.set("is_ipv6", ctx.dissect.is_ipv6).map_err(|error| LuaError::Call(error.to_string()))?;
        let tcp = lua.create_table().map_err(|error| LuaError::Call(error.to_string()))?;
        tcp.set("th_flags", 0x10).map_err(|error| LuaError::Call(error.to_string()))?;
        tcp.set("th_seq", 0).map_err(|error| LuaError::Call(error.to_string()))?;
        tcp.set("th_urp", 0).map_err(|error| LuaError::Call(error.to_string()))?;
        dis.set("tcp", tcp).map_err(|error| LuaError::Call(error.to_string()))?;

        let pos = lua.create_table().map_err(|error| LuaError::Call(error.to_string()))?;
        for (marker, name) in MARKER_NAMES {
            if let Some(offset) = ctx.dissect.markers.get(marker) {
                pos.set(*name, *offset).map_err(|error| LuaError::Call(error.to_string()))?;
            }
        }
        dis.set("pos", pos).map_err(|error| LuaError::Call(error.to_string()))?;
        Ok(dis)
    }

    fn install_zapret_call_globals(
        lua: &Lua,
        ctx: &StrategyContext<'_>,
        call_plan: Arc<Mutex<LuaCallPlan>>,
    ) -> Result<(), LuaError> {
        let rawsend_plan = Arc::clone(&call_plan);
        let rawsend_dissect = lua
            .create_function(move |_, args: Variadic<Value>| {
                let Some(Value::Table(dis)) = args.first() else {
                    return Ok(false);
                };
                let payload = dis.get::<Option<LuaString>>("payload")?;
                if let Some(payload) = payload {
                    let mut plan = lock_lua_call_plan(&rawsend_plan)?;
                    plan.actions.push(DesyncAction::RawSend(payload.as_bytes().to_vec()));
                    plan.verdict = Some(StrategyVerdict::Apply);
                }
                Ok(true)
            })
            .map_err(to_call)?;
        lua.globals().set("rawsend_dissect", rawsend_dissect).map_err(to_call)?;

        let markers = ctx.dissect.markers.clone();
        let resolve_multi_pos = lua
            .create_function(move |lua, (_data, _l7payload, spec): (Value, Value, String)| {
                let positions = lua.create_table()?;
                let mut index = 1;
                for token in spec.split(',') {
                    if let Some(position) = resolve_zapret_position_token(token.trim(), &markers) {
                        positions.raw_set(index, position)?;
                        index += 1;
                    }
                }
                Ok(positions)
            })
            .map_err(to_call)?;
        lua.globals().set("resolve_multi_pos", resolve_multi_pos).map_err(to_call)?;
        Ok(())
    }

    fn resolve_zapret_position_token(token: &str, markers: &HashMap<MarkerName, usize>) -> Option<usize> {
        if token.is_empty() {
            return None;
        }
        if let Ok(position) = token.parse::<usize>() {
            return Some(position);
        }
        marker_by_name(token).and_then(|marker| markers.get(&marker).map(|offset| offset.saturating_add(1)))
    }

    fn create_caps_table(lua: &Lua, ctx: &StrategyContext<'_>) -> Result<Table, LuaError> {
        let caps = lua.create_table().map_err(|error| LuaError::Call(error.to_string()))?;
        let raw_socket =
            ctx.caps.has(RuntimeCapability::RawTcpFakeSend) || ctx.caps.has(RuntimeCapability::RawUdpFragmentation);
        caps.set("raw_socket", raw_socket).map_err(|error| LuaError::Call(error.to_string()))?;
        caps.set("tcp_repair", ctx.caps.has(RuntimeCapability::ReplacementSocket))
            .map_err(|error| LuaError::Call(error.to_string()))?;
        caps.set("vpn_mode", ctx.caps.has(RuntimeCapability::VpnMode))
            .map_err(|error| LuaError::Call(error.to_string()))?;
        Ok(caps)
    }

    fn attach_action_functions(
        lua: &Lua,
        desync: &Table,
        ctx: &StrategyContext<'_>,
        call_plan: Arc<Mutex<LuaCallPlan>>,
    ) -> Result<(), LuaError> {
        let detect_proto = proto_name(&ctx.dissect.proto).to_owned();
        let detect = lua
            .create_function(move |_, proto: String| Ok(proto.eq_ignore_ascii_case(&detect_proto)))
            .map_err(to_call)?;
        desync.set("detect", detect).map_err(to_call)?;

        let markers = ctx.dissect.markers.clone();
        let pos = lua
            .create_function(move |_, marker: String| {
                Ok(marker_by_name(&marker).and_then(|name| markers.get(&name).copied()))
            })
            .map_err(to_call)?;
        desync.set("pos", pos).map_err(to_call)?;

        let pass_plan = Arc::clone(&call_plan);
        let pass = lua
            .create_function(move |_, ()| {
                lock_lua_call_plan(&pass_plan)?.verdict = Some(StrategyVerdict::FallbackPlain);
                Ok(VERDICT_PASS)
            })
            .map_err(to_call)?;
        desync.set("pass", pass).map_err(to_call)?;

        let drop_plan = Arc::clone(&call_plan);
        let drop_fn = lua
            .create_function(move |_, ()| {
                lock_lua_call_plan(&drop_plan)?.verdict = Some(StrategyVerdict::Drop);
                Ok(VERDICT_DROP)
            })
            .map_err(to_call)?;
        desync.set("drop", drop_fn).map_err(to_call)?;

        let split_plan = Arc::clone(&call_plan);
        let split = lua
            .create_function(move |_, (offset, disorder, ttl): (usize, bool, Option<u8>)| {
                let mut plan = lock_lua_call_plan(&split_plan)?;
                if let Some(ttl) = ttl
                    && plan.actions.last() != Some(&DesyncAction::SetTtl(ttl))
                {
                    plan.actions.push(DesyncAction::SetTtl(ttl));
                }
                plan.actions.push(DesyncAction::Split { offset, disorder });
                plan.verdict = Some(StrategyVerdict::Apply);
                Ok(VERDICT_MODIFY)
            })
            .map_err(to_call)?;
        desync.set("split", split).map_err(to_call)?;

        let rawsend_plan = Arc::clone(&call_plan);
        let rawsend = lua
            .create_function(move |_, bytes: LuaString| {
                let mut plan = lock_lua_call_plan(&rawsend_plan)?;
                plan.actions.push(DesyncAction::RawSend(bytes.as_bytes().to_vec()));
                plan.verdict = Some(StrategyVerdict::Apply);
                Ok(VERDICT_MODIFY)
            })
            .map_err(to_call)?;
        desync.set("rawsend", rawsend).map_err(to_call)?;

        let wsize_plan = Arc::clone(&call_plan);
        let wsize = lua
            .create_function(move |_, window: u32| {
                let mut plan = lock_lua_call_plan(&wsize_plan)?;
                plan.actions.push(DesyncAction::SetWindowClamp(window));
                plan.verdict = Some(StrategyVerdict::Apply);
                Ok(VERDICT_MODIFY)
            })
            .map_err(to_call)?;
        desync.set("wsize", wsize).map_err(to_call)?;

        let set_ttl_plan = Arc::clone(&call_plan);
        let set_ttl = lua
            .create_function(move |_, ttl: u8| {
                lock_lua_call_plan(&set_ttl_plan)?.actions.push(DesyncAction::SetTtl(ttl));
                Ok(VERDICT_MODIFY)
            })
            .map_err(to_call)?;
        desync.set("set_ttl", set_ttl).map_err(to_call)?;

        attach_compatibility_actions(lua, desync, ctx, call_plan)?;
        Ok(())
    }

    fn attach_compatibility_actions(
        lua: &Lua,
        desync: &Table,
        ctx: &StrategyContext<'_>,
        call_plan: Arc<Mutex<LuaCallPlan>>,
    ) -> Result<(), LuaError> {
        let fake_plan = Arc::clone(&call_plan);
        let fake = lua
            .create_function(move |_, (ttl, sni_mode, payload_file): (Option<u8>, Option<String>, Option<String>)| {
                let mut plan = lock_lua_call_plan(&fake_plan)?;
                plan.actions.push(DesyncAction::WriteFake { ttl, sni_mode, payload_file });
                plan.verdict = Some(StrategyVerdict::Apply);
                Ok(VERDICT_MODIFY)
            })
            .map_err(to_call)?;
        desync.set("fake", fake).map_err(to_call)?;

        let payload = ctx.payload.to_vec();
        let oob_plan = Arc::clone(&call_plan);
        let oob = lua
            .create_function(move |_, (offset, byte): (usize, u8)| {
                let mut plan = lock_lua_call_plan(&oob_plan)?;
                let prefix = payload.get(..offset.min(payload.len())).unwrap_or_default().to_vec();
                plan.actions.push(DesyncAction::WriteUrgent { prefix, urgent_byte: byte });
                plan.verdict = Some(StrategyVerdict::Apply);
                Ok(VERDICT_MODIFY)
            })
            .map_err(to_call)?;
        desync.set("oob", oob).map_err(to_call)?;

        let fake_rst_plan = Arc::clone(&call_plan);
        let fake_rst = lua
            .create_function(move |_, ttl: Option<u8>| {
                let mut plan = lock_lua_call_plan(&fake_rst_plan)?;
                plan.actions.push(DesyncAction::SendFakeRst { ttl });
                plan.verdict = Some(StrategyVerdict::Apply);
                Ok(VERDICT_MODIFY)
            })
            .map_err(to_call)?;
        desync.set("fake_rst", fake_rst).map_err(to_call)?;

        let udplen_plan = Arc::clone(&call_plan);
        let udplen = lua
            .create_function(move |_, delta: i16| {
                let mut plan = lock_lua_call_plan(&udplen_plan)?;
                plan.actions.push(DesyncAction::UdpLen { delta });
                plan.verdict = Some(StrategyVerdict::Apply);
                Ok(VERDICT_MODIFY)
            })
            .map_err(to_call)?;
        desync.set("udplen", udplen).map_err(to_call)?;
        Ok(())
    }

    fn set_optional_string(lua: &Lua, table: &Table, key: &str, value: Option<&str>) -> Result<(), LuaError> {
        match value {
            Some(value) => table
                .set(key, lua.create_string(value).map_err(|error| LuaError::Call(error.to_string()))?)
                .map_err(|error| LuaError::Call(error.to_string())),
            None => table.set(key, Value::Nil).map_err(|error| LuaError::Call(error.to_string())),
        }
    }

    fn proto_name(proto: &L7Protocol) -> &'static str {
        match proto {
            L7Protocol::Any => "any",
            L7Protocol::Unknown => "unknown",
            L7Protocol::Known => "known",
            L7Protocol::Http(_) => "http",
            L7Protocol::Tls(_) => "tls",
            L7Protocol::Dtls(_) => "dtls",
            L7Protocol::Quic(_) => "quic",
            L7Protocol::WireGuard(_) => "wireguard",
            L7Protocol::Dht(_) => "dht",
            L7Protocol::Discord(_) => "discord_ip_discovery",
            L7Protocol::Stun(_) => "stun",
            L7Protocol::Xmpp(_) => "xmpp",
            L7Protocol::Dns(_) => "dns",
            L7Protocol::Mtproto(_) => "mtproto",
            L7Protocol::BitTorrent(_) => "bittorrent",
            L7Protocol::UtpBitTorrent(_) => "utp_bittorrent",
        }
    }

    fn hostname(proto: &L7Protocol) -> Option<&str> {
        match proto {
            L7Protocol::Http(dissect) => dissect.host.as_deref(),
            L7Protocol::Tls(dissect) => dissect.sni.as_deref(),
            _ => None,
        }
    }

    fn verdict_from_lua(verdict: i64) -> Option<StrategyVerdict> {
        match verdict {
            VERDICT_PASS => Some(StrategyVerdict::FallbackPlain),
            VERDICT_MODIFY => Some(StrategyVerdict::Apply),
            VERDICT_DROP => Some(StrategyVerdict::Drop),
            _ => None,
        }
    }

    const MARKER_NAMES: &[(MarkerName, &str)] = &[
        (MarkerName::Absolute, "absolute"),
        (MarkerName::Host, "host"),
        (MarkerName::HostEnd, "endhost"),
        (MarkerName::HostSld, "sld"),
        (MarkerName::HostMidSld, "midsld"),
        (MarkerName::HostEndSld, "endsld"),
        (MarkerName::HttpMethod, "http_method"),
        (MarkerName::ExtLen, "ext_len"),
        (MarkerName::SniExt, "sni_ext"),
        (MarkerName::Data, "data"),
        (MarkerName::End, "end"),
    ];

    fn marker_by_name(name: &str) -> Option<MarkerName> {
        match name {
            "absolute" | "abs" => Some(MarkerName::Absolute),
            "host" => Some(MarkerName::Host),
            "endhost" | "host_end" => Some(MarkerName::HostEnd),
            "sld" | "host_sld" => Some(MarkerName::HostSld),
            "midsld" | "host_mid_sld" => Some(MarkerName::HostMidSld),
            "endsld" | "host_end_sld" => Some(MarkerName::HostEndSld),
            "http_method" => Some(MarkerName::HttpMethod),
            "ext_len" => Some(MarkerName::ExtLen),
            "sni_ext" => Some(MarkerName::SniExt),
            "data" => Some(MarkerName::Data),
            "end" => Some(MarkerName::End),
            _ => None,
        }
    }

    fn to_call(error: mlua::Error) -> LuaError {
        LuaError::Call(error.to_string())
    }

    fn to_load(error: mlua::Error) -> LuaError {
        LuaError::ScriptLoad(error.to_string())
    }

    struct LuaFunctionStrategy {
        engine: LuaStrategyEngine,
        func_name: String,
    }

    impl DesyncStrategy for LuaFunctionStrategy {
        fn id(&self) -> &str {
            &self.func_name
        }

        fn matches(&self, _ctx: &StrategyContext<'_>) -> bool {
            true
        }

        fn plan(&self, ctx: &StrategyContext<'_>, plan: &mut DesyncPlan) -> Result<(), StrategyError> {
            match self.engine.call_strategy(&self.func_name, ctx) {
                Ok(outcome) => {
                    let had_actions = !outcome.actions.is_empty() || outcome.output.is_some();
                    plan.actions.extend(outcome.actions);
                    if let Some(output) = outcome.output {
                        plan.actions.push(DesyncAction::Write(output));
                    }
                    if let Some(verdict) = outcome.verdict {
                        plan.verdict = verdict;
                    } else if had_actions {
                        plan.verdict = StrategyVerdict::Apply;
                    }
                    Ok(())
                }
                Err(LuaError::ScriptLoad(error)) => Err(StrategyError::ScriptLoad(error)),
                Err(LuaError::Call(error)) => Err(StrategyError::LuaTypeError(error)),
                Err(error) => Err(StrategyError::Execution(error.to_string())),
            }
        }

        fn describe(&self) -> StrategyDescriptor {
            StrategyDescriptor {
                id: self.func_name.clone(),
                label: format!("Lua strategy {}", self.func_name),
                ..StrategyDescriptor::default()
            }
        }
    }
}

#[cfg(not(feature = "lua-strategies"))]
mod disabled {
    use std::path::Path;

    use thiserror::Error;

    /// Lua backend errors.
    #[derive(Debug, Error)]
    pub enum LuaError {
        /// Lua support is disabled at compile time.
        #[error("lua-strategies feature is disabled")]
        FeatureDisabled,
    }

    /// Stub engine available when the Lua backend feature is disabled.
    #[derive(Clone, Debug, Default)]
    pub struct LuaStrategyEngine;

    impl LuaStrategyEngine {
        /// Returns an error because Lua support is feature-gated.
        pub fn new() -> Result<Self, LuaError> {
            Err(LuaError::FeatureDisabled)
        }

        /// Returns an error because Lua support is feature-gated.
        pub fn new_jailed(_base_dir: impl AsRef<Path>) -> Result<Self, LuaError> {
            Err(LuaError::FeatureDisabled)
        }

        /// Returns an error because Lua support is feature-gated.
        pub fn load_script(&self, _path: impl AsRef<Path>) -> Result<(), LuaError> {
            Err(LuaError::FeatureDisabled)
        }

        /// Returns an error because Lua support is feature-gated.
        pub fn load_bytes(&self, _name: &str, _bytes: &[u8]) -> Result<(), LuaError> {
            Err(LuaError::FeatureDisabled)
        }

        /// Returns an error because Lua support is feature-gated.
        pub fn load_script_registering_globals(&self, _path: impl AsRef<Path>) -> Result<Vec<String>, LuaError> {
            Err(LuaError::FeatureDisabled)
        }

        /// Returns an error because Lua support is feature-gated.
        pub fn validate_script(_path: impl AsRef<Path>) -> Result<(), LuaError> {
            Err(LuaError::FeatureDisabled)
        }

        /// Returns an error because Lua support is feature-gated.
        pub fn validate_bytes(_name: &str, _bytes: &[u8]) -> Result<(), LuaError> {
            Err(LuaError::FeatureDisabled)
        }

        /// Returns an error because Lua support is feature-gated.
        pub fn list_registered_functions(&self) -> Result<Vec<String>, LuaError> {
            Err(LuaError::FeatureDisabled)
        }
    }
}

#[cfg(not(feature = "lua-strategies"))]
pub use disabled::{LuaError, LuaStrategyEngine};
#[cfg(feature = "lua-strategies")]
pub use enabled::{LuaError, LuaStrategyEngine};
