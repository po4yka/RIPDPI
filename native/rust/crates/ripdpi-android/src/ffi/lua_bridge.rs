use std::path::{Path, PathBuf};
use std::sync::Mutex;

use android_support::ffi_boundary;
use jni::objects::{JObject, JObjectArray, JString};
use jni::sys::{jobjectArray, jstring};
use jni::{Env, EnvUnowned, Outcome};
use ripdpi_strategy_lua::{LuaError, LuaStrategyEngine};
use std::sync::{LazyLock, OnceLock};

static LUA_ENGINE: LazyLock<Result<Mutex<LuaStrategyEngine>, String>> =
    LazyLock::new(|| LuaStrategyEngine::new().map(Mutex::new).map_err(|error| error.to_string()));
static LOADED_SCRIPT_PATHS: LazyLock<Mutex<Vec<PathBuf>>> = LazyLock::new(|| Mutex::new(Vec::new()));

/// Trust-on-first-use jail directory for the JNI `luaLoadScript` surface.
///
/// `luaLoadScript` receives a user-typed absolute path from the app's advanced
/// Lua field; in the legitimate flow the first load at startup is the bundled
/// `<filesDir>/lua/…` script extracted by `LuaAssetManager`. The canonical
/// parent of that first script is locked here, and every subsequent JNI load
/// must canonicalize to a file inside it — closing cross-directory path escape
/// (absolute or `../`) after the first local load. Genuinely untrusted
/// *imported* configs do not reach this path; they go through the registry,
/// which jails the VM to the config's own base directory via `new_jailed`.
static LUA_JNI_SCRIPT_JAIL: OnceLock<PathBuf> = OnceLock::new();

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_StrategyEngineNativeBindings_luaLoadScript(
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
    path: JString<'_>,
) -> jstring {
    ffi_boundary(core::ptr::null_mut(), move || {
        nullable_error_entry(env, path, |path| {
            let canonical = jail_jni_script_path(&path)?;
            engine()?.load_script_registering_globals(&canonical)?;
            remember_loaded_path(canonical)?;
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

/// Confines a JNI `luaLoadScript` path to the trust-on-first-use jail dir.
///
/// The target is canonicalized first (resolving symlinks and `..`); the
/// canonical parent of the *first* load is locked as the jail base, and every
/// later load must canonicalize to a file inside it. A path that escapes the
/// locked directory — an absolute path elsewhere or a `../` traversal — is
/// rejected before the engine reads the file.
fn jail_jni_script_path(path: &Path) -> Result<PathBuf, LuaBridgeError> {
    let canonical = std::fs::canonicalize(path)
        .map_err(|source| LuaBridgeError::ScriptRead { path: path.to_path_buf(), source })?;
    let parent =
        canonical.parent().ok_or_else(|| LuaBridgeError::ScriptPathEscape { path: path.to_path_buf() })?.to_path_buf();
    let base = LUA_JNI_SCRIPT_JAIL.get_or_init(|| parent);
    enforce_jni_jail(base, path, &canonical)
}

/// Pure jail check: the canonicalized `target` must live inside the locked
/// `base` directory, otherwise the original `requested` path is rejected as an
/// escape. Split out from [`jail_jni_script_path`] so the containment rule is
/// unit-testable without touching the process-global [`LUA_JNI_SCRIPT_JAIL`].
fn enforce_jni_jail(base: &Path, requested: &Path, target: &Path) -> Result<PathBuf, LuaBridgeError> {
    if target.starts_with(base) {
        Ok(target.to_path_buf())
    } else {
        Err(LuaBridgeError::ScriptPathEscape { path: requested.to_path_buf() })
    }
}

#[derive(Debug, thiserror::Error)]
enum LuaBridgeError {
    #[error("Lua engine initialization failed: {0}")]
    Initialization(String),
    #[error("{0} lock poisoned")]
    LockPoisoned(&'static str),
    #[error("Lua script path {path} could not be read: {source}")]
    ScriptRead { path: PathBuf, source: std::io::Error },
    #[error("Lua script path {path} escapes the locked script directory")]
    ScriptPathEscape { path: PathBuf },
    #[error(transparent)]
    Lua(#[from] LuaError),
}

#[cfg(test)]
mod tests {
    use super::{LuaBridgeError, enforce_jni_jail};
    use std::path::Path;

    #[test]
    fn accepts_a_target_inside_the_locked_base() {
        let base = Path::new("/data/user/0/com.poyka.ripdpi/files/lua");
        let target = base.join("zapret-antidpi.lua");
        let resolved = enforce_jni_jail(base, &target, &target).expect("in-jail path is accepted");
        assert_eq!(resolved, target);
    }

    #[test]
    fn rejects_an_absolute_target_outside_the_locked_base() {
        let base = Path::new("/data/user/0/com.poyka.ripdpi/files/lua");
        let requested = Path::new("/etc/passwd");
        let error = enforce_jni_jail(base, requested, requested).expect_err("out-of-jail path is rejected");
        assert!(matches!(error, LuaBridgeError::ScriptPathEscape { .. }));
    }

    #[test]
    fn rejects_a_sibling_directory_that_shares_a_prefix() {
        // `..._lua-evil` must not be accepted just because it shares a string
        // prefix with the `..._lua` jail; `starts_with` matches path
        // components, not raw substrings.
        let base = Path::new("/data/user/0/com.poyka.ripdpi/files/lua");
        let requested = Path::new("/data/user/0/com.poyka.ripdpi/files/lua-evil/x.lua");
        let error = enforce_jni_jail(base, requested, requested).expect_err("sibling-prefix path is rejected");
        assert!(matches!(error, LuaBridgeError::ScriptPathEscape { .. }));
    }
}
