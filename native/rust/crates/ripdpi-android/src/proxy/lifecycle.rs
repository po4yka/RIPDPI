use std::os::fd::AsRawFd;
use std::sync::{Arc, Mutex, PoisonError};

use android_support::{
    android_log_level_from_debug_verbosity, android_log_level_from_str, set_android_log_scope_level,
    throw_illegal_argument, throw_illegal_state, throw_io_exception,
};
use jni::objects::JString;
use jni::sys::{jint, jlong};
use jni::JNIEnv;
use ripdpi_config::RuntimeConfig;
use ripdpi_proxy_config::NetworkSnapshot;
use ripdpi_runtime::{runtime, EmbeddedProxyControl};

use crate::config::{parse_proxy_config_json, runtime_config_envelope_from_payload};
use crate::errors::JniProxyError;
use crate::telemetry::{ProxyTelemetryObserver, ProxyTelemetryState};

use super::registry::{
    ensure_proxy_destroyable, listener_fd_for_proxy_stop, lookup_proxy_session, remove_proxy_session,
    try_mark_proxy_running, ProxySession, ProxySessionState, SESSIONS,
};

pub(crate) fn create_session(env: &mut JNIEnv, config_json: JString) -> jlong {
    let json: String = match env.get_string(&config_json) {
        Ok(value) => value.into(),
        Err(_) => {
            throw_illegal_argument(env, "Invalid proxy config payload");
            return 0;
        }
    };

    let payload = match parse_proxy_config_json(&json) {
        Ok(payload) => payload,
        Err(err) => {
            err.throw(env);
            return 0;
        }
    };

    let envelope = match runtime_config_envelope_from_payload(payload) {
        Ok(envelope) => envelope,
        Err(err) => {
            err.throw(env);
            return 0;
        }
    };
    let native_log_level = match envelope.native_log_level.as_deref() {
        Some(value) => match android_log_level_from_str(value) {
            Some(level) => level,
            None => {
                throw_illegal_argument(env, format!("Unsupported proxy nativeLogLevel: {value}"));
                return 0;
            }
        },
        None => android_log_level_from_debug_verbosity(envelope.config.debug),
    };
    let config = envelope.config;

    if let Err(err) = runtime::create_listener(&config) {
        JniProxyError::Io(err).throw(env);
        return 0;
    }

    let autolearn_enabled = config.host_autolearn_enabled;
    let telemetry = Arc::new(ProxyTelemetryState::new());
    set_android_log_scope_level(telemetry.log_scope().to_string(), native_log_level);
    telemetry.set_autolearn_state(autolearn_enabled, 0, 0);

    SESSIONS.insert(ProxySession {
        config,
        runtime_context: envelope.runtime_context,
        telemetry,
        state: Mutex::new(ProxySessionState::Idle),
    }) as jlong
}

pub(crate) fn start_session(env: &mut JNIEnv, handle: jlong) -> jint {
    let session = match lookup_proxy_session(handle) {
        Ok(session) => session,
        Err(err) => {
            err.throw(env);
            return libc::EINVAL;
        }
    };

    let config = session.config.clone();
    let (listener, listener_fd) = match open_proxy_listener(&config, &session.telemetry) {
        Ok(parts) => parts,
        Err(err) => {
            throw_io_exception(env, format!("Failed to open proxy listener: {err}"));
            return libc::EINVAL;
        }
    };

    session.telemetry.clear_last_error();
    let control = Arc::new(EmbeddedProxyControl::new_with_context(
        Some(Arc::new(ProxyTelemetryObserver { state: session.telemetry.clone() })),
        session.runtime_context.clone(),
    ));

    {
        let mut state = session.state.lock().unwrap_or_else(PoisonError::into_inner);
        if let Err(message) = try_mark_proxy_running(&mut state, listener_fd, control.clone()) {
            throw_illegal_state(env, message);
            return libc::EINVAL;
        }
    }

    let result = runtime::run_proxy_with_embedded_control(config, listener, control);

    let mut state = session.state.lock().unwrap_or_else(PoisonError::into_inner);
    *state = ProxySessionState::Idle;
    if let Err(err) = &result {
        session.telemetry.on_client_error(err.to_string());
    }

    match result {
        Ok(()) => 0,
        Err(err) => positive_os_error(&err, libc::EINVAL),
    }
}

pub(crate) fn stop_session(env: &mut JNIEnv, handle: jlong) {
    let session = match lookup_proxy_session(handle) {
        Ok(session) => session,
        Err(err) => {
            err.throw(env);
            return;
        }
    };

    let (listener_fd, control) = {
        let state = session.state.lock().unwrap_or_else(PoisonError::into_inner);
        match listener_fd_for_proxy_stop(&state) {
            Ok(parts) => parts,
            Err(message) => {
                throw_illegal_state(env, message);
                return;
            }
        }
    };

    control.request_shutdown();
    if let Err(err) = shutdown_proxy_listener(listener_fd) {
        throw_io_exception(env, format!("Failed to stop proxy listener: {err}"));
        session.telemetry.on_client_error(err.to_string());
    }
    session.telemetry.push_event("proxy", "info", "stop requested".to_string());
}

pub(crate) fn update_network_snapshot(env: &mut JNIEnv, handle: jlong, snapshot_json: JString) {
    let json: String = match env.get_string(&snapshot_json) {
        Ok(value) => value.into(),
        Err(_) => {
            throw_illegal_argument(env, "Invalid network snapshot JSON");
            return;
        }
    };
    let snapshot: NetworkSnapshot = match serde_json::from_str(&json) {
        Ok(value) => value,
        Err(err) => {
            throw_illegal_argument(env, format!("Failed to parse network snapshot: {err}"));
            return;
        }
    };
    let session = match lookup_proxy_session(handle) {
        Ok(session) => session,
        Err(err) => {
            err.throw(env);
            return;
        }
    };
    let state = session.state.lock().unwrap_or_else(PoisonError::into_inner);
    if let ProxySessionState::Running { control, .. } = &*state {
        control.update_network_snapshot(snapshot);
    }
    // If the session is Idle, ignore: snapshot will be re-pushed on next start.
}

pub(crate) fn destroy_session(env: &mut JNIEnv, handle: jlong) {
    let session = match lookup_proxy_session(handle) {
        Ok(session) => session,
        Err(err) => {
            err.throw(env);
            return;
        }
    };
    let state = session.state.lock().unwrap_or_else(PoisonError::into_inner);
    if let Err(message) = ensure_proxy_destroyable(&state) {
        throw_illegal_state(env, message);
        return;
    }
    drop(state);
    let _ = remove_proxy_session(handle);
}

fn positive_os_error(err: &std::io::Error, fallback: i32) -> i32 {
    err.raw_os_error().unwrap_or(fallback)
}

pub(crate) fn open_proxy_listener(
    config: &RuntimeConfig,
    telemetry: &ProxyTelemetryState,
) -> Result<(std::net::TcpListener, i32), std::io::Error> {
    match runtime::create_listener(config) {
        Ok(listener) => {
            let listener_fd = listener.as_raw_fd();
            Ok((listener, listener_fd))
        }
        Err(err) => {
            telemetry.on_client_error(format!("listener open failed: {err}"));
            Err(err)
        }
    }
}

pub(crate) fn shutdown_proxy_listener(listener_fd: i32) -> Result<(), std::io::Error> {
    nix::sys::socket::shutdown(listener_fd, nix::sys::socket::Shutdown::Both)
        .map_err(|e| std::io::Error::from_raw_os_error(e as i32))
}
