use std::sync::{Arc, Mutex, PoisonError};

use android_support::{
    android_log_level_from_debug_verbosity, android_log_level_from_str, set_android_log_scope_level,
    throw_illegal_argument_env,
};
use jni::objects::JString;
use jni::sys::{jint, jlong};
use jni::Env;
use ripdpi_config::RuntimeConfig;
use ripdpi_proxy_config::NetworkSnapshot;
use ripdpi_runtime_api::EmbeddedProxyControl;

use crate::config::{parse_proxy_config_json, runtime_config_envelope_from_payload};
use ripdpi_android_bridge_support::{
    throw_illegal_argument_env_with_payload, throw_illegal_state_env_with_payload, throw_io_exception_env_with_payload,
    JniProxyError, NativeBridgeError, NativeBridgeErrorDomain,
};
use ripdpi_android_telemetry_adapter::{ProxyTelemetryObserver, ProxyTelemetryState};

use super::registry::{
    control_for_proxy_stop, ensure_proxy_destroyable, lookup_proxy_session, remove_proxy_session,
    try_mark_proxy_running, ProxySession, ProxySessionState, SESSIONS,
};

fn proxy_error(code: &'static str, message: impl Into<String>) -> NativeBridgeError {
    NativeBridgeError::new(NativeBridgeErrorDomain::Proxy, code, message)
}

pub(crate) fn create_session(env: &mut Env<'_>, config_json: JString) -> jlong {
    let Ok(json) = config_json.try_to_string(env) else {
        throw_illegal_argument_env_with_payload(
            env,
            "Invalid proxy config payload",
            &proxy_error("create_config_invalid", "Invalid proxy config payload")
                .with_cause_class("java.lang.IllegalArgumentException"),
        );
        return 0;
    };

    let payload = match parse_proxy_config_json(&json) {
        Ok(payload) => payload,
        Err(err) => {
            let detail = err.to_string();
            err.throw_with_payload(
                env,
                &proxy_error("create_config_parse_failed", detail)
                    .with_cause_class("java.lang.IllegalArgumentException"),
            );
            return 0;
        }
    };

    let envelope = match runtime_config_envelope_from_payload(payload) {
        Ok(envelope) => envelope,
        Err(err) => {
            let detail = err.to_string();
            err.throw_with_payload(
                env,
                &proxy_error("create_config_envelope_failed", detail)
                    .with_cause_class("java.lang.IllegalArgumentException"),
            );
            return 0;
        }
    };
    let native_log_level = match envelope.native_log_level.as_deref() {
        Some(value) => match android_log_level_from_str(value) {
            Some(level) => level,
            None => {
                let detail = format!("Unsupported proxy nativeLogLevel: {value}");
                throw_illegal_argument_env_with_payload(
                    env,
                    &detail,
                    &proxy_error("create_unsupported_log_level", detail.clone())
                        .with_cause_class("java.lang.IllegalArgumentException"),
                );
                return 0;
            }
        },
        None => android_log_level_from_debug_verbosity(envelope.config.process.debug),
    };
    let config = envelope.config;

    if let Err(err) = ripdpi_proxy_runtime::create_listener(&config) {
        let detail = err.to_string();
        JniProxyError::Io(err).throw_with_payload(
            env,
            &proxy_error("create_listener_probe_failed", detail)
                .with_cause_class("java.io.IOException")
                .retryable(true),
        );
        return 0;
    }

    let autolearn_enabled = config.host_autolearn.enabled;
    let telemetry = Arc::new(ProxyTelemetryState::new(envelope.log_context.clone()));
    set_android_log_scope_level(telemetry.log_scope().to_string(), native_log_level);
    telemetry.set_autolearn_state(autolearn_enabled, 0, 0, 0, None, None);

    SESSIONS.insert(ProxySession {
        config,
        runtime_context: envelope.runtime_context,
        telemetry,
        state: Mutex::new(ProxySessionState::Idle),
    }) as jlong
}

/// Drop guard that resets proxy state to `Idle` if the session is still
/// `Running` when the guard is dropped (e.g. due to a panic inside the
/// blocking runtime call). After a normal return the caller must
/// [`std::mem::forget`] the guard to skip the redundant reset.
struct IdleGuard<'a> {
    state: &'a Mutex<ProxySessionState>,
}

impl Drop for IdleGuard<'_> {
    fn drop(&mut self) {
        let mut state = self.state.lock().unwrap_or_else(PoisonError::into_inner);
        if matches!(*state, ProxySessionState::Running { .. }) {
            *state = ProxySessionState::Idle;
        }
    }
}

