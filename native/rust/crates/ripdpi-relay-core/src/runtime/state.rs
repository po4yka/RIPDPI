use std::io;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

use arc_swap::ArcSwapOption;
use tokio_util::sync::CancellationToken;
use tokio_util::task::TaskTracker;

use crate::backend::RelayBackend;
use crate::telemetry::TcpConnectObservation;
use ripdpi_failure_classifier::{
    ConfirmGoodDpiAccumulator, ConfirmGoodDpiEvidence, ConfirmGoodFlowObservation, ConfirmGoodFlowSource,
    ConfirmGoodTerminalReason,
};

pub(super) struct RuntimeState {
    stop_requested: AtomicBool,
    running: AtomicBool,
    active_sessions: AtomicU64,
    total_sessions: AtomicU64,
    backend: OnceLock<Arc<RelayBackend>>,
    listener_address: OnceLock<String>,
    last_target: ArcSwapOption<String>,
    last_error: ArcSwapOption<String>,
    last_handshake_error: ArcSwapOption<String>,
    quality_observer: Mutex<Option<Arc<dyn Fn(TcpConnectObservation) + Send + Sync>>>,
    readiness_observer: Mutex<Option<Arc<dyn Fn() + Send + Sync>>>,
    confirm_good_dpi: Mutex<ConfirmGoodDpiAccumulator>,
    /// Parent cancellation token. `request_stop` cancels it so every in-flight
    /// SOCKS5 session — racing its `child_token().cancelled()` future against
    /// the session work — wakes promptly and unwinds instead of leaking its
    /// upstream connection and fds until the process exits.
    shutdown_token: CancellationToken,
    /// Tracks every spawned session task so [`RuntimeState::drain_sessions`]
    /// can join them within a bounded grace window on shutdown.
    session_tracker: TaskTracker,
}

impl RuntimeState {
    pub(super) fn new() -> Self {
        Self {
            stop_requested: AtomicBool::new(false),
            running: AtomicBool::new(false),
            active_sessions: AtomicU64::new(0),
            total_sessions: AtomicU64::new(0),
            backend: OnceLock::new(),
            listener_address: OnceLock::new(),
            last_target: ArcSwapOption::empty(),
            last_error: ArcSwapOption::empty(),
            last_handshake_error: ArcSwapOption::empty(),
            quality_observer: Mutex::new(None),
            readiness_observer: Mutex::new(None),
            confirm_good_dpi: Mutex::new(ConfirmGoodDpiAccumulator::default()),
            shutdown_token: CancellationToken::new(),
            session_tracker: TaskTracker::new(),
        }
    }

    pub(super) fn request_stop(&self) {
        self.stop_requested.store(true, Ordering::SeqCst);
        // Wake every in-flight session so it unwinds instead of leaking its
        // upstream connection until the runtime is dropped.
        self.shutdown_token.cancel();
    }

    pub(super) fn stop_requested(&self) -> bool {
        self.stop_requested.load(Ordering::SeqCst)
    }

    /// A child of the runtime-level shutdown token, handed to each spawned
    /// session so it observes `request_stop` and cancels its work.
    pub(super) fn session_cancel_token(&self) -> CancellationToken {
        self.shutdown_token.child_token()
    }

    /// A cheap (`Arc`-backed) clone of the session tracker, used by
    /// `spawn_socks_session` to register each session task. Cloning before the
    /// spawn avoids moving `&self`'s owner into the spawned future.
    pub(super) fn clone_tracker(&self) -> TaskTracker {
        self.session_tracker.clone()
    }

    /// Close the tracker (so no new tasks register) and join all in-flight
    /// sessions, bounded by `grace`. Returns `true` when every session drained
    /// within the window, `false` if the timeout elapsed first. Idempotent:
    /// re-closing an already-closed tracker is a no-op.
    ///
    /// cancel-safe: the only `.await` is `timeout(grace, wait())`; cancelling
    /// it merely abandons the wait — the tracker and its tasks are unaffected.
    pub(super) async fn drain_sessions(&self, grace: std::time::Duration) -> bool {
        self.session_tracker.close();
        tokio::time::timeout(grace, self.session_tracker.wait()).await.is_ok()
    }

