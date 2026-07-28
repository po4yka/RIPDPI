use android_support::{sanitize_error_message, throw_runtime_exception};
use jni::objects::JString;
use jni::sys::{jint, jlong, jlongArray};
use jni::{EnvUnowned, Outcome};

use super::icmp::icmp_ingress_packets_session;
use super::lifecycle::{create_session, destroy_session, start_session, stop_session};
use super::stats::{forwarding_evidence_session, stats_session};
use super::telemetry::telemetry_session;

pub(crate) fn tunnel_create_entry(mut env: EnvUnowned<'_>, config_json: JString) -> jlong {
    android_support::init_android_logging("ripdpi-tunnel-native");
    match env.with_env(move |env| -> jni::errors::Result<jlong> { Ok(create_session(env, config_json)) }).into_outcome()
    {
        Outcome::Ok(handle) => handle,
        Outcome::Err(err) => {
            log::error!("Tunnel session creation failed: {err}");
            throw_runtime_exception(
                &mut env,
                sanitize_error_message(&err.to_string(), "Tunnel session creation failed"),
            );
            0
        }
        Outcome::Panic(_) => {
            log::error!("Tunnel session creation panicked");
            throw_runtime_exception(&mut env, sanitize_error_message("panic", "Tunnel session creation failed"));
            0
        }
    }
}

pub(crate) fn tunnel_start_entry(mut env: EnvUnowned<'_>, handle: jlong, tun_fd: jint) {
    android_support::init_android_logging("ripdpi-tunnel-native");
    match env
        .with_env(move |env| -> jni::errors::Result<()> {
            start_session(env, handle, tun_fd);
            Ok(())
        })
        .into_outcome()
    {
        Outcome::Ok(()) => {}
        Outcome::Err(err) => {
            log::error!("Tunnel session start failed: {err}");
            throw_runtime_exception(&mut env, sanitize_error_message(&err.to_string(), "Tunnel session start failed"));
        }
        Outcome::Panic(_) => {
            log::error!("Tunnel session start panicked");
            throw_runtime_exception(&mut env, sanitize_error_message("panic", "Tunnel session start failed"));
        }
    }
}

pub(crate) fn tunnel_stop_entry(mut env: EnvUnowned<'_>, handle: jlong) {
    android_support::init_android_logging("ripdpi-tunnel-native");
    match env
        .with_env(move |env| -> jni::errors::Result<()> {
            stop_session(env, handle);
            Ok(())
        })
        .into_outcome()
    {
        Outcome::Ok(()) => {}
        Outcome::Err(err) => {
            log::error!("Tunnel session stop failed: {err}");
            throw_runtime_exception(&mut env, sanitize_error_message(&err.to_string(), "Tunnel session stop failed"));
        }
        Outcome::Panic(_) => {
            log::error!("Tunnel session stop panicked");
            throw_runtime_exception(&mut env, sanitize_error_message("panic", "Tunnel session stop failed"));
        }
    }
}

pub(crate) fn tunnel_stats_entry(mut env: EnvUnowned<'_>, handle: jlong) -> jlongArray {
    android_support::init_android_logging("ripdpi-tunnel-native");
    match env.with_env(move |env| -> jni::errors::Result<jlongArray> { Ok(stats_session(env, handle)) }).into_outcome()
    {
        Outcome::Ok(stats) => stats,
        Outcome::Err(err) => {
            log::error!("Tunnel stats retrieval failed: {err}");
            throw_runtime_exception(
                &mut env,
                sanitize_error_message(&err.to_string(), "Tunnel stats retrieval failed"),
            );
            std::ptr::null_mut()
        }
        Outcome::Panic(_) => {
            log::error!("Tunnel stats retrieval panicked");
            throw_runtime_exception(&mut env, sanitize_error_message("panic", "Tunnel stats retrieval failed"));
            std::ptr::null_mut()
        }
    }
}

