use std::sync::Arc;
use std::sync::mpsc::{RecvTimeoutError, sync_channel};
use std::time::Duration;

use android_support::{throw_illegal_argument_env, throw_illegal_state_env, throw_io_exception_env};
use jni::Env;
use jni::sys::{jint, jlong};
use ripdpi_tunnel_core::Stats;
use tokio_util::sync::CancellationToken;

use super::super::registry::{TunnelSessionState, lookup_tunnel_session};
use super::fd::adopt_tun_fd;
use super::readiness::{defer_failed_tunnel_start_cleanup, wait_for_native_readiness};
use super::telemetry::{mark_session_started, wire_session_telemetry};
use super::worker::{WorkerLaunch, launch_tunnel_worker};
use super::{ensure_tunnel_start_allowed, rollback_failed_tunnel_start, validate_tun_fd};

const NATIVE_READY_TIMEOUT: Duration = Duration::from_secs(5);

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
    // JNI lifecycle contract: start_session performs validation, adopts the
    // TUN fd, transitions Ready -> Starting, and spawns the tunnel worker. It
    // publishes Running only after the worker has completed all fallible packet
    // loop setup. Long-running tunnel work remains exclusively on that worker.
    let (startup_ready, readiness) = sync_channel(1);
    let worker = match launch_tunnel_worker(WorkerLaunch {
        runtime: session.runtime.clone(),
        config: session.config.clone(),
        owned_fd,
        cancel: cancel.clone(),
        stats: stats.clone(),
        telemetry: session.telemetry.clone(),
        last_error: session.last_error.clone(),
        startup_ready,
    }) {
        Ok(worker) => worker,
        Err(err) => {
            rollback_failed_tunnel_start(&session, format!("failed to spawn tunnel worker thread: {err}"));
            throw_io_exception_env(env, format!("Failed to spawn tunnel worker thread: {err}"));
            return;
        }
    };

    if let Err(readiness_error) = wait_for_native_readiness(&readiness, NATIVE_READY_TIMEOUT) {
        let worker_error = session.last_error.lock().unwrap_or_else(std::sync::PoisonError::into_inner).clone();
        let message = worker_error.unwrap_or_else(|| match readiness_error {
            RecvTimeoutError::Timeout => "native tunnel readiness timed out".to_string(),
            RecvTimeoutError::Disconnected => "native tunnel worker exited before readiness".to_string(),
        });
        defer_failed_tunnel_start_cleanup(&session, cancel, worker, message.clone());
        throw_io_exception_env(env, message);
        return;
    }

    if worker.is_finished() {
        let worker_panicked = worker.join().is_err();
        let message =
            session.last_error.lock().unwrap_or_else(std::sync::PoisonError::into_inner).clone().unwrap_or_else(|| {
                if worker_panicked {
                    "native tunnel worker panicked immediately after readiness".to_string()
                } else {
                    "native tunnel worker exited immediately after readiness".to_string()
                }
            });
        rollback_failed_tunnel_start(&session, message.clone());
        throw_io_exception_env(env, message);
        return;
    }

    mark_session_started(&session.telemetry, &session.config);

    let mut state = session.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    *state = TunnelSessionState::Running { cancel, stats, worker };
}
