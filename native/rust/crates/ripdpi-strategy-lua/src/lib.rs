//! Optional Lua strategy backend.

#[cfg(feature = "lua-strategies")]
mod enabled {
    use std::collections::HashMap;
    use std::fs;
    use std::path::{Path, PathBuf};
    use std::sync::{Arc, Mutex};

    use mlua::{Function, Lua, RegistryKey, Table, Value};
    use ripdpi_strategy_trait::{
        DesyncAction, DesyncPlan, DesyncStrategy, FlowId, StrategyContext, StrategyDescriptor, StrategyError,
        StrategyVerdict,
    };
    use thiserror::Error;

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
    }

    /// Feature-gated Lua strategy engine.
    #[derive(Clone)]
    pub struct LuaStrategyEngine {
        inner: Arc<Mutex<LuaEngineInner>>,
    }

    struct LuaEngineInner {
        lua: Lua,
        registered: HashMap<String, RegistryKey>,
        conn_states: HashMap<FlowId, RegistryKey>,
    }

    impl LuaStrategyEngine {
        /// Initializes a Lua 5.4 VM.
        pub fn new() -> Result<Self, LuaError> {
            Ok(Self {
                inner: Arc::new(Mutex::new(LuaEngineInner {
                    lua: Lua::new(),
                    registered: HashMap::new(),
                    conn_states: HashMap::new(),
                })),
            })
        }

        /// Loads and executes a Lua file.
        pub fn load_script(&self, path: impl AsRef<Path>) -> Result<(), LuaError> {
            let path = path.as_ref();
            let bytes = fs::read(path).map_err(|source| LuaError::ScriptRead { path: path.to_path_buf(), source })?;
            self.load_bytes(&path.to_string_lossy(), &bytes)
        }

        /// Loads and executes Lua source bytes.
        pub fn load_bytes(&self, name: &str, bytes: &[u8]) -> Result<(), LuaError> {
            let inner = self.inner.lock().map_err(|_| LuaError::LockPoisoned)?;
            inner.lua.load(bytes).set_name(name).exec().map_err(|error| LuaError::ScriptLoad(error.to_string()))
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

        fn call_strategy(&self, func_name: &str, ctx: &StrategyContext<'_>) -> Result<Option<Vec<u8>>, LuaError> {
            let mut inner = self.inner.lock().map_err(|_| LuaError::LockPoisoned)?;
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
            let desync = inner.lua.create_table().map_err(|error| LuaError::Call(error.to_string()))?;
            desync.set("conn", conn).map_err(|error| LuaError::Call(error.to_string()))?;
            let payload = inner.lua.create_string(ctx.payload).map_err(|error| LuaError::Call(error.to_string()))?;
            desync.set("payload", payload).map_err(|error| LuaError::Call(error.to_string()))?;

            match function.call::<Value>(desync).map_err(|error| LuaError::Call(error.to_string()))? {
                Value::String(output) => Ok(Some(output.as_bytes().to_vec())),
                Value::Nil | Value::Boolean(_) | Value::Integer(_) | Value::Number(_) => Ok(None),
                other => Err(LuaError::Call(format!("unsupported Lua strategy return type: {}", other.type_name()))),
            }
        }
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
                Ok(Some(output)) => {
                    plan.actions.push(DesyncAction::Write(output));
                    plan.verdict = StrategyVerdict::Apply;
                    Ok(())
                }
                Ok(None) => Ok(()),
                Err(LuaError::ScriptLoad(error)) => Err(StrategyError::ScriptLoad(error)),
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
        pub fn load_script(&self, _path: impl AsRef<Path>) -> Result<(), LuaError> {
            Err(LuaError::FeatureDisabled)
        }

        /// Returns an error because Lua support is feature-gated.
        pub fn load_bytes(&self, _name: &str, _bytes: &[u8]) -> Result<(), LuaError> {
            Err(LuaError::FeatureDisabled)
        }
    }
}

#[cfg(not(feature = "lua-strategies"))]
pub use disabled::{LuaError, LuaStrategyEngine};
#[cfg(feature = "lua-strategies")]
pub use enabled::{LuaError, LuaStrategyEngine};
