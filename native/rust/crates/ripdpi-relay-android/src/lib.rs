use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use android_support::{init_android_logging, JNI_VERSION};
use jni::objects::{JObject, JString};
use jni::sys::{jint, jlong};
use jni::{EnvUnowned, JavaVM, Outcome};
use once_cell::sync::Lazy;
use ripdpi_relay_core::{RelayRuntime, ResolvedRelayRuntimeConfig};

static NEXT_HANDLE: Lazy<Mutex<u64>> = Lazy::new(|| Mutex::new(1));
static SESSIONS: Lazy<Mutex<HashMap<u64, Arc<RelayRuntime>>>> = Lazy::new(|| Mutex::new(HashMap::new()));

/// # Safety
/// Called once by the JVM at library load; `vm` is a valid `*mut JavaVM` that outlives this call.
/// The function must not panic across the FFI boundary; panic-hook installation and signal masking
/// are handled inside the `catch_unwind` wrapper.
#[unsafe(no_mangle)]
#[allow(improper_ctypes_definitions)]
pub extern "system" fn JNI_OnLoad(_vm: JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    match std::panic::catch_unwind(|| {
        android_support::ignore_sigpipe();
        init_android_logging("ripdpi-relay-native");
        android_support::install_panic_hook();
        JNI_VERSION
    }) {
        Ok(version) => version,
        Err(_) => jni::sys::JNI_ERR,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiRelayNativeBindings_jniCreate(
    mut env: EnvUnowned<'_>,
    _thiz: JObject,
    config_json: JString,
) -> jlong {
    match env
        .with_env(move |env| -> jni::errors::Result<jlong> {
            let config_json: String = config_json.mutf8_chars(env)?.to_str().into_owned();
            let Ok(config) = serde_json::from_str::<ResolvedRelayRuntimeConfig>(&config_json) else {
                return Ok(0);
            };
            let handle = {
                let mut next = NEXT_HANDLE.lock().expect("handle mutex");
                let value = *next;
                *next += 1;
                value
            };
            SESSIONS.lock().expect("session mutex").insert(handle, RelayRuntime::new(config));
            Ok(jlong::try_from(handle).unwrap_or(0))
        })
        .into_outcome()
    {
        Outcome::Ok(handle) => handle,
        _ => 0,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiRelayNativeBindings_jniStart(
    _env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) -> jint {
    let Some(session) = session_from_handle(handle) else {
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

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiRelayNativeBindings_jniStop(
    _env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) {
    if let Some(session) = session_from_handle(handle) {
        session.stop();
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiRelayNativeBindings_jniPollTelemetry(
    mut env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) -> jni::sys::jstring {
    match env
        .with_env(move |env| -> jni::errors::Result<jni::sys::jstring> {
            let payload = session_from_handle(handle)
                .and_then(|session| serde_json::to_string(&session.telemetry()).ok())
                .unwrap_or_else(|| {
                    "{\"source\":\"relay\",\"state\":\"idle\",\"health\":\"idle\",\"capturedAt\":0}".to_string()
                });
            Ok(env.new_string(payload)?.into_raw())
        })
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        _ => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_RipDpiRelayNativeBindings_jniDestroy(
    _env: EnvUnowned<'_>,
    _thiz: JObject,
    handle: jlong,
) {
    if let Ok(handle) = u64::try_from(handle) {
        SESSIONS.lock().expect("session mutex").remove(&handle);
    }
}

fn session_from_handle(handle: jlong) -> Option<Arc<RelayRuntime>> {
    let handle = u64::try_from(handle).ok()?;
    SESSIONS.lock().expect("session mutex").get(&handle).cloned()
}
