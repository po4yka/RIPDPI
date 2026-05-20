//! WARP session lifecycle delegates behind the JNI exports in `lib.rs`.
//!
//! These functions are the `create`/`start`/`stop`/`destroy` bodies that
//! `lib.rs` wraps in `ffi_boundary`. Ordering is `create -> start -> stop ->
//! destroy`; `start` blocks for the whole session while `stop` only signals
//! it. Sessions live in the `registry` module keyed by the opaque `jlong`
//! handle. Failures are reported through return values — these functions never
//! throw Java exceptions.

use android_support::{clear_warp_events, init_android_logging, JNI_VERSION};
use jni::objects::JString;
use jni::sys::{jint, jlong};
use jni::{EnvUnowned, Outcome};
use ripdpi_warp_core::{ResolvedWarpRuntimeConfig, WarpRuntime};

use crate::registry;
use crate::vpn_protect;

/// `JNI_OnLoad` body: mask `SIGPIPE`, install Android logging and the panic
/// hook, and return the targeted JNI version. Runs once at library load.
pub(crate) fn jni_on_load() -> jint {
    android_support::ignore_sigpipe();
    init_android_logging("ripdpi-warp-native");
    android_support::install_panic_hook();
    JNI_VERSION
}

/// `jniCreate`: parse `config_json` into a `ResolvedWarpRuntimeConfig`, build a
/// `WarpRuntime` bound to the VPN-protect platform, and register it. Returns
/// the new opaque handle, or `0` if the config is invalid — no Java exception
/// is thrown.
pub(crate) fn create(mut env: EnvUnowned<'_>, config_json: JString<'_>) -> jlong {
    match env
        .with_env(move |env| -> jni::errors::Result<jlong> {
            let config_json: String = config_json.mutf8_chars(env)?.to_str().into_owned();
            let Ok(config) = serde_json::from_str::<ResolvedWarpRuntimeConfig>(&config_json) else {
                return Ok(0);
            };
            clear_warp_events();
            Ok(registry::insert(WarpRuntime::with_platform(config, vpn_protect::warp_platform())))
        })
        .into_outcome()
    {
        Outcome::Ok(handle) => handle,
        _ => 0,
    }
}

/// `jniStart`: look up the session, build a multi-thread Tokio runtime and run
/// the WARP tunnel to completion. **Blocks the calling (JNI) thread for the
/// whole session lifetime.** Returns `0` on a clean exit, `1` if `handle` is
/// unknown, or `2` if the runtime fails to build or the session errors.
pub(crate) fn start(handle: jlong) -> jint {
    let Some(session) = registry::get(handle) else {
        return 1;
    };
    match tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .and_then(|runtime| runtime.block_on(session.run()).map(|_| ()))
    {
        Ok(()) => 0,
        Err(_) => 2,
    }
}

/// `jniStop`: signal the running session to shut down so the blocked `start`
/// returns. Does not block. Idempotent — a no-op if `handle` is unknown or
/// already destroyed.
pub(crate) fn stop(handle: jlong) {
    if let Some(session) = registry::get(handle) {
        session.stop();
    }
}

/// `jniDestroy`: remove the session from the registry, retiring the handle.
/// Idempotent — a no-op if `handle` is unknown or already removed. Should run
/// only after `start` has returned.
pub(crate) fn destroy(handle: jlong) {
    registry::remove(handle);
}
