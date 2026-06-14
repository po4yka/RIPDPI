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

/// Explicitly-seeded jail directory for the JNI `luaLoadScript` surface.
///
/// `luaLoadScript` receives a user-typed absolute `path` plus the canonical
/// `<filesDir>/lua` `base_dir` (produced by `LuaAssetManager`) on every call.
/// The first load seeds this jail base (first-seed-wins) and every load — first
/// included — must canonicalize to a file inside it, closing cross-directory
/// path escape (absolute or `../`).
///
/// This deliberately replaces the previous trust-on-first-use scheme, where the
/// canonical parent of the *first* `luaLoadScript` path became the jail: a
/// user/attacker-influenced first path could pin the jail to an arbitrary
/// directory. Folding the base into the load also removes the unseeded window
/// entirely; [`LuaBridgeError::JailNotSeeded`] remains as defence for the
/// (now unreachable in production) case of a load against an unseeded jail.
///
/// Genuinely untrusted *imported* configs do not reach this path; they go
/// through the registry, which jails the VM to the config's own base directory
/// via `new_jailed`.
static LUA_JNI_SCRIPT_JAIL: OnceLock<PathBuf> = OnceLock::new();

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_StrategyEngineNativeBindings_luaLoadScript(
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
    base_dir: JString<'_>,
    path: JString<'_>,
) -> jstring {
    ffi_boundary(core::ptr::null_mut(), move || {
        let mut env = env;
        match env
            .with_env(|env| -> jni::errors::Result<jstring> {
                let base_dir = base_dir.mutf8_chars(env)?.to_str().into_owned();
                let path = path.mutf8_chars(env)?.to_str().into_owned();
                let result = load_script_in_jail(PathBuf::from(base_dir), PathBuf::from(path))
                    .map_err(|error| error.to_string());
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

/// Seeds the JNI script jail base from the `<filesDir>/lua` directory that
/// Kotlin passes to `luaLoadScript` (sourced from `LuaAssetManager`). The
/// directory is canonicalized (resolving symlinks and `..`) before being
/// locked, so later containment checks compare canonical-vs-canonical. Seeding
/// is idempotent: the first successful seed wins and subsequent calls are a
/// no-op, so a stray later seed cannot move the jail.
fn seed_jni_script_jail(dir: &Path) -> Result<(), LuaBridgeError> {
    let canonical = std::fs::canonicalize(dir)
        .map_err(|source| LuaBridgeError::JailSeedRead { path: dir.to_path_buf(), source })?;
    // `set` returns Err if already seeded; first seed wins, so ignore it.
    let _ = LUA_JNI_SCRIPT_JAIL.set(canonical);
    Ok(())
}

/// Seeds the jail from the caller-supplied `<filesDir>/lua` `base_dir`
/// (first-seed-wins) and loads `path` confined to it. Folding the base into the
/// load removes any unseeded window and avoids a separate JNI surface to seed
/// the jail.
fn load_script_in_jail(base_dir: PathBuf, path: PathBuf) -> Result<(), LuaBridgeError> {
    seed_jni_script_jail(&base_dir)?;
    let canonical = jail_jni_script_path(&path)?;
    engine()?.load_script_registering_globals(&canonical)?;
    remember_loaded_path(canonical)?;
    Ok(())
}

/// Confines a JNI `luaLoadScript` path to the explicitly-seeded jail dir.
///
/// The target is canonicalized first (resolving symlinks and `..`) and must
/// canonicalize to a file inside the seeded base. A path that escapes the
/// locked directory — an absolute path elsewhere or a `../` traversal — is
/// rejected before the engine reads the file. If the jail has not been seeded
/// yet (the `base_dir` of the first `luaLoadScript` call), the load is rejected
/// outright rather than trust-on-first-use pinning the jail to the requested path.
fn jail_jni_script_path(path: &Path) -> Result<PathBuf, LuaBridgeError> {
    let canonical = std::fs::canonicalize(path)
        .map_err(|source| LuaBridgeError::ScriptRead { path: path.to_path_buf(), source })?;
    resolve_in_jail(LUA_JNI_SCRIPT_JAIL.get().map(PathBuf::as_path), path, &canonical)
}

/// Pure jail resolution against an optional seeded `base`.
///
/// Returns [`LuaBridgeError::JailNotSeeded`] when `base` is `None` (the jail was
/// never seeded), otherwise delegates to [`enforce_jni_jail`]. Split out from
/// [`jail_jni_script_path`] so both the "unseeded load" and "seeded but
/// out-of-jail" rules are unit-testable without touching the process-global
/// [`LUA_JNI_SCRIPT_JAIL`].
fn resolve_in_jail(base: Option<&Path>, requested: &Path, target: &Path) -> Result<PathBuf, LuaBridgeError> {
    let base = base.ok_or(LuaBridgeError::JailNotSeeded)?;
    enforce_jni_jail(base, requested, target)
}

/// Pure jail check: the canonicalized `target` must live inside the locked
/// `base` directory, otherwise the original `requested` path is rejected as an
/// escape. Split out from [`resolve_in_jail`] so the containment rule is
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
    #[error("Lua script jail directory {path} could not be read: {source}")]
    JailSeedRead { path: PathBuf, source: std::io::Error },
    #[error("Lua script path {path} escapes the locked script directory")]
    ScriptPathEscape { path: PathBuf },
    #[error("Lua script jail is not seeded; luaLoadScript must be called with a non-empty base_dir")]
    JailNotSeeded,
    #[error(transparent)]
    Lua(#[from] LuaError),
}

#[cfg(test)]
mod tests {
    use super::{LuaBridgeError, enforce_jni_jail, resolve_in_jail};
    use std::path::Path;

    #[test]
    fn accepts_a_target_inside_the_locked_base() {
        let base = Path::new("/data/user/0/com.poyka.ripdpi/files/lua");
        let target = base.join("zapret-antidpi.lua");
        let resolved = enforce_jni_jail(base, &target, &target).expect("in-jail path is accepted");
        assert_eq!(resolved, target);
    }

    #[test]
    fn unseeded_jail_rejects_any_load() {
        // A load resolved against an unseeded jail (an attacker-typed path in
        // the advanced field) must be rejected, never trust-on-first-use pinned
        // as the jail base.
        let requested = Path::new("/sdcard/Download/evil.lua");
        let error = resolve_in_jail(None, requested, requested).expect_err("unseeded load is rejected");
        assert!(matches!(error, LuaBridgeError::JailNotSeeded));
    }

    #[test]
    fn seeded_jail_rejects_first_arbitrary_load() {
        // With an explicit seed, the very first load of an out-of-jail path is
        // rejected — the seed wins over what trust-on-first-use would have
        // pinned to the attacker-influenced path.
        let base = Path::new("/data/user/0/com.poyka.ripdpi/files/lua");
        let requested = Path::new("/sdcard/Download/evil.lua");
        let error = resolve_in_jail(Some(base), requested, requested).expect_err("out-of-jail first load is rejected");
        assert!(matches!(error, LuaBridgeError::ScriptPathEscape { .. }));
    }

    #[test]
    fn seeded_jail_accepts_in_jail_load() {
        let base = Path::new("/data/user/0/com.poyka.ripdpi/files/lua");
        let target = base.join("zapret-antidpi.lua");
        let resolved = resolve_in_jail(Some(base), &target, &target).expect("in-jail load is accepted");
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
