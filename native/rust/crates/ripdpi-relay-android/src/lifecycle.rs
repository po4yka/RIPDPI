use android_support::{clear_relay_events, init_android_logging, JNI_VERSION};
use jni::objects::JString;
use jni::sys::{jint, jlong};
use jni::{EnvUnowned, JavaVM, Outcome};

use crate::registry::{insert_session, remove_session, session_from_handle};
use crate::runtime::create_session;
use crate::telemetry::{serialize_runtime_telemetry, IDLE_TELEMETRY_JSON};

pub(crate) fn jni_on_load_entry(_vm: JavaVM) -> jint {
    match std::panic::catch_unwind(|| {
        android_support::ignore_sigpipe();
        init_android_logging("ripdpi-relay-native");
        android_support::install_panic_hook();
        let _ = rustls::crypto::ring::default_provider().install_default();
        JNI_VERSION
    }) {
        Ok(version) => version,
        Err(_) => jni::sys::JNI_ERR,
    }
}

pub(crate) fn relay_create_entry(mut env: EnvUnowned<'_>, config_json: JString) -> jlong {
    match env
        .with_env(move |env| -> jni::errors::Result<jlong> {
            let config_json: String = config_json.mutf8_chars(env)?.to_str().into_owned();
            let Some(session) = create_session(&config_json) else {
                return Ok(0);
            };

            clear_relay_events();
            let handle = insert_session(session);
            Ok(jlong::try_from(handle).unwrap_or(0))
        })
        .into_outcome()
    {
        Outcome::Ok(handle) => handle,
        _ => 0,
    }
}

pub(crate) fn relay_start_entry(_env: EnvUnowned<'_>, handle: jlong) -> jint {
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

pub(crate) fn relay_stop_entry(_env: EnvUnowned<'_>, handle: jlong) {
    if let Some(session) = session_from_handle(handle) {
        session.stop();
    }
}

pub(crate) fn relay_poll_telemetry_entry(mut env: EnvUnowned<'_>, handle: jlong) -> jni::sys::jstring {
    match env
        .with_env(move |env| -> jni::errors::Result<jni::sys::jstring> {
            let payload = session_from_handle(handle)
                .and_then(|session| serialize_runtime_telemetry(&session))
                .unwrap_or_else(|| IDLE_TELEMETRY_JSON.to_string());
            Ok(env.new_string(payload)?.into_raw())
        })
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        _ => std::ptr::null_mut(),
    }
}

pub(crate) fn relay_destroy_entry(_env: EnvUnowned<'_>, handle: jlong) {
    remove_session(handle);
}
