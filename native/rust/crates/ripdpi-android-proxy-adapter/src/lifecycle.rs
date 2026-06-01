use std::sync::PoisonError;

use android_support::throw_illegal_argument_env;
use jni::Env;
use jni::objects::JString;
use jni::sys::jlong;
use ripdpi_config::RuntimeConfig;
use ripdpi_proxy_config::NetworkSnapshot;

pub(crate) use crate::lifecycle_create::create_session;
pub(crate) use crate::lifecycle_start::start_session;
use ripdpi_android_bridge_support::{NativeBridgeError, NativeBridgeErrorDomain, throw_illegal_state_env_with_payload};
use ripdpi_android_telemetry_adapter::ProxyTelemetryState;

use super::registry::{
    ProxySessionState, control_for_proxy_stop, ensure_proxy_destroyable, lookup_proxy_session, remove_proxy_session,
};

pub(crate) fn proxy_error(code: &'static str, message: impl Into<String>) -> NativeBridgeError {
    NativeBridgeError::new(NativeBridgeErrorDomain::Proxy, code, message)
}

/// Drop guard that resets proxy state to `Idle` if the session is still
/// `Running` when the guard is dropped (e.g. due to a panic inside the
/// blocking runtime call). After a normal return the caller must
/// [`std::mem::forget`] the guard to skip the redundant reset.
pub(crate) struct IdleGuard<'a> {
    pub(crate) state: &'a std::sync::Mutex<ProxySessionState>,
}

impl Drop for IdleGuard<'_> {
    fn drop(&mut self) {
        let mut state = self.state.lock().unwrap_or_else(PoisonError::into_inner);
        if matches!(*state, ProxySessionState::Running { .. }) {
            *state = ProxySessionState::Idle;
        }
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

pub(crate) fn positive_os_error(err: &std::io::Error, fallback: i32) -> i32 {
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
