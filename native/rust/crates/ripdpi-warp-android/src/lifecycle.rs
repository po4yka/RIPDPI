use android_support::{clear_warp_events, init_android_logging, JNI_VERSION};
use jni::objects::JString;
use jni::sys::{jint, jlong};
use jni::{EnvUnowned, Outcome};
use ripdpi_warp_core::{ResolvedWarpRuntimeConfig, WarpRuntime};

use crate::registry;
use crate::vpn_protect;

pub(crate) fn jni_on_load() -> jint {
    android_support::ignore_sigpipe();
    init_android_logging("ripdpi-warp-native");
    android_support::install_panic_hook();
    JNI_VERSION
}

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

pub(crate) fn stop(handle: jlong) {
    if let Some(session) = registry::get(handle) {
        session.stop();
    }
}

pub(crate) fn destroy(handle: jlong) {
    registry::remove(handle);
}