    pub(super) fn set_running(&self, running: bool) {
        self.running.store(running, Ordering::SeqCst);
    }

    pub(super) fn is_running(&self) -> bool {
        self.running.load(Ordering::SeqCst)
    }

    pub(super) fn start_session(&self) {
        self.active_sessions.fetch_add(1, Ordering::SeqCst);
        self.total_sessions.fetch_add(1, Ordering::SeqCst);
    }

    pub(super) fn finish_session(&self) {
        self.active_sessions.fetch_sub(1, Ordering::SeqCst);
    }

    pub(super) fn active_sessions(&self) -> u64 {
        self.active_sessions.load(Ordering::SeqCst)
    }

    pub(super) fn total_sessions(&self) -> u64 {
        self.total_sessions.load(Ordering::SeqCst)
    }

    pub(super) fn set_backend(&self, backend: Arc<RelayBackend>) -> io::Result<()> {
        self.backend
            .set(backend)
            .map_err(|_| io::Error::new(io::ErrorKind::AlreadyExists, "relay backend was already initialized"))
    }

    pub(super) fn backend(&self) -> Option<&Arc<RelayBackend>> {
        self.backend.get()
    }

    pub(super) fn set_listener_address(&self, listener_address: String) -> io::Result<()> {
        self.listener_address
            .set(listener_address)
            .map_err(|_| io::Error::new(io::ErrorKind::AlreadyExists, "relay listener address was already initialized"))
    }

    pub(super) fn listener_address(&self) -> Option<String> {
        self.listener_address.get().cloned()
    }

    pub(super) fn record_target(&self, target: String) {
        self.last_target.store(Some(Arc::new(target)));
    }

    pub(super) fn record_error(&self, error: String) {
        self.last_error.store(Some(Arc::new(error)));
    }

    pub(super) fn record_handshake_error(&self, error: String) {
        self.last_handshake_error.store(Some(Arc::new(error)));
    }

    pub(super) fn last_target(&self) -> Option<String> {
        load_optional_string(&self.last_target)
    }

    pub(super) fn last_error(&self) -> Option<String> {
        load_optional_string(&self.last_error)
    }

    pub(super) fn last_handshake_error(&self) -> Option<String> {
        load_optional_string(&self.last_handshake_error)
    }

    /// Install a quality observer callback invoked for every upstream TCP
    /// connect attempt. Replaces any previously installed observer.
    ///
    /// Cancel-safety: synchronous lock; no `.await` inside.
    pub(super) fn set_quality_observer(&self, observer: Arc<dyn Fn(TcpConnectObservation) + Send + Sync>) {
        if let Ok(mut guard) = self.quality_observer.lock() {
            *guard = Some(observer);
        }
    }

    /// Fire the quality observer with `obs`. Clone the `Arc` inside the lock,
    /// release the lock, then invoke — reentrancy-safe (mirrors tunnel-core
    /// observer pattern from `stats/observer.rs`).
    ///
    /// Cancel-safety: synchronous; no `.await` inside.
    pub(super) fn emit_connect_observation(&self, obs: TcpConnectObservation) {
        let observer = match self.quality_observer.lock() {
            Ok(guard) => guard.as_ref().map(Arc::clone),
            Err(_) => None,
        };
        if let Some(observer) = observer {
            observer(obs);
        }
    }

    pub(super) fn record_confirm_good_passive_stall(
        &self,
        target: &str,
        application_bytes_sent: u64,
        application_response_bytes: u64,
        profile_catalog_validated: bool,
    ) {
        let now_ms = crate::telemetry::now_ms();
        if let Ok(mut accumulator) = self.confirm_good_dpi.lock() {
            accumulator.record(
                ConfirmGoodFlowObservation {
                    network_scope: "relay-runtime".to_string(),
                    target_digest: ripdpi_xhttp::reality_target_digest(target),
                    observed_at_ms: now_ms,
                    source: ConfirmGoodFlowSource::Passive,
                    classic_vless_reality: true,
                    profile_catalog_validated,
                    reality_handshake_completed: true,
                    application_bytes_sent,
                    application_response_bytes,
                    terminal_reason: ConfirmGoodTerminalReason::PassiveTimeout,
                },
                now_ms,
            );
        }
    }