pub(crate) fn tunnel_forwarding_evidence_entry(mut env: EnvUnowned<'_>, handle: jlong) -> jni::sys::jstring {
    android_support::init_android_logging("ripdpi-tunnel-native");
    match env
        .with_env(move |env| -> jni::errors::Result<jni::sys::jstring> { Ok(forwarding_evidence_session(env, handle)) })
        .into_outcome()
    {
        Outcome::Ok(evidence) => evidence,
        Outcome::Err(err) => {
            log::error!("Tunnel forwarding evidence retrieval failed: {err}");
            throw_runtime_exception(
                &mut env,
                sanitize_error_message(&err.to_string(), "Tunnel forwarding evidence retrieval failed"),
            );
            std::ptr::null_mut()
        }
        Outcome::Panic(_) => {
            log::error!("Tunnel forwarding evidence retrieval panicked");
            std::ptr::null_mut()
        }
    }
}

#[cfg(test)]
pub(crate) fn tunnel_forwarding_evidence_panic_entry(mut env: EnvUnowned<'_>) -> jni::sys::jstring {
    android_support::init_android_logging("ripdpi-tunnel-native");
    match env
        .with_env(|_env| -> jni::errors::Result<jni::sys::jstring> { panic!("injected forwarding evidence panic") })
        .into_outcome()
    {
        Outcome::Ok(evidence) => evidence,
        Outcome::Err(err) => {
            log::error!("Tunnel forwarding evidence panic test failed unexpectedly: {err}");
            throw_runtime_exception(
                &mut env,
                sanitize_error_message(&err.to_string(), "Tunnel forwarding evidence retrieval failed"),
            );
            std::ptr::null_mut()
        }
        Outcome::Panic(_) => std::ptr::null_mut(),
    }
}

pub(crate) fn tunnel_icmp_ingress_packets_entry(mut env: EnvUnowned<'_>, handle: jlong) -> jlong {
    android_support::init_android_logging("ripdpi-tunnel-native");
    match env
        .with_env(move |env| -> jni::errors::Result<jlong> { Ok(icmp_ingress_packets_session(env, handle)) })
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        Outcome::Err(err) => {
            log::error!("Tunnel ICMP ingress retrieval failed: {err}");
            throw_runtime_exception(
                &mut env,
                sanitize_error_message(&err.to_string(), "Tunnel ICMP ingress retrieval failed"),
            );
            0
        }
        Outcome::Panic(_) => {
            log::error!("Tunnel ICMP ingress retrieval panicked");
            throw_runtime_exception(&mut env, sanitize_error_message("panic", "Tunnel ICMP ingress retrieval failed"));
            0
        }
    }
}

pub(crate) fn tunnel_telemetry_entry(mut env: EnvUnowned<'_>, handle: jlong) -> jni::sys::jstring {
    android_support::init_android_logging("ripdpi-tunnel-native");
    match env
        .with_env(move |env| -> jni::errors::Result<jni::sys::jstring> { Ok(telemetry_session(env, handle)) })
        .into_outcome()
    {
        Outcome::Ok(telemetry) => telemetry,
        Outcome::Err(err) => {
            log::error!("Tunnel telemetry retrieval failed: {err}");
            throw_runtime_exception(
                &mut env,
                sanitize_error_message(&err.to_string(), "Tunnel telemetry retrieval failed"),
            );
            std::ptr::null_mut()
        }
        Outcome::Panic(_) => {
            log::error!("Tunnel telemetry retrieval panicked");
            throw_runtime_exception(&mut env, sanitize_error_message("panic", "Tunnel telemetry retrieval failed"));
            std::ptr::null_mut()
        }
    }
}

pub(crate) fn tunnel_destroy_entry(mut env: EnvUnowned<'_>, handle: jlong) {
    android_support::init_android_logging("ripdpi-tunnel-native");
    match env
        .with_env(move |env| -> jni::errors::Result<()> {
            destroy_session(env, handle);
            Ok(())
        })
        .into_outcome()
    {
        Outcome::Ok(()) => {}
        Outcome::Err(err) => {
            log::error!("Tunnel session destroy failed: {err}");
            throw_runtime_exception(
                &mut env,
                sanitize_error_message(&err.to_string(), "Tunnel session destroy failed"),
            );
        }
        Outcome::Panic(_) => {
            log::error!("Tunnel session destroy panicked");
            throw_runtime_exception(&mut env, sanitize_error_message("panic", "Tunnel session destroy failed"));
        }
    }
}
