mod lifecycle;
mod pcap;
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
pub(crate) use pcap::{pcap_is_recording_entry, pcap_start_entry, pcap_stop_entry};
use telemetry::poll_proxy_telemetry;

fn log_and_throw(env: &mut EnvUnowned<'_>, label: &str, message: &str) {
    log::error!("{label}: {message}");
    throw_runtime_exception(env, sanitize_error_message(message, label));
}

pub(crate) fn proxy_create_entry(mut env: EnvUnowned<'_>, config_json: JString) -> jlong {
    init_android_logging("ripdpi-native");
    match env.with_env(move |env| -> jni::errors::Result<jlong> { Ok(create_session(env, config_json)) }).into_outcome()
    {
        Outcome::Ok(handle) => handle,
        Outcome::Err(err) => {
            log_and_throw(&mut env, "Proxy session creation failed", &err.to_string());
            0
        }
        Outcome::Panic(payload) => {
            log_and_throw(&mut env, "Proxy session creation panicked", &extract_panic_message(payload));
            0
        }
    }
}

pub(crate) fn proxy_start_entry(mut env: EnvUnowned<'_>, handle: jlong) -> jint {
    init_android_logging("ripdpi-native");
    match env.with_env(move |env| -> jni::errors::Result<jint> { Ok(start_session(env, handle)) }).into_outcome() {
        Outcome::Ok(result) => result,
        Outcome::Err(err) => {
            log_and_throw(&mut env, "Proxy session start failed", &err.to_string());
            libc::EINVAL
        }
        Outcome::Panic(payload) => {
            log_and_throw(&mut env, "Proxy session start panicked", &extract_panic_message(payload));
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
        Outcome::Err(err) => log_and_throw(&mut env, "Proxy session stop failed", &err.to_string()),
        Outcome::Panic(payload) => {
            log_and_throw(&mut env, "Proxy session stop panicked", &extract_panic_message(payload))
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
            log_and_throw(&mut env, "Proxy telemetry polling failed", &err.to_string());
            std::ptr::null_mut()
        }
        Outcome::Panic(payload) => {
            log_and_throw(&mut env, "Proxy telemetry polling panicked", &extract_panic_message(payload));
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
        Outcome::Err(err) => log_and_throw(&mut env, "Proxy session destroy failed", &err.to_string()),
        Outcome::Panic(payload) => {
            log_and_throw(&mut env, "Proxy session destroy panicked", &extract_panic_message(payload))
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
        Outcome::Err(err) => log_and_throw(&mut env, "Proxy network snapshot update failed", &err.to_string()),
        Outcome::Panic(payload) => {
            log_and_throw(&mut env, "Proxy network snapshot update panicked", &extract_panic_message(payload))
        }
    }
}