    pub(super) fn confirm_good_dpi_evidence(&self) -> Option<ConfirmGoodDpiEvidence> {
        self.confirm_good_dpi
            .lock()
            .ok()
            .and_then(|mut accumulator| accumulator.candidate_evidence(crate::telemetry::now_ms()))
    }

    /// Install a readiness observer fired exactly once when the runtime has
    /// bound its listener and is about to serve. Replaces any previously
    /// installed observer. The adapter layer wires this to a native readiness
    /// push (see ADR 0003); the relay core itself stays platform-agnostic.
    ///
    /// Cancel-safety: synchronous lock; no `.await` inside.
    pub(super) fn set_readiness_observer(&self, observer: Arc<dyn Fn() + Send + Sync>) {
        if let Ok(mut guard) = self.readiness_observer.lock() {
            *guard = Some(observer);
        }
    }

    /// Fire the readiness observer, if installed. Clone the `Arc` inside the
    /// lock, release the lock, then invoke — reentrancy-safe, mirroring
    /// `emit_connect_observation`.
    ///
    /// Cancel-safety: synchronous; no `.await` inside.
    pub(super) fn notify_ready(&self) {
        let observer = match self.readiness_observer.lock() {
            Ok(guard) => guard.as_ref().map(Arc::clone),
            Err(_) => None,
        };
        if let Some(observer) = observer {
            observer();
        }
    }
}

fn load_optional_string(slot: &ArcSwapOption<String>) -> Option<String> {
    slot.load_full().as_deref().cloned()
}

#[cfg(test)]
mod tests {
    use std::sync::atomic::{AtomicU64, Ordering};

    use super::*;

    #[test]
    fn target_digest_never_exposes_the_target() {
        let target = "private.example:443";
        let digest = ripdpi_xhttp::reality_target_digest(target);

        assert_eq!(digest.len(), 64);
        assert!(!digest.contains(target));
        assert_eq!(digest, ripdpi_xhttp::reality_target_digest(target));
    }

    /// Smoke: observer is invoked when `emit_connect_observation` is called.
    #[test]
    fn observer_fires_on_emit() {
        let state = RuntimeState::new();
        let count = Arc::new(AtomicU64::new(0));
        let count_clone = Arc::clone(&count);
        state.set_quality_observer(Arc::new(move |_obs| {
            count_clone.fetch_add(1, Ordering::Relaxed);
        }));
        state.emit_connect_observation(TcpConnectObservation { rtt_ms: 10, succeeded: true });
        assert_eq!(count.load(Ordering::Relaxed), 1);
    }

    /// Observer receives the exact observation values that were passed.
    #[test]
    fn observer_receives_correct_values() {
        let state = RuntimeState::new();
        let recorded_rtt = Arc::new(AtomicU64::new(u64::MAX));
        let recorded_succeeded = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let rtt_clone = Arc::clone(&recorded_rtt);
        let ok_clone = Arc::clone(&recorded_succeeded);
        state.set_quality_observer(Arc::new(move |obs| {
            rtt_clone.store(obs.rtt_ms, Ordering::Relaxed);
            ok_clone.store(obs.succeeded, Ordering::Relaxed);
        }));
        state.emit_connect_observation(TcpConnectObservation { rtt_ms: 42, succeeded: true });
        assert_eq!(recorded_rtt.load(Ordering::Relaxed), 42);
        assert!(recorded_succeeded.load(Ordering::Relaxed));
    }

    /// A failure observation sets `succeeded = false`.
    #[test]
    fn observer_receives_failure_observation() {
        let state = RuntimeState::new();
        let recorded_succeeded = Arc::new(std::sync::atomic::AtomicBool::new(true));
        let ok_clone = Arc::clone(&recorded_succeeded);
        state.set_quality_observer(Arc::new(move |obs| {
            ok_clone.store(obs.succeeded, Ordering::Relaxed);
        }));
        state.emit_connect_observation(TcpConnectObservation { rtt_ms: 0, succeeded: false });
        assert!(!recorded_succeeded.load(Ordering::Relaxed));
    }

