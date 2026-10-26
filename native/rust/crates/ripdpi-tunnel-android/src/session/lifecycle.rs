use android_support::{throw_illegal_argument_env, throw_illegal_state_env};
use jni::Env;
use jni::sys::jlong;

use std::sync::Arc;
use std::time::{Duration, Instant};

use tokio_util::sync::CancellationToken;

use super::pcap;
use super::registry::{TunnelSession, TunnelSessionState, lookup_tunnel_session, remove_tunnel_session};

mod create;
mod fd;
mod readiness;
mod start;
mod state;
mod telemetry;
mod validation;
mod worker;

pub(crate) use create::create_session;
pub(crate) use readiness::rollback_failed_tunnel_start;
pub(crate) use start::start_session;
pub(crate) use state::{ensure_tunnel_destroyable, ensure_tunnel_start_allowed, take_running_tunnel};
pub(crate) use validation::validate_tun_fd;

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
            Ok(running) => {
                // Unstartable until the worker has released its TUN fd duplicate.
                *state = TunnelSessionState::CleanupPending { cancel: Arc::clone(&running.0) };
                running
            }
            Err(message) => {
                throw_illegal_state_env(env, message);
                return;
            }
        }
    };

    pcap::pcap_retire_entry(handle);
    session.telemetry.mark_stop_requested();
    stop_running_tunnel(&session, running, STOP_JOIN_TIMEOUT);
}

/// How long jniStop waits for the worker before handing the join to a background reaper.
/// It stays well below the Kotlin service destroy timeout, which cannot interrupt a JNI call.
const STOP_JOIN_TIMEOUT: Duration = Duration::from_secs(5);

/// Cancels the worker and joins it for at most `timeout`. A worker blocked in a syscall
/// keeps the session `CleanupPending` and is joined by a background reaper, so the JNI
/// stop call never blocks without bound.
fn stop_running_tunnel(
    session: &Arc<TunnelSession>,
    (cancel, worker): (Arc<CancellationToken>, std::thread::JoinHandle<()>),
    timeout: Duration,
) {
    cancel.cancel();
    let deadline = Instant::now() + timeout;
    while !worker.is_finished() && Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(10));
    }
    if worker.is_finished() {
        finish_stopped_worker(session, &cancel, worker);
        return;
    }
    session.telemetry.log_line("worker", "warn", "tunnel worker still stopping; cleanup continues in background");
    spawn_worker_reaper(session, move |session| finish_stopped_worker(session, &cancel, worker));
}

/// Runs `reap` (a join of a stopped worker) on its own plain thread, never on the session
/// runtime's blocking pool: after jniDestroy the reaper can hold the last session reference,
/// and a tokio runtime dropped on one of its own blocking threads waits for ever for that
/// thread to exit. If the thread cannot start, the worker is detached: it still closes its
/// TUN fd, and the session stays `CleanupPending`, which can be destroyed but not started.
fn spawn_worker_reaper(session: &Arc<TunnelSession>, reap: impl FnOnce(&TunnelSession) + Send + 'static) {
    let reaper_session = Arc::clone(session);
    let spawned = std::thread::Builder::new().name("ripdpi-tunnel-reaper".into()).spawn(move || reap(&reaper_session));
    if let Err(err) = spawned {
        session.telemetry.log_line("worker", "error", &format!("tunnel worker reaper did not start: {err}"));
    }
}

fn finish_stopped_worker(
    session: &TunnelSession,
    cancel: &Arc<CancellationToken>,
    worker: std::thread::JoinHandle<()>,
) {
    if worker.join().is_err() {
        session.telemetry.log_line("worker", "error", "tunnel worker panicked during shutdown");
    }
    let mut state = session.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    if matches!(&*state, TunnelSessionState::CleanupPending { cancel: pending } if Arc::ptr_eq(pending, cancel)) {
        *state = TunnelSessionState::Ready;
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
    use std::sync::{Mutex, mpsc};

    use super::*;
    use crate::config::{config_from_payload, sample_payload};
    use crate::telemetry::TunnelTelemetryState;

    #[test]
    fn stop_returns_within_its_bound_while_a_worker_is_stuck() {
        let runtime = Arc::new(
            tokio::runtime::Builder::new_multi_thread().worker_threads(1).enable_all().build().expect("test runtime"),
        );
        let cancel = Arc::new(CancellationToken::new());
        let session = Arc::new(TunnelSession {
            runtime,
            config: Arc::new(config_from_payload(sample_payload()).expect("config")),
            last_error: Arc::new(Mutex::new(None)),
            telemetry: Arc::new(TunnelTelemetryState::new(None)),
            state: Mutex::new(TunnelSessionState::CleanupPending { cancel: Arc::clone(&cancel) }),
        });
        // A worker blocked in a syscall that ignores cancellation.
        let (release, stuck) = mpsc::sync_channel::<()>(1);
        let worker = std::thread::spawn(move || {
            let _ = stuck.recv();
        });

        let started = Instant::now();
        stop_running_tunnel(&session, (Arc::clone(&cancel), worker), Duration::from_millis(50));

        assert!(started.elapsed() < Duration::from_secs(2), "stop blocked for {:?}", started.elapsed());
        assert!(cancel.is_cancelled());
        assert!(matches!(*session.state.lock().expect("state"), TunnelSessionState::CleanupPending { .. }));
        release.send(()).expect("release worker");
        let deadline = Instant::now() + Duration::from_secs(2);
        while !matches!(*session.state.lock().expect("state"), TunnelSessionState::Ready) {
            assert!(Instant::now() < deadline, "the reaper never retired the stopped worker");
            std::thread::sleep(Duration::from_millis(5));
        }
    }

    #[test]
    fn reaper_that_holds_the_last_session_reference_releases_it() {
        let runtime = Arc::new(
            tokio::runtime::Builder::new_multi_thread().worker_threads(1).enable_all().build().expect("test runtime"),
        );
        let cancel = Arc::new(CancellationToken::new());
        let session = Arc::new(TunnelSession {
            runtime,
            config: Arc::new(config_from_payload(sample_payload()).expect("config")),
            last_error: Arc::new(Mutex::new(None)),
            telemetry: Arc::new(TunnelTelemetryState::new(None)),
            state: Mutex::new(TunnelSessionState::CleanupPending { cancel: Arc::clone(&cancel) }),
        });
        let (release, stuck) = mpsc::sync_channel::<()>(1);
        let worker = std::thread::spawn(move || {
            let _ = stuck.recv();
        });
        stop_running_tunnel(&session, (Arc::clone(&cancel), worker), Duration::from_millis(50));
        // TunnelSession drops its runtime before its telemetry, so the telemetry outlives a
        // runtime drop that never returns.
        let telemetry = Arc::downgrade(&session.telemetry);
        // jniDestroy: the registry drops its reference, so the reaper now holds the last one.
        drop(session);

        release.send(()).expect("release worker");
        let deadline = Instant::now() + Duration::from_secs(5);
        while telemetry.upgrade().is_some() {
            assert!(Instant::now() < deadline, "the reaper hung while it dropped the session runtime");
            std::thread::sleep(Duration::from_millis(5));
        }
    }
}
