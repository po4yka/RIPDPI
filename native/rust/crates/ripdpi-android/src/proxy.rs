mod lifecycle;
mod registry;
mod telemetry;

#[cfg(feature = "loom")]
mod loom_tests;
#[cfg(test)]
mod tests;

use android_support::{init_android_logging, sanitize_error_message, throw_runtime_exception};
use jni::objects::JString;
use jni::sys::{jint, jlong, jstring};
use jni::{EnvUnowned, Outcome};

use crate::errors::extract_panic_message;

use lifecycle::{create_session, destroy_session, start_session, stop_session, update_network_snapshot};
use telemetry::poll_proxy_telemetry;

pub(crate) fn proxy_create_entry(mut env: EnvUnowned<'_>, config_json: JString) -> jlong {
    init_android_logging("ripdpi-native");
    match env.with_env(move |env| -> jni::errors::Result<jlong> { Ok(create_session(env, config_json)) }).into_outcome()
    {
        Outcome::Ok(handle) => handle,
        Outcome::Err(err) => {
            log::error!("Proxy session creation failed: {err}");
            throw_runtime_exception(
                &mut env,
                sanitize_error_message(&err.to_string(), "Proxy session creation failed"),
            );
            0
        }
        Outcome::Panic(panic_payload) => {
            let msg = extract_panic_message(panic_payload);
            log::error!("Proxy session creation panicked: {msg}");
            throw_runtime_exception(&mut env, sanitize_error_message(&msg, "Proxy session creation failed"));
            0
        }
    }
}

pub(crate) fn proxy_start_entry(mut env: EnvUnowned<'_>, handle: jlong) -> jint {
    init_android_logging("ripdpi-native");
    match env.with_env(move |env| -> jni::errors::Result<jint> { Ok(start_session(env, handle)) }).into_outcome() {
        Outcome::Ok(result) => result,
        Outcome::Err(err) => {
            log::error!("Proxy session start failed: {err}");
            throw_runtime_exception(&mut env, sanitize_error_message(&err.to_string(), "Proxy session start failed"));
            libc::EINVAL
        }
        Outcome::Panic(panic_payload) => {
            let msg = extract_panic_message(panic_payload);
            log::error!("Proxy session start panicked: {msg}");
            throw_runtime_exception(&mut env, sanitize_error_message(&msg, "Proxy session start failed"));
            libc::EINVAL
        }
    }
}

pub(crate) fn proxy_stop_entry(mut env: EnvUnowned<'_>, handle: jlong) {
    init_android_logging("ripdpi-native");
    match env
        .with_env(move |env| -> jni::errors::Result<()> {
            stop_session(env, handle);
            Ok(())
        })
        .into_outcome()
    {
        Outcome::Ok(()) => {}
        Outcome::Err(err) => {
            log::error!("Proxy session stop failed: {err}");
            throw_runtime_exception(&mut env, sanitize_error_message(&err.to_string(), "Proxy session stop failed"));
        }
        Outcome::Panic(panic_payload) => {
            let msg = extract_panic_message(panic_payload);
            log::error!("Proxy session stop panicked: {msg}");
            throw_runtime_exception(&mut env, sanitize_error_message(&msg, "Proxy session stop failed"));
        }
    }
}

pub(crate) fn proxy_poll_telemetry_entry(mut env: EnvUnowned<'_>, handle: jlong) -> jstring {
    init_android_logging("ripdpi-native");
    match env
        .with_env(move |env| -> jni::errors::Result<jstring> { Ok(poll_proxy_telemetry(env, handle)) })
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        Outcome::Err(err) => {
            log::error!("Proxy telemetry polling failed: {err}");
            throw_runtime_exception(
                &mut env,
                sanitize_error_message(&err.to_string(), "Proxy telemetry polling failed"),
            );
            std::ptr::null_mut()
        }
        Outcome::Panic(panic_payload) => {
            let msg = extract_panic_message(panic_payload);
            log::error!("Proxy telemetry polling panicked: {msg}");
            throw_runtime_exception(&mut env, sanitize_error_message(&msg, "Proxy telemetry polling failed"));
            std::ptr::null_mut()
        }
    }
}

pub(crate) fn proxy_destroy_entry(mut env: EnvUnowned<'_>, handle: jlong) {
    init_android_logging("ripdpi-native");
    match env
        .with_env(move |env| -> jni::errors::Result<()> {
            destroy_session(env, handle);
            Ok(())
        })
        .into_outcome()
    {
        Outcome::Ok(()) => {}
        Outcome::Err(err) => {
            log::error!("Proxy session destroy failed: {err}");
            throw_runtime_exception(&mut env, sanitize_error_message(&err.to_string(), "Proxy session destroy failed"));
        }
        Outcome::Panic(panic_payload) => {
            let msg = extract_panic_message(panic_payload);
            log::error!("Proxy session destroy panicked: {msg}");
            throw_runtime_exception(&mut env, sanitize_error_message(&msg, "Proxy session destroy failed"));
        }
    }
}

pub(crate) fn proxy_update_network_snapshot_entry(mut env: EnvUnowned<'_>, handle: jlong, snapshot_json: JString) {
    init_android_logging("ripdpi-native");
    match env
        .with_env(move |env| -> jni::errors::Result<()> {
            update_network_snapshot(env, handle, snapshot_json);
            Ok(())
        })
        .into_outcome()
    {
        Outcome::Ok(()) => {}
        Outcome::Err(err) => {
            log::error!("Proxy network snapshot update failed: {err}");
            throw_runtime_exception(
                &mut env,
                sanitize_error_message(&err.to_string(), "Proxy network snapshot update failed"),
            );
        }
        Outcome::Panic(panic_payload) => {
            let msg = extract_panic_message(panic_payload);
            log::error!("Proxy network snapshot update panicked: {msg}");
            throw_runtime_exception(&mut env, sanitize_error_message(&msg, "Proxy network snapshot update failed"));
        }
    }
}
