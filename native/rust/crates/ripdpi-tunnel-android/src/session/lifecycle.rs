use std::os::fd::{BorrowedFd, IntoRawFd};
use std::sync::{Arc, Mutex};

use android_support::{
    android_log_level_from_str, set_android_log_scope_level, throw_illegal_argument_env, throw_illegal_state_env,
    throw_io_exception_env,
};
use jni::objects::JString;
use jni::sys::{jint, jlong};
use jni::Env;
use ripdpi_tunnel_core::Stats;
use tokio_util::sync::CancellationToken;

use crate::config::{config_from_payload, parse_tunnel_config_json, sanitize_log_context};
use crate::telemetry::TunnelTelemetryState;

use super::registry::{
    lookup_tunnel_session, remove_tunnel_session, shared_tunnel_runtime, TunnelSession, TunnelSessionState, SESSIONS,
};

pub(crate) fn create_session(env: &mut Env<'_>, config_json: JString) -> jlong {
    let Ok(json) = config_json.try_to_string(env) else {
        throw_illegal_argument_env(env, "Invalid tunnel config payload");
        return 0;
    };
    let payload = match parse_tunnel_config_json(&json) {
        Ok(payload) => payload,
        Err(err) => {
            throw_illegal_argument_env(env, err);
            return 0;
        }
    };
    let log_context = sanitize_log_context(payload.log_context.clone());
    let config = match config_from_payload(payload) {
        Ok(config) => Arc::new(config),
        Err(message) => {
            throw_illegal_argument_env(env, message);
            return 0;
        }
    };
    let Some(native_log_level) = android_log_level_from_str(&config.misc.log_level) else {
        throw_illegal_argument_env(env, format!("Unsupported tunnel logLevel: {}", config.misc.log_level));
        return 0;
    };
    let runtime = match shared_tunnel_runtime() {
        Ok(runtime) => runtime,
        Err(err) => {
            throw_io_exception_env(env, format!("Failed to initialize Tokio runtime: {err}"));
            return 0;
        }
    };
    let telemetry = Arc::new(TunnelTelemetryState::new(log_context));
    set_android_log_scope_level(telemetry.log_scope().to_string(), native_log_level);

    SESSIONS.insert(TunnelSession {
        runtime,
        config,
        last_error: Arc::new(Mutex::new(None)),
        telemetry,
        state: Mutex::new(TunnelSessionState::Ready),
    }) as jlong
}

pub(crate) fn start_session(env: &mut Env<'_>, handle: jlong, tun_fd: jint) {
    let session = match lookup_tunnel_session(handle) {
        Ok(session) => session,
        Err(message) => {
            throw_illegal_argument_env(env, message);
            return;
        }
    };
    if let Err(message) = validate_tun_fd(tun_fd) {
        throw_illegal_argument_env(env, message);
        return;
    }
    // Duplicate the fd so run_tunnel owns an independent copy.
    // If VpnService revokes the original fd, the dup'd fd remains valid
    // until run_tunnel closes it via File::from_raw_fd.
    let owned_fd = match unsafe { nix::unistd::dup(BorrowedFd::borrow_raw(tun_fd)) } {
        Ok(fd) => fd,
        Err(err) => {
            throw_io_exception_env(env, format!("Failed to dup TUN fd: {err}"));
            return;
        }
    };
    // Verify the dup'd fd is a valid open file descriptor
    if let Err(err) = nix::sys::stat::fstat(&owned_fd) {
        // OwnedFd drops and closes automatically
        throw_io_exception_env(env, format!("TUN fd validation failed: {err}"));
        return;
    }
    let runtime = session.runtime.clone();

    let cancel = Arc::new(CancellationToken::new());
    let stats = Arc::new(Stats::new());
    let config = session.config.clone();
    let last_error = session.last_error.clone();
    let telemetry = session.telemetry.clone();

    // Wire the DNS latency histogram: clone shares the Arc<Mutex<Histogram>>
    // inside LatencyHistogram so the closure and telemetry state observe the
    // same underlying data without requiring ripdpi-tunnel-core to import ripdpi-telemetry.
    let dns_histogram = telemetry.dns_histogram.clone();
    stats.set_dns_latency_observer(Arc::new(move |ms| dns_histogram.record(ms)));

    {
        let mut state = session.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        if let Err(message) = ensure_tunnel_start_allowed(&state) {
            drop(owned_fd);
            throw_illegal_state_env(env, message);
            return;
        }
        *state = TunnelSessionState::Starting { cancel: cancel.clone() };
    }

    {
        let mut guard = session.last_error.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        *guard = None;
    }
    telemetry.mark_started(format!("{}:{}", session.config.socks5.address, session.config.socks5.port));

    let worker_cancel = cancel.clone();
    let worker_stats = stats.clone();
    let worker = match std::thread::Builder::new().name("ripdpi-tunnel-worker".into()).spawn(move || {
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            runtime.block_on(ripdpi_tunnel_core::run_tunnel(
                config,
                owned_fd.into_raw_fd(),
                (*worker_cancel).clone(),
                worker_stats.clone(),
            ))
        }));

        match result {
            Ok(Ok(())) => {}
            Ok(Err(err)) => {
                telemetry.log_line("worker", "error", &format!("worker exited with error: {err}"));
                let mut guard = last_error.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
                *guard = Some(err.to_string());
                drop(guard);
                telemetry.record_error(err.to_string());
            }
            Err(panic) => {
                let msg = if let Some(s) = panic.downcast_ref::<&str>() {
                    s.to_string()
                } else if let Some(s) = panic.downcast_ref::<String>() {
                    s.clone()
                } else {
                    "unknown panic".to_string()
                };
                telemetry.log_line("worker", "error", &format!("worker panicked: {msg}"));
                let mut guard = last_error.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
                *guard = Some(format!("Tunnel worker panicked: {msg}"));
                drop(guard);
                telemetry.record_error(format!("Tunnel worker panicked: {msg}"));
            }
        }
        telemetry.mark_stopped();
    }) {
        Ok(worker) => worker,
        Err(err) => {
            // owned_fd was moved into the closure; if spawn failed the closure
            // is dropped, so OwnedFd::drop closes the fd automatically.
            rollback_failed_tunnel_start(&session, format!("failed to spawn tunnel worker thread: {err}"));
            throw_io_exception_env(env, format!("Failed to spawn tunnel worker thread: {err}"));
            return;
        }
    };

    let mut state = session.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    *state = TunnelSessionState::Running { cancel, stats, worker };
}

