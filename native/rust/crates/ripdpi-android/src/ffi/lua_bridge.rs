use std::path::PathBuf;
use std::sync::Mutex;

use android_support::ffi_boundary;
use jni::objects::{JObject, JObjectArray, JString};
use jni::sys::{jobjectArray, jstring};
use jni::{Env, EnvUnowned, Outcome};
use once_cell::sync::Lazy;
use ripdpi_strategy_lua::{LuaError, LuaStrategyEngine};

static LUA_ENGINE: Lazy<Result<Mutex<LuaStrategyEngine>, String>> =
    Lazy::new(|| LuaStrategyEngine::new().map(Mutex::new).map_err(|error| error.to_string()));
static LOADED_SCRIPT_PATHS: Lazy<Mutex<Vec<PathBuf>>> = Lazy::new(|| Mutex::new(Vec::new()));

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_StrategyEngineNativeBindings_luaLoadScript(
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
    path: JString<'_>,
) -> jstring {
    ffi_boundary(core::ptr::null_mut(), move || {
        nullable_error_entry(env, path, |path| {
            engine()?.load_script_registering_globals(&path)?;
            remember_loaded_path(path)?;
            Ok(())
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_StrategyEngineNativeBindings_luaReloadConfig(
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
) -> jstring {
    ffi_boundary(core::ptr::null_mut(), move || {
        let mut env = env;
        match env
            .with_env(|env| -> jni::errors::Result<jstring> {
                let result = reload_loaded_scripts().map_err(|error| error.to_string());
                error_to_nullable_jstring(env, result)
            })
            .into_outcome()
        {
            Outcome::Ok(value) => value,
            _ => std::ptr::null_mut(),
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_StrategyEngineNativeBindings_luaListStrategies(
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
) -> jobjectArray {
    ffi_boundary(core::ptr::null_mut(), move || {
        let mut env = env;
        match env
            .with_env(|env| -> jni::errors::Result<jobjectArray> {
                let names = match engine() {
                    Ok(engine) => engine.list_registered_functions().unwrap_or_default(),
                    Err(_) => Vec::new(),
                };
                string_array(env, &names)
            })
            .into_outcome()
        {
            Outcome::Ok(value) => value,
            _ => std::ptr::null_mut(),
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_StrategyEngineNativeBindings_luaLoadedScriptPaths(
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
) -> jobjectArray {
    ffi_boundary(core::ptr::null_mut(), move || {
        let mut env = env;
        match env
            .with_env(|env| -> jni::errors::Result<jobjectArray> {
                let paths = LOADED_SCRIPT_PATHS
                    .lock()
                    .map(|paths| paths.iter().map(|path| path.to_string_lossy().to_string()).collect::<Vec<_>>())
                    .unwrap_or_default();
                string_array(env, &paths)
            })
            .into_outcome()
        {
            Outcome::Ok(value) => value,
            _ => std::ptr::null_mut(),
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_StrategyEngineNativeBindings_luaValidateScript(
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
    path: JString<'_>,
) -> jstring {
    ffi_boundary(core::ptr::null_mut(), move || {
        nullable_error_entry(env, path, |path| LuaStrategyEngine::validate_script(path).map_err(Into::into))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_StrategyEngineNativeBindings_validateStrategyConfigText(
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
    config_text: JString<'_>,
) -> jstring {
    ffi_boundary(core::ptr::null_mut(), move || {
        let mut env = env;
        match env
            .with_env(move |env| -> jni::errors::Result<jstring> {
                let config_text = config_text.mutf8_chars(env)?.to_str().into_owned();
                error_to_nullable_jstring(env, validate_strategy_config_text(&config_text))
            })
            .into_outcome()
        {
            Outcome::Ok(value) => value,
            _ => std::ptr::null_mut(),
        }
    })
}

fn validate_strategy_config_text(config_text: &str) -> Result<(), String> {
    match ripdpi_strategy_config::parse_yaml_str(config_text, ".") {
        Ok(_) => Ok(()),
        Err(yaml_error) => match ripdpi_strategy_config::parse_toml_str(config_text, ".") {
            Ok(_) => Ok(()),
            Err(toml_error) => Err(format!("YAML parse failed: {yaml_error}; TOML parse failed: {toml_error}")),
        },
    }
}

fn nullable_error_entry(
    mut env: EnvUnowned<'_>,
    path: JString<'_>,
    operation: impl FnOnce(PathBuf) -> Result<(), LuaBridgeError>,
) -> jstring {
    match env
        .with_env(move |env| -> jni::errors::Result<jstring> {
            let path = path.mutf8_chars(env)?.to_str().into_owned();
            error_to_nullable_jstring(env, operation(PathBuf::from(path)).map_err(|error| error.to_string()))
        })
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        _ => std::ptr::null_mut(),
    }
}

fn error_to_nullable_jstring(env: &mut Env<'_>, result: Result<(), String>) -> jni::errors::Result<jstring> {
    match result {
        Ok(()) => Ok(std::ptr::null_mut()),
        Err(error) => Ok(env.new_string(error)?.into_raw()),
    }
}

fn string_array(env: &mut Env<'_>, values: &[String]) -> jni::errors::Result<jobjectArray> {
    let initial = env.new_string("")?;
    let array = JObjectArray::<JString>::new(env, values.len(), &initial)?;
    for (index, value) in values.iter().enumerate() {
        let string = env.new_string(value)?;
        array.set_element(env, index, &string)?;
    }
    Ok(array.into_raw())
}

fn engine() -> Result<std::sync::MutexGuard<'static, LuaStrategyEngine>, LuaBridgeError> {
    match &*LUA_ENGINE {
        Ok(engine) => engine.lock().map_err(|_| LuaBridgeError::LockPoisoned("lua engine")),
        Err(error) => Err(LuaBridgeError::Initialization(error.clone())),
    }
}

fn remember_loaded_path(path: PathBuf) -> Result<(), LuaBridgeError> {
    let mut paths = LOADED_SCRIPT_PATHS.lock().map_err(|_| LuaBridgeError::LockPoisoned("lua script paths"))?;
    if !paths.contains(&path) {
        paths.push(path);
    }
    Ok(())
}

fn reload_loaded_scripts() -> Result<(), LuaBridgeError> {
    let paths = LOADED_SCRIPT_PATHS.lock().map_err(|_| LuaBridgeError::LockPoisoned("lua script paths"))?.clone();
    let engine = engine()?;
    for path in paths {
        engine.load_script_registering_globals(path)?;
    }
    Ok(())
}

#[derive(Debug, thiserror::Error)]
enum LuaBridgeError {
    #[error("Lua engine initialization failed: {0}")]
    Initialization(String),
    #[error("{0} lock poisoned")]
    LockPoisoned(&'static str),
    #[error(transparent)]
    Lua(#[from] LuaError),
}
