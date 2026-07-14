use std::path::PathBuf;

use android_support::{sanitize_error_message, throw_runtime_exception};
use jni::objects::JString;
use jni::sys::{jint, jlong};
use jni::{EnvUnowned, Outcome};

use super::pcap;
use super::registry::{TunnelSessionState, lookup_tunnel_session};

pub(crate) fn tunnel_pcap_start_entry(
    mut env: EnvUnowned<'_>,
    handle: jlong,
    capture_dir: JString,
    max_file_bytes: jlong,
    max_files: jint,
) -> jlong {
    android_support::init_android_logging("ripdpi-tunnel-native");
    match env
        .with_env(move |env| -> jni::errors::Result<jlong> {
            let dir =
                capture_dir.try_to_string(env).map(PathBuf::from).map_err(|_| jni::errors::Error::JavaException)?;
            let Ok(session) = lookup_tunnel_session(handle) else { return Ok(0) };
            let state = session.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            let TunnelSessionState::Running { stats, .. } = &*state else { return Ok(0) };
            let set_id = pcap::pcap_start_entry(
                handle,
                stats.clone(),
                dir,
                u64::try_from(max_file_bytes).unwrap_or(0),
                u32::try_from(max_files).unwrap_or(0),
            );
            Ok(set_id)
        })
        .into_outcome()
    {
        Outcome::Ok(set_id) => set_id,
        Outcome::Err(err) => {
            log::error!("Tunnel pcap start failed: {err}");
            throw_runtime_exception(&mut env, sanitize_error_message(&err.to_string(), "Tunnel pcap start failed"));
            0
        }
        Outcome::Panic(_) => {
            log::error!("Tunnel pcap start panicked");
            throw_runtime_exception(&mut env, sanitize_error_message("panic", "Tunnel pcap start failed"));
            0
        }
    }
}

pub(crate) fn tunnel_pcap_stop_entry(mut env: EnvUnowned<'_>, handle: jlong) -> jni::sys::jstring {
    android_support::init_android_logging("ripdpi-tunnel-native");
    match env
        .with_env(move |env| -> jni::errors::Result<jni::sys::jstring> {
            Ok(env.new_string(pcap::pcap_stop_entry(handle))?.into_raw())
        })
        .into_outcome()
    {
        Outcome::Ok(json) => json,
        Outcome::Err(err) => {
            log::error!("Tunnel pcap stop failed: {err}");
            throw_runtime_exception(&mut env, sanitize_error_message(&err.to_string(), "Tunnel pcap stop failed"));
            std::ptr::null_mut()
        }
        Outcome::Panic(_) => {
            log::error!("Tunnel pcap stop panicked");
            throw_runtime_exception(&mut env, sanitize_error_message("panic", "Tunnel pcap stop failed"));
            std::ptr::null_mut()
        }
    }
}

pub(crate) fn tunnel_pcap_list_captures_entry(mut env: EnvUnowned<'_>, capture_dir: JString) -> jni::sys::jstring {
    android_support::init_android_logging("ripdpi-tunnel-native");
    match env
        .with_env(move |env| -> jni::errors::Result<jni::sys::jstring> {
            let dir =
                capture_dir.try_to_string(env).map(PathBuf::from).map_err(|_| jni::errors::Error::JavaException)?;
            Ok(env.new_string(pcap::pcap_list_captures_entry(dir))?.into_raw())
        })
        .into_outcome()
    {
        Outcome::Ok(json) => json,
        Outcome::Err(err) => {
            log::error!("Tunnel pcap list failed: {err}");
            throw_runtime_exception(&mut env, sanitize_error_message(&err.to_string(), "Tunnel pcap list failed"));
            std::ptr::null_mut()
        }
        Outcome::Panic(_) => {
            log::error!("Tunnel pcap list panicked");
            throw_runtime_exception(&mut env, sanitize_error_message("panic", "Tunnel pcap list failed"));
            std::ptr::null_mut()
        }
    }
}

pub(crate) fn tunnel_pcap_redact_entry(mut env: EnvUnowned<'_>, source_path: JString, dest_fd: jint) -> jlong {
    let Some(dest_fd) = pcap::adopt_pcap_dest_fd(dest_fd) else {
        return 0;
    };
    android_support::init_android_logging("ripdpi-tunnel-native");
    match env
        .with_env(move |env| -> jni::errors::Result<jlong> {
            let path =
                source_path.try_to_string(env).map(PathBuf::from).map_err(|_| jni::errors::Error::JavaException)?;
            Ok(pcap::pcap_redact_entry(path, dest_fd) as jlong)
        })
        .into_outcome()
    {
        Outcome::Ok(bytes) => bytes,
        Outcome::Err(err) => {
            log::error!("Tunnel pcap redact failed: {err}");
            throw_runtime_exception(&mut env, sanitize_error_message(&err.to_string(), "Tunnel pcap redact failed"));
            0
        }
        Outcome::Panic(_) => {
            log::error!("Tunnel pcap redact panicked");
            throw_runtime_exception(&mut env, sanitize_error_message("panic", "Tunnel pcap redact failed"));
            0
        }
    }
}