pub(crate) fn start_session(env: &mut Env<'_>, handle: jlong) -> jint {
    let session = match lookup_proxy_session(handle) {
        Ok(session) => session,
        Err(err) => {
            let detail = err.to_string();
            err.throw_with_payload(
                env,
                &proxy_error("start_handle_invalid", detail)
                    .with_cause_class("java.lang.IllegalArgumentException")
                    .with_handle_state("invalid_or_unknown"),
            );
            return libc::EINVAL;
        }
    };

    let config = session.config.clone();
    let listener = match open_proxy_listener(&config, &session.telemetry) {
        Ok(listener) => listener,
        Err(err) => {
            let detail = format!("Failed to open proxy listener: {err}");
            throw_io_exception_env_with_payload(
                env,
                &detail,
                &proxy_error("start_listener_open_failed", detail.clone())
                    .with_cause_class("java.io.IOException")
                    .retryable(true),
            );
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
        if let Err(message) = try_mark_proxy_running(&mut state, control.clone()) {
            throw_illegal_state_env_with_payload(
                env,
                message,
                &proxy_error("start_invalid_state", message)
                    .with_cause_class("java.lang.IllegalStateException")
                    .with_handle_state(message),
            );
            return libc::EINVAL;
        }
    }

    // Guard ensures state resets to Idle if the runtime call panics.
    let guard = IdleGuard { state: &session.state };

    let result = ripdpi_proxy_runtime::run_proxy_with_embedded_control(config, listener, control);

    // Normal exit: reset state ourselves and disarm the guard.
    let mut state = session.state.lock().unwrap_or_else(PoisonError::into_inner);
    *state = ProxySessionState::Idle;
    drop(state);
    std::mem::forget(guard);

    if let Err(err) = &result {
        session.telemetry.on_client_error(err.to_string());
    }

    match result {
        Ok(()) => 0,
        Err(err) => positive_os_error(&err, libc::EINVAL),
    }
}

pub(crate) fn stop_session(env: &mut Env<'_>, handle: jlong) {
    let session = match lookup_proxy_session(handle) {
        Ok(session) => session,
        Err(err) => {
            let detail = err.to_string();
            err.throw_with_payload(
                env,
                &proxy_error("stop_handle_invalid", detail)
                    .with_cause_class("java.lang.IllegalArgumentException")
                    .with_handle_state("invalid_or_unknown"),
            );
            return;
        }
    };

    let control = {
        let state = session.state.lock().unwrap_or_else(PoisonError::into_inner);
        match control_for_proxy_stop(&state) {
            Ok(control) => control,
            Err(message) => {
                throw_illegal_state_env_with_payload(
                    env,
                    message,
                    &proxy_error("stop_invalid_state", message)
                        .with_cause_class("java.lang.IllegalStateException")
                        .with_handle_state(message),
                );
                return;
            }
        }
    };

    control.request_shutdown();
    session.telemetry.push_event("proxy", "info", "stop requested".to_string());
}

pub(crate) fn update_network_snapshot(env: &mut Env<'_>, handle: jlong, snapshot_json: JString) {
    let Ok(json) = snapshot_json.try_to_string(env) else {
        throw_illegal_argument_env(env, "Invalid network snapshot JSON");
        return;
    };
    let snapshot: NetworkSnapshot = match serde_json::from_str(&json) {
        Ok(value) => value,
        Err(err) => {
            throw_illegal_argument_env(env, format!("Failed to parse network snapshot: {err}"));
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
    if let ProxySessionState::Running { control } = &*state {
        control.update_network_snapshot(snapshot);
    }
    // If the session is Idle or Destroyed, ignore: snapshot will be re-pushed on next start.
}

pub(crate) fn destroy_session(env: &mut Env<'_>, handle: jlong) {
    let session = match lookup_proxy_session(handle) {
        Ok(session) => session,
        Err(err) => {
            let detail = err.to_string();
            err.throw_with_payload(
                env,
                &proxy_error("destroy_handle_invalid", detail)
                    .with_cause_class("java.lang.IllegalArgumentException")
                    .with_handle_state("invalid_or_unknown"),
            );
            return;
        }
    };
    let mut state = session.state.lock().unwrap_or_else(PoisonError::into_inner);
    if let Err(message) = ensure_proxy_destroyable(&state) {
        throw_illegal_state_env_with_payload(
            env,
            message,
            &proxy_error("destroy_invalid_state", message)
                .with_cause_class("java.lang.IllegalStateException")
                .with_handle_state(message),
        );
        return;
    }
    // Tombstone the session before releasing the lock so concurrent
    // callers see Destroyed rather than racing on an Idle session.
    *state = ProxySessionState::Destroyed;
    drop(state);
    let _ = remove_proxy_session(handle);
}

fn positive_os_error(err: &std::io::Error, fallback: i32) -> i32 {
    err.raw_os_error().unwrap_or(fallback)
}

pub(crate) fn open_proxy_listener(
    config: &RuntimeConfig,
    telemetry: &ProxyTelemetryState,
) -> Result<std::net::TcpListener, std::io::Error> {
    ripdpi_proxy_runtime::create_listener(config).map_err(|err| {
        telemetry.on_client_error(format!("listener open failed: {err}"));
        err
    })
}
