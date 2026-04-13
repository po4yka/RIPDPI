use std::path::Path;
use std::sync::{Arc, Mutex, MutexGuard};

use android_support::{
    init_android_logging, sanitize_error_message, throw_runtime_exception, throw_runtime_exception_env,
};
use jni::objects::JString;
use jni::sys::{jboolean, jlong, jstring};
use jni::{Env, EnvUnowned, Outcome};
use once_cell::sync::Lazy;
use ripdpi_monitor::pcap::PcapRecordingSession;

use crate::errors::extract_panic_message;

const DEFAULT_MAX_BYTES: u64 = 10 * 1024 * 1024; // 10 MB
const DEFAULT_MAX_CONNECTIONS: u32 = 50;

static PCAP_SESSION: Lazy<Mutex<Option<Arc<PcapRecordingSession>>>> = Lazy::new(|| Mutex::new(None));

fn lock_session() -> Option<MutexGuard<'static, Option<Arc<PcapRecordingSession>>>> {
    PCAP_SESSION.lock().ok()
}

fn pcap_start(env: &mut Env<'_>, dir_path: JString, max_bytes: jlong) -> jboolean {
    let Ok(dir_str) = dir_path.try_to_string(env) else {
        throw_runtime_exception_env(env, "PCAP start failed: invalid dir_path");
        return false;
    };

    let max_bytes: u64 = if max_bytes > 0 { max_bytes as u64 } else { DEFAULT_MAX_BYTES };
    let dir = Path::new(&dir_str);

    match PcapRecordingSession::start(dir, max_bytes, DEFAULT_MAX_CONNECTIONS) {
        Ok(session) => {
            let Some(mut guard) = lock_session() else {
                throw_runtime_exception_env(env, "PCAP start failed: session mutex poisoned");
                return false;
            };
            *guard = Some(Arc::new(session));
            log::info!("PCAP recording started in {dir_str}");
            true
        }
        Err(err) => {
            throw_runtime_exception_env(env, sanitize_error_message(&err.to_string(), "PCAP start failed"));
            false
        }
    }
}

fn pcap_stop(env: &mut Env<'_>) -> jstring {
    let session: Option<Arc<PcapRecordingSession>> = match lock_session() {
        Some(mut guard) => guard.take(),
        None => {
            throw_runtime_exception_env(env, "PCAP stop failed: session mutex poisoned");
            return std::ptr::null_mut();
        }
    };

    match session {
        None => std::ptr::null_mut(),
        Some(session) => match session.stop() {
            Ok(path) => {
                let path_str = path.to_string_lossy();
                log::info!("PCAP recording stopped: {path_str}");
                match env.new_string(path_str.as_ref()) {
                    Ok(s) => s.into_raw(),
                    Err(err) => {
                        throw_runtime_exception_env(
                            env,
                            sanitize_error_message(&err.to_string(), "PCAP stop failed: new_string"),
                        );
                        std::ptr::null_mut()
                    }
                }
            }
            Err(err) => {
                throw_runtime_exception_env(env, sanitize_error_message(&err.to_string(), "PCAP stop failed"));
                std::ptr::null_mut()
            }
        },
    }
}

fn pcap_is_recording() -> jboolean {
    match lock_session() {
        Some(guard) => matches!(guard.as_ref(), Some(session) if session.is_active()),
        None => false,
    }
}

pub(crate) fn pcap_start_entry(
    mut env: EnvUnowned<'_>,
    _handle: jlong,
    dir_path: JString,
    max_bytes: jlong,
) -> jboolean {
    init_android_logging("ripdpi-native");
    match env
        .with_env(move |env| -> jni::errors::Result<jboolean> { Ok(pcap_start(env, dir_path, max_bytes)) })
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        Outcome::Err(err) => {
            log::error!("PCAP start failed: {err}");
            throw_runtime_exception(&mut env, sanitize_error_message(&err.to_string(), "PCAP start failed"));
            false
        }
        Outcome::Panic(panic_payload) => {
            let msg = extract_panic_message(panic_payload);
            log::error!("PCAP start panicked: {msg}");
            throw_runtime_exception(&mut env, sanitize_error_message(&msg, "PCAP start failed"));
            false
        }
    }
}

pub(crate) fn pcap_stop_entry(mut env: EnvUnowned<'_>, _handle: jlong) -> jstring {
    init_android_logging("ripdpi-native");
    match env.with_env(move |env| -> jni::errors::Result<jstring> { Ok(pcap_stop(env)) }).into_outcome() {
        Outcome::Ok(value) => value,
        Outcome::Err(err) => {
            log::error!("PCAP stop failed: {err}");
            throw_runtime_exception(&mut env, sanitize_error_message(&err.to_string(), "PCAP stop failed"));
            std::ptr::null_mut()
        }
        Outcome::Panic(panic_payload) => {
            let msg = extract_panic_message(panic_payload);
            log::error!("PCAP stop panicked: {msg}");
            throw_runtime_exception(&mut env, sanitize_error_message(&msg, "PCAP stop failed"));
            std::ptr::null_mut()
        }
    }
}

pub(crate) fn pcap_is_recording_entry(mut env: EnvUnowned<'_>, _handle: jlong) -> jboolean {
    init_android_logging("ripdpi-native");
    match env.with_env(move |_env| -> jni::errors::Result<jboolean> { Ok(pcap_is_recording()) }).into_outcome() {
        Outcome::Ok(value) => value,
        Outcome::Err(err) => {
            log::error!("PCAP is_recording failed: {err}");
            false
        }
        Outcome::Panic(panic_payload) => {
            let msg = extract_panic_message(panic_payload);
            log::error!("PCAP is_recording panicked: {msg}");
            false
        }
    }
}