pub(crate) fn stop_session(env: &mut Env<'_>, handle: jlong) {
    let session = match lookup_tunnel_session(handle) {
        Ok(session) => session,
        Err(message) => {
            throw_illegal_argument_env(env, message);
            return;
        }
    };

    let running = {
        let mut state = session.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        match take_running_tunnel(&mut state) {
            Ok(running) => running,
            Err(message) => {
                throw_illegal_state_env(env, message);
                return;
            }
        }
    };

    running.0.cancel();
    session.telemetry.mark_stop_requested();
    if running.1.join().is_err() {
        session.telemetry.log_line("worker", "error", "tunnel worker panicked during shutdown");
    }
}

pub(crate) fn destroy_session(env: &mut Env<'_>, handle: jlong) {
    let session = match lookup_tunnel_session(handle) {
        Ok(session) => session,
        Err(message) => {
            throw_illegal_argument_env(env, message);
            return;
        }
    };
    let mut state = session.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    if let Err(message) = ensure_tunnel_destroyable(&state) {
        throw_illegal_state_env(env, message);
        return;
    }
    *state = TunnelSessionState::Destroyed;
    drop(state);
    let _ = remove_tunnel_session(handle);
}

pub(crate) fn validate_tun_fd(tun_fd: jint) -> Result<(), &'static str> {
    if tun_fd < 0 {
        Err("Invalid TUN file descriptor")
    } else {
        Ok(())
    }
}

pub(crate) fn ensure_tunnel_start_allowed(state: &TunnelSessionState) -> Result<(), &'static str> {
    match state {
        TunnelSessionState::Ready => Ok(()),
        TunnelSessionState::Starting { .. } => Err("Tunnel session is already starting"),
        TunnelSessionState::Running { .. } => Err("Tunnel session is already running"),
        TunnelSessionState::Destroyed => Err("Tunnel session has been destroyed"),
    }
}

pub(crate) fn take_running_tunnel(
    state: &mut TunnelSessionState,
) -> Result<(Arc<CancellationToken>, std::thread::JoinHandle<()>), &'static str> {
    match state {
        TunnelSessionState::Running { .. } => {
            // Only replace when actually Running -- other states must not be mutated.
            let TunnelSessionState::Running { cancel, worker, .. } =
                std::mem::replace(state, TunnelSessionState::Ready)
            else {
                unreachable!("just matched Running");
            };
            Ok((cancel, worker))
        }
        TunnelSessionState::Starting { cancel } => {
            // Startup is still in progress -- request cancellation so the worker
            // thread observes it once it enters the run loop.
            cancel.cancel();
            Err("Tunnel session is still starting; cancellation requested")
        }
        TunnelSessionState::Ready => Err("Tunnel session is not running"),
        TunnelSessionState::Destroyed => Err("Tunnel session has been destroyed"),
    }
}

pub(crate) fn ensure_tunnel_destroyable(state: &TunnelSessionState) -> Result<(), &'static str> {
    match state {
        TunnelSessionState::Ready => Ok(()),
        TunnelSessionState::Starting { .. } => Err("Cannot destroy a starting tunnel session"),
        TunnelSessionState::Running { .. } => Err("Cannot destroy a running tunnel session"),
        TunnelSessionState::Destroyed => Err("Tunnel session has already been destroyed"),
    }
}

pub(crate) fn rollback_failed_tunnel_start(session: &TunnelSession, message: String) {
    {
        let mut guard = session.last_error.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        *guard = Some(message.clone());
    }
    session.telemetry.record_error(message);
    session.telemetry.mark_stopped();
    {
        let mut state = session.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        *state = TunnelSessionState::Ready;
    }
}
