use std::sync::Arc;
use std::sync::mpsc::{Receiver, RecvTimeoutError, sync_channel};
use std::time::Duration;

use android_support::{throw_illegal_argument_env, throw_illegal_state_env, throw_io_exception_env};
use jni::Env;
use jni::sys::{jint, jlong};
use ripdpi_tunnel_core::Stats;
use tokio_util::sync::CancellationToken;

use super::pcap;
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

const NATIVE_READY_TIMEOUT: Duration = Duration::from_secs(5);

fn wait_for_native_readiness(readiness: &Receiver<()>, timeout: Duration) -> Result<(), RecvTimeoutError> {
    readiness.recv_timeout(timeout)
}

fn defer_failed_tunnel_start_cleanup(
    session: &Arc<super::registry::TunnelSession>,
    cancel: Arc<CancellationToken>,
    worker: std::thread::JoinHandle<()>,
    message: String,
) {
    cancel.cancel();
    {
        let mut guard = session.last_error.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        *guard = Some(message.clone());
    }
    session.telemetry.record_error(message);
    {
        let mut state = session.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        *state = TunnelSessionState::CleanupPending { cancel };
    }

    // The startup JNI call must honor its readiness deadline even if fallible
    // setup is blocked in a syscall. The shared runtime owns this blocking
    // reaper until the worker releases its TUN-fd duplicate; dropping the task
    // handle does not detach that ownership from the runtime.
    let cleanup_session = Arc::clone(session);
    drop(session.runtime.spawn_blocking(move || {
        if worker.join().is_err() {
            cleanup_session.telemetry.log_line("worker", "error", "tunnel worker panicked during startup cleanup");
        }
        let mut state = cleanup_session.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        if matches!(*state, TunnelSessionState::CleanupPending { .. }) {
            *state = TunnelSessionState::Ready;
        }
    }));
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
    pcap::pcap_retire_entry(handle);
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
    pcap::pcap_retire_entry(handle);
    let _ = remove_tunnel_session(handle);
}

#[cfg(test)]
mod tests {
    use std::sync::{Arc, Mutex, mpsc};
    use std::time::{Duration, Instant};

    use crate::config::{config_from_payload, sample_payload};
    use crate::telemetry::TunnelTelemetryState;

    use super::super::registry::{TunnelSession, TunnelSessionState};
    use super::{defer_failed_tunnel_start_cleanup, ensure_tunnel_destroyable, wait_for_native_readiness};
    use tokio_util::sync::CancellationToken;

    #[test]
    fn native_readiness_wait_honors_injected_deadline() {
        let (_ready_sender, readiness) = mpsc::sync_channel(1);
        let timeout = Duration::from_millis(20);
        let started = Instant::now();

        let result = wait_for_native_readiness(&readiness, timeout);

        assert!(matches!(result, Err(mpsc::RecvTimeoutError::Timeout)));
        assert!(started.elapsed() >= timeout, "readiness wait returned before its deadline");
        assert!(started.elapsed() < Duration::from_secs(1), "readiness wait exceeded its bounded test deadline");
    }

    #[test]
    fn timed_out_start_defers_blocked_worker_join_without_losing_ownership() {
        let runtime = Arc::new(
            tokio::runtime::Builder::new_multi_thread().worker_threads(1).enable_all().build().expect("test runtime"),
        );
        let cancel = Arc::new(CancellationToken::new());
        let session = Arc::new(TunnelSession {
            runtime,
            config: Arc::new(config_from_payload(sample_payload()).expect("config")),
            last_error: Arc::new(Mutex::new(None)),
            telemetry: Arc::new(TunnelTelemetryState::new(None)),
            state: Mutex::new(TunnelSessionState::Starting { cancel: Arc::clone(&cancel) }),
        });
        let (release_sender, release_receiver) = mpsc::sync_channel(1);
        let worker = std::thread::spawn(move || {
            let _ = release_receiver.recv();
        });
        let started = Instant::now();

        defer_failed_tunnel_start_cleanup(&session, Arc::clone(&cancel), worker, "readiness timed out".to_string());

        assert!(started.elapsed() < Duration::from_millis(250), "startup cleanup blocked on worker join");
        assert!(cancel.is_cancelled(), "timed-out startup must request worker cancellation");
        {
            let state = session.state.lock().expect("state lock");
            assert!(matches!(*state, TunnelSessionState::CleanupPending { .. }));
            assert!(ensure_tunnel_destroyable(&state).is_ok(), "cleanup-owned session must be removable");
        }
        release_sender.send(()).expect("release worker");

        let deadline = Instant::now() + Duration::from_secs(1);
        loop {
            if matches!(*session.state.lock().expect("state lock"), TunnelSessionState::Ready) {
                break;
            }
            assert!(Instant::now() < deadline, "runtime reaper did not retire the startup worker");
            std::thread::yield_now();
        }
        assert_eq!(session.last_error.lock().expect("last error").as_deref(), Some("readiness timed out"));
    }
}