    /// No observer installed: `emit_connect_observation` is a no-op (no panic).
    #[test]
    fn no_observer_emit_is_noop() {
        let state = RuntimeState::new();
        // Must not panic.
        state.emit_connect_observation(TcpConnectObservation { rtt_ms: 5, succeeded: true });
    }

    /// `set_quality_observer` replaces the previous observer.
    #[test]
    fn set_quality_observer_replaces_previous() {
        let state = RuntimeState::new();
        let first_count = Arc::new(AtomicU64::new(0));
        let second_count = Arc::new(AtomicU64::new(0));

        let first_clone = Arc::clone(&first_count);
        state.set_quality_observer(Arc::new(move |_| {
            first_clone.fetch_add(1, Ordering::Relaxed);
        }));

        let second_clone = Arc::clone(&second_count);
        state.set_quality_observer(Arc::new(move |_| {
            second_clone.fetch_add(1, Ordering::Relaxed);
        }));

        state.emit_connect_observation(TcpConnectObservation { rtt_ms: 1, succeeded: true });

        assert_eq!(first_count.load(Ordering::Relaxed), 0, "first observer must not fire after replacement");
        assert_eq!(second_count.load(Ordering::Relaxed), 1, "second observer must fire");
    }

    /// Smoke: readiness observer is invoked when `notify_ready` is called.
    #[test]
    fn readiness_observer_fires_on_notify() {
        let state = RuntimeState::new();
        let count = Arc::new(AtomicU64::new(0));
        let count_clone = Arc::clone(&count);
        state.set_readiness_observer(Arc::new(move || {
            count_clone.fetch_add(1, Ordering::Relaxed);
        }));
        state.notify_ready();
        assert_eq!(count.load(Ordering::Relaxed), 1);
    }

    /// No readiness observer installed: `notify_ready` is a no-op (no panic).
    #[test]
    fn no_readiness_observer_notify_is_noop() {
        let state = RuntimeState::new();
        // Must not panic.
        state.notify_ready();
    }

    /// `set_readiness_observer` replaces the previous observer.
    #[test]
    fn set_readiness_observer_replaces_previous() {
        let state = RuntimeState::new();
        let first_count = Arc::new(AtomicU64::new(0));
        let second_count = Arc::new(AtomicU64::new(0));

        let first_clone = Arc::clone(&first_count);
        state.set_readiness_observer(Arc::new(move || {
            first_clone.fetch_add(1, Ordering::Relaxed);
        }));

        let second_clone = Arc::clone(&second_count);
        state.set_readiness_observer(Arc::new(move || {
            second_clone.fetch_add(1, Ordering::Relaxed);
        }));

        state.notify_ready();

        assert_eq!(first_count.load(Ordering::Relaxed), 0, "first readiness observer must not fire after replacement");
        assert_eq!(second_count.load(Ordering::Relaxed), 1, "second readiness observer must fire");
    }

    /// Reentrancy: observer calls `set_quality_observer` on a separate state
    /// instance without deadlock (mirrors tunnel-core reentrancy test shape).
    #[test]
    fn observer_reentrancy_does_not_deadlock() {
        let state = Arc::new(RuntimeState::new());
        let state_inner = Arc::clone(&state);
        let replacement_count = Arc::new(AtomicU64::new(0));
        let replacement_count_clone = Arc::clone(&replacement_count);

        let first: Arc<dyn Fn(TcpConnectObservation) + Send + Sync> = Arc::new(move |_obs| {
            // Replace observer on a *separate* instance to confirm reentrancy
            // path does not deadlock on the same Mutex.
            let count = Arc::clone(&replacement_count_clone);
            state_inner.set_quality_observer(Arc::new(move |_| {
                count.fetch_add(1, Ordering::Relaxed);
            }));
        });
        state.set_quality_observer(first);
        state.emit_connect_observation(TcpConnectObservation { rtt_ms: 0, succeeded: true });
        // Second emit hits the replacement observer.
        state.emit_connect_observation(TcpConnectObservation { rtt_ms: 0, succeeded: true });
        assert_eq!(replacement_count.load(Ordering::Relaxed), 1);
    }
}
