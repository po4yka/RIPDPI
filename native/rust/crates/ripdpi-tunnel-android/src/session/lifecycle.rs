use std::sync::Arc;

use android_support::{throw_illegal_argument_env, throw_illegal_state_env, throw_io_exception_env};
use jni::Env;
use jni::sys::{jint, jlong};
use ripdpi_tunnel_core::Stats;
use tokio_util::sync::CancellationToken;

use super::registry::{TunnelSessionState, lookup_tunnel_session, remove_tunnel_session};

mod create;
mod fd;
mod state;
mod telemetry;
mod validation;
mod worker;

pub(crate) use create::create_session;
pub(crate) use state::{
    ensure_tunnel_destroyable, ensure_tunnel_start_allowed, rollback_failed_tunnel_start, take_running_tunnel,
};
pub(crate) use validation::validate_tun_fd;

use fd::adopt_tun_fd;
use telemetry::{mark_session_started, wire_session_telemetry};
use worker::{WorkerLaunch, launch_tunnel_worker};

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
    let owned_fd = match adopt_tun_fd(tun_fd) {
        Ok(fd) => fd,
        Err(message) => {
            throw_io_exception_env(env, message);
            return;
        }
    };

    let cancel = Arc::new(CancellationToken::new());
    let stats = Arc::new(Stats::new());
    wire_session_telemetry(&stats, &session.telemetry);

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
    mark_session_started(&session.telemetry, &session.config);

    // JNI lifecycle contract: start_session performs validation, adopts the
    // TUN fd, transitions Ready -> Starting -> Running, and spawns the tunnel
    // worker. It must not run packet IO on the JNI caller thread; long-running
    // tunnel work belongs exclusively to the worker returned here.
    let worker = match launch_tunnel_worker(WorkerLaunch {
        runtime: session.runtime.clone(),
        config: session.config.clone(),
        owned_fd,
        cancel: cancel.clone(),
        stats: stats.clone(),
        telemetry: session.telemetry.clone(),
        last_error: session.last_error.clone(),
    }) {
        Ok(worker) => worker,
        Err(err) => {
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
