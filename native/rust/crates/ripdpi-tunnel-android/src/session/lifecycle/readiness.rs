use std::sync::Arc;
use std::sync::mpsc::{Receiver, RecvTimeoutError};
use std::time::Duration;

use tokio_util::sync::CancellationToken;

use super::super::registry::{TunnelSession, TunnelSessionState};

pub(super) fn wait_for_native_readiness(readiness: &Receiver<()>, timeout: Duration) -> Result<(), RecvTimeoutError> {
    readiness.recv_timeout(timeout)
}

pub(super) fn defer_failed_tunnel_start_cleanup(
    session: &Arc<TunnelSession>,
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

#[cfg(test)]
mod tests {
    use std::sync::{Arc, Mutex, mpsc};
    use std::time::{Duration, Instant};

    use tokio_util::sync::CancellationToken;

    use crate::config::{config_from_payload, sample_payload};
    use crate::telemetry::TunnelTelemetryState;

    use super::super::super::registry::{TunnelSession, TunnelSessionState};
    use super::super::state::ensure_tunnel_destroyable;
    use super::{defer_failed_tunnel_start_cleanup, wait_for_native_readiness};

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
