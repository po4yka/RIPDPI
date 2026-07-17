use std::sync::Arc;
use std::sync::mpsc::{RecvTimeoutError, sync_channel};
use std::time::Duration;

use android_support::{throw_illegal_argument_env, throw_illegal_state_env, throw_io_exception_env};
use jni::Env;
use jni::sys::{jint, jlong};
use ripdpi_tunnel_core::Stats;
use tokio_util::sync::CancellationToken;

use super::super::registry::{TunnelSession, TunnelSessionState, lookup_tunnel_session};
use super::fd::adopt_tun_fd;
use super::readiness::{defer_failed_tunnel_start_cleanup, wait_for_native_readiness};
use super::telemetry::{mark_session_started, wire_session_telemetry};
use super::worker::{WorkerLaunch, launch_tunnel_worker};
use super::{ensure_tunnel_start_allowed, rollback_failed_tunnel_start, validate_tun_fd};

const NATIVE_READY_TIMEOUT: Duration = Duration::from_secs(5);
const NATIVE_START_CANCELLED: &str = "native tunnel startup was cancelled";
const NATIVE_START_STATE_CHANGED: &str = "native tunnel startup state changed before readiness";

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

    if let Err(message) = promote_ready_tunnel_start(&session, cancel, stats, worker) {
        throw_io_exception_env(env, message);
    }
}

fn promote_ready_tunnel_start(
    session: &Arc<TunnelSession>,
    cancel: Arc<CancellationToken>,
    stats: Arc<Stats>,
    worker: std::thread::JoinHandle<()>,
) -> Result<(), String> {
    let message = {
        let mut state = session.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        match &*state {
            TunnelSessionState::Starting { cancel: state_cancel } if Arc::ptr_eq(state_cancel, &cancel) => {
                if cancel.is_cancelled() {
                    NATIVE_START_CANCELLED
                } else {
                    // The Starting cancel Arc is the startup generation token:
                    // each start creates a fresh Arc, so pointer equality proves
                    // this readiness belongs to the state we are promoting.
                    mark_session_started(&session.telemetry, &session.config);
                    *state = TunnelSessionState::Running { cancel, stats, worker };
                    return Ok(());
                }
            }
            TunnelSessionState::Ready
            | TunnelSessionState::Starting { .. }
            | TunnelSessionState::CleanupPending { .. }
            | TunnelSessionState::Running { .. }
            | TunnelSessionState::Destroyed => NATIVE_START_STATE_CHANGED,
        }
        .to_string()
    };

    defer_failed_tunnel_start_cleanup(session, cancel, worker, message.clone());
    Err(message)
}

#[cfg(test)]
mod tests {
    use std::sync::{Arc, Mutex, mpsc};
    use std::time::{Duration, Instant};

    use ripdpi_tunnel_core::{DnsStatsSnapshot, Stats};
    use tokio_util::sync::CancellationToken;

    use crate::config::{config_from_payload, sample_payload};
    use crate::session::lifecycle::take_running_tunnel;
    use crate::session::registry::{TunnelSession, TunnelSessionState};
    use crate::telemetry::TunnelTelemetryState;

    use super::promote_ready_tunnel_start;

    #[test]
    fn cancelled_starting_session_never_promotes_after_native_readiness() {
        let runtime = Arc::new(
            tokio::runtime::Builder::new_multi_thread().worker_threads(1).enable_all().build().expect("test runtime"),
        );
        let cancel = Arc::new(CancellationToken::new());
        let stats = Arc::new(Stats::new());
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

        {
            let mut state = session.state.lock().expect("state lock");
            let err = take_running_tunnel(&mut state).expect_err("stop during startup");
            assert_eq!(err, "Tunnel session is still starting; cancellation requested");
        }

        let promotion = promote_ready_tunnel_start(&session, Arc::clone(&cancel), stats, worker);

        assert_eq!(promotion.as_ref().map_err(String::as_str), Err("native tunnel startup was cancelled"));
        let snapshot = session.telemetry.snapshot((0, 0, 0, 0), DnsStatsSnapshot::default(), None, None);
        assert_eq!(snapshot.state, "idle");
        assert_eq!(snapshot.active_sessions, 0);
        assert_eq!(snapshot.total_sessions, 0);
        {
            let state = session.state.lock().expect("state lock");
            assert!(matches!(*state, TunnelSessionState::CleanupPending { .. }));
        }

        release_sender.send(()).expect("release worker");
        let deadline = Instant::now() + Duration::from_secs(1);
        loop {
            if matches!(*session.state.lock().expect("state lock"), TunnelSessionState::Ready) {
                break;
            }
            assert!(Instant::now() < deadline, "cleanup reaper did not retire cancelled startup");
            std::thread::yield_now();
        }
    }

    #[test]
    fn stale_start_generation_never_promotes_after_native_readiness() {
        let runtime = Arc::new(
            tokio::runtime::Builder::new_multi_thread().worker_threads(1).enable_all().build().expect("test runtime"),
        );
        let stale_cancel = Arc::new(CancellationToken::new());
        let current_cancel = Arc::new(CancellationToken::new());
        let stats = Arc::new(Stats::new());
        let session = Arc::new(TunnelSession {
            runtime,
            config: Arc::new(config_from_payload(sample_payload()).expect("config")),
            last_error: Arc::new(Mutex::new(None)),
            telemetry: Arc::new(TunnelTelemetryState::new(None)),
            state: Mutex::new(TunnelSessionState::Starting { cancel: Arc::clone(&current_cancel) }),
        });
        let (release_sender, release_receiver) = mpsc::sync_channel(1);
        let worker = std::thread::spawn(move || {
            let _ = release_receiver.recv();
        });

        let promotion = promote_ready_tunnel_start(&session, Arc::clone(&stale_cancel), stats, worker);

        assert_eq!(
            promotion.as_ref().map_err(String::as_str),
            Err("native tunnel startup state changed before readiness"),
        );
        assert!(stale_cancel.is_cancelled(), "stale worker must be cancelled");
        assert!(!current_cancel.is_cancelled(), "current startup generation must remain live");
        let snapshot = session.telemetry.snapshot((0, 0, 0, 0), DnsStatsSnapshot::default(), None, None);
        assert_eq!(snapshot.state, "idle");
        assert_eq!(snapshot.active_sessions, 0);
        assert_eq!(snapshot.total_sessions, 0);

        release_sender.send(()).expect("release worker");
        let deadline = Instant::now() + Duration::from_secs(1);
        loop {
            let state = session.state.lock().expect("state lock");
            if matches!(&*state, TunnelSessionState::Starting { cancel } if Arc::ptr_eq(cancel, &current_cancel)) {
                break;
            }
            drop(state);
            assert!(Instant::now() < deadline, "stale startup cleanup corrupted current generation");
            std::thread::yield_now();
        }
    }
}
