use std::sync::{Arc, PoisonError};

use jni::Env;
use jni::sys::{jint, jlong};
use ripdpi_runtime_api::EmbeddedProxyControl;

use crate::lifecycle::{IdleGuard, open_proxy_listener, positive_os_error, proxy_error};
use crate::quality_sink::CompositeProxyTelemetrySink;
use crate::registry::{ProxySessionState, lookup_proxy_session, try_mark_proxy_running};
use ripdpi_android_bridge_support::{throw_illegal_state_env_with_payload, throw_io_exception_env_with_payload};
use ripdpi_android_telemetry_adapter::ProxyTelemetryObserver;

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
    // Install the QualityWindowSink alongside the existing
    // ProxyTelemetryObserver so producer-side TCP-connect timing in
    // ripdpi-proxy-runtime feeds the process-wide QualityWindow. See
    // `quality_sink.rs` for the composition rationale.
    let observer = ProxyTelemetryObserver { state: session.telemetry.clone() };
    let control = Arc::new(EmbeddedProxyControl::new_with_context(
        Some(Arc::new(CompositeProxyTelemetrySink::new(observer))),
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

    let guard = IdleGuard { state: &session.state };
    let result = ripdpi_proxy_runtime::run_proxy_with_embedded_control(
        config,
        listener,
        control,
        Arc::new(ripdpi_ws_tunnel::TelegramWsTransport),
    );
    let mut state = session.state.lock().unwrap_or_else(PoisonError::into_inner);
    *state = ProxySessionState::Idle;
    drop(state);
    std::mem::forget(guard);

    if let Err(err) = &result {
        session.telemetry.on_client_error(err.to_string());
    }
    result.map_or_else(|err| positive_os_error(&err, libc::EINVAL), |_| 0)
}
