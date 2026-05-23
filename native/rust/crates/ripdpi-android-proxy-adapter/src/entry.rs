use android_support::init_android_logging;
use jni::objects::JString;
use jni::sys::{jint, jlong, jstring};
use jni::{EnvUnowned, Outcome};

use ripdpi_android_bridge_support::{extract_panic_message, NativeBridgeError, NativeBridgeErrorDomain};

use crate::entry_error::{log_and_throw, log_and_throw_with_payload};
use crate::geo_versions::{geo_database_versions, geoip_metadata};
use crate::lifecycle::{create_session, destroy_session, start_session, stop_session, update_network_snapshot};
use crate::telemetry::poll_proxy_telemetry;

fn proxy_bridge_error(code: &'static str, message: &str) -> NativeBridgeError {
    NativeBridgeError::new(NativeBridgeErrorDomain::Proxy, code, message)
}

pub fn proxy_create_entry(mut env: EnvUnowned<'_>, config_json: JString) -> jlong {
    init_android_logging("ripdpi-native");
    match env.with_env(move |env| -> jni::errors::Result<jlong> { Ok(create_session(env, config_json)) }).into_outcome()
    {
        Outcome::Ok(handle) => handle,
        Outcome::Err(err) => {
            let detail = err.to_string();
            let bridge = proxy_bridge_error("create_failed", &detail).with_cause_class("jni.errors.Error");
            log_and_throw_with_payload(&mut env, "Proxy session creation failed", &detail, &bridge);
            0
        }
        Outcome::Panic(payload) => {
            let detail = extract_panic_message(payload);
            let bridge = proxy_bridge_error("create_panic", &detail).with_cause_class("java.lang.RuntimeException");
            log_and_throw_with_payload(&mut env, "Proxy session creation panicked", &detail, &bridge);
            0
        }
    }
}

pub fn proxy_start_entry(mut env: EnvUnowned<'_>, handle: jlong) -> jint {
    init_android_logging("ripdpi-native");
    match env.with_env(move |env| -> jni::errors::Result<jint> { Ok(start_session(env, handle)) }).into_outcome() {
        Outcome::Ok(result) => result,
        Outcome::Err(err) => {
            let detail = err.to_string();
            let bridge = proxy_bridge_error("start_failed", &detail)
                .with_cause_class("jni.errors.Error")
                .with_handle_state("unknown");
            log_and_throw_with_payload(&mut env, "Proxy session start failed", &detail, &bridge);
            libc::EINVAL
        }
        Outcome::Panic(payload) => {
            let detail = extract_panic_message(payload);
            let bridge = proxy_bridge_error("start_panic", &detail)
                .with_cause_class("java.lang.RuntimeException")
                .with_handle_state("unknown");
            log_and_throw_with_payload(&mut env, "Proxy session start panicked", &detail, &bridge);
            libc::EINVAL
        }
    }
}

pub fn proxy_stop_entry(mut env: EnvUnowned<'_>, handle: jlong) {
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
            let detail = err.to_string();
            let bridge = proxy_bridge_error("stop_failed", &detail)
                .with_cause_class("jni.errors.Error")
                .with_handle_state("unknown");
            log_and_throw_with_payload(&mut env, "Proxy session stop failed", &detail, &bridge);
        }
        Outcome::Panic(payload) => {
            let detail = extract_panic_message(payload);
            let bridge = proxy_bridge_error("stop_panic", &detail)
                .with_cause_class("java.lang.RuntimeException")
                .with_handle_state("unknown");
            log_and_throw_with_payload(&mut env, "Proxy session stop panicked", &detail, &bridge);
        }
    }
}

pub fn proxy_poll_telemetry_entry(mut env: EnvUnowned<'_>, handle: jlong) -> jstring {
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

pub fn proxy_destroy_entry(mut env: EnvUnowned<'_>, handle: jlong) {
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
            let detail = err.to_string();
            let bridge = proxy_bridge_error("destroy_failed", &detail)
                .with_cause_class("jni.errors.Error")
                .with_handle_state("unknown");
            log_and_throw_with_payload(&mut env, "Proxy session destroy failed", &detail, &bridge);
        }
        Outcome::Panic(payload) => {
            let detail = extract_panic_message(payload);
            let bridge = proxy_bridge_error("destroy_panic", &detail)
                .with_cause_class("java.lang.RuntimeException")
                .with_handle_state("unknown");
            log_and_throw_with_payload(&mut env, "Proxy session destroy panicked", &detail, &bridge);
        }
    }
}

pub fn proxy_update_network_snapshot_entry(mut env: EnvUnowned<'_>, handle: jlong, snapshot_json: JString) {
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
            log_and_throw(&mut env, "Proxy network snapshot update panicked", &extract_panic_message(payload));
        }
    }
}

pub fn proxy_geo_database_versions_entry(
    mut env: EnvUnowned<'_>,
    geoip_db_path: JString,
    geosite_db_path: JString,
) -> jstring {
    init_android_logging("ripdpi-native");
    match env
        .with_env(move |env| -> jni::errors::Result<jstring> {
            Ok(geo_database_versions(env, geoip_db_path, geosite_db_path))
        })
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        Outcome::Err(err) => {
            log_and_throw(&mut env, "Proxy geo database version lookup failed", &err.to_string());
            std::ptr::null_mut()
        }
        Outcome::Panic(payload) => {
            log_and_throw(&mut env, "Proxy geo database version lookup panicked", &extract_panic_message(payload));
            std::ptr::null_mut()
        }
    }
}

pub fn proxy_geoip_metadata_entry(
    mut env: EnvUnowned<'_>,
    geoip_db_path: JString,
    geosite_db_path: JString,
    ip: JString,
) -> jstring {
    init_android_logging("ripdpi-native");
    match env
        .with_env(move |env| -> jni::errors::Result<jstring> {
            Ok(geoip_metadata(env, geoip_db_path, geosite_db_path, ip))
        })
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        Outcome::Err(err) => {
            log_and_throw(&mut env, "Proxy geoip metadata lookup failed", &err.to_string());
            std::ptr::null_mut()
        }
        Outcome::Panic(payload) => {
            log_and_throw(&mut env, "Proxy geoip metadata lookup panicked", &extract_panic_message(payload));
            std::ptr::null_mut()
        }
    }
}
