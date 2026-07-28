use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use android_support::clear_proxy_events;
use arc_swap::ArcSwap;
use ripdpi_proxy_config::ProxyLogContext;
use ripdpi_telemetry::LatencyHistogram;

use super::types::DirectPathLearningSignal;

static NEXT_PROXY_SESSION_ID: AtomicU64 = AtomicU64::new(1);

#[derive(Clone)]
pub(super) struct TelemetryStrings {
    pub(super) listener_address: Option<String>,
    pub(super) upstream_address: Option<String>,
    pub(super) upstream_rtt_ms: Option<u64>,
    pub(super) last_target: Option<String>,
    pub(super) last_host: Option<String>,
    pub(super) last_error: Option<String>,
    pub(super) last_failure_class: Option<String>,
    pub(super) last_fallback_action: Option<String>,
    pub(super) adaptive_trigger_mask: Option<u64>,
    pub(super) adaptive_last_trigger: Option<String>,
    pub(super) adaptive_override_reason: Option<String>,
    pub(super) morph_hint_family: Option<String>,
    pub(super) morph_rollback_reason: Option<String>,
    pub(super) quic_migration_status: Option<String>,
    pub(super) quic_migration_reason: Option<String>,
    pub(super) last_retry_reason: Option<String>,
    pub(super) last_autolearn_host: Option<String>,
    pub(super) last_autolearn_action: Option<String>,
    pub(super) last_block_signal: Option<String>,
    pub(super) last_block_provider: Option<String>,
}

pub struct ProxyTelemetryState {
    pub(super) session_id: String,
    pub(super) log_scope: String,
    pub(super) log_context: Option<ProxyLogContext>,
    pub(super) running: AtomicBool,
    pub(super) active_sessions: AtomicU64,
    pub(super) total_sessions: AtomicU64,
    pub(super) total_errors: AtomicU64,
    pub(super) network_errors: AtomicU64,
    pub(super) route_changes: AtomicU64,
    pub(super) retry_paced_count: AtomicU64,
    pub(super) last_retry_backoff_ms: AtomicU64,
    pub(super) candidate_diversification_count: AtomicU64,
    pub(super) last_route_group: AtomicI64,
    pub(super) adaptive_override_active: AtomicBool,
    pub(super) autolearn_enabled: AtomicBool,
    pub(super) learned_host_count: AtomicU64,
    pub(super) penalized_host_count: AtomicU64,
    pub(super) blocked_host_count: AtomicU64,
    pub(super) last_autolearn_group: AtomicI64,
    pub(super) slot_exhaustions: AtomicU64,
    pub(super) ws_tunnel_fake_sni_active: AtomicU64,
    pub(super) upstream_socket_created: AtomicU64,
    pub(super) upstream_opened: AtomicU64,
    pub(super) upstream_open_failures: AtomicU64,
    pub(super) protect_attempted: AtomicU64,
    pub(super) protect_succeeded: AtomicU64,
    pub(super) protect_rejected: AtomicU64,
    pub(super) protect_errors: AtomicU64,
    pub(super) upstream_application_bytes: AtomicU64,
    pub(super) first_upstream_application_forwarded_at: AtomicU64,
    pub(super) last_upstream_application_forwarded_at: AtomicU64,
    pub(super) strings: ArcSwap<TelemetryStrings>,
    pub(super) direct_path_learning_signals: Mutex<Vec<DirectPathLearningSignal>>,
    pub(super) tcp_connect_histogram: LatencyHistogram,
    pub(super) tls_handshake_histogram: LatencyHistogram,
    readiness_observer: Mutex<Option<Arc<dyn Fn() + Send + Sync>>>,
}

impl ProxyTelemetryState {
    pub fn new(log_context: Option<ProxyLogContext>) -> Self {
        // Ordering: Relaxed -- session ID is a monotonic counter used only for logging; no
        // synchronisation with other threads is needed beyond uniqueness.
        let ordinal = NEXT_PROXY_SESSION_ID.fetch_add(1, Ordering::Relaxed);
        let session_id = format!("proxy-{ordinal}");
        clear_proxy_events();
        Self {
            log_scope: format!("proxy:{session_id}"),
            session_id,
            log_context,
            running: AtomicBool::new(false),
            active_sessions: AtomicU64::new(0),
            total_sessions: AtomicU64::new(0),
            total_errors: AtomicU64::new(0),
            network_errors: AtomicU64::new(0),
            route_changes: AtomicU64::new(0),
            retry_paced_count: AtomicU64::new(0),
            last_retry_backoff_ms: AtomicU64::new(0),
            candidate_diversification_count: AtomicU64::new(0),
            last_route_group: AtomicI64::new(-1),
            adaptive_override_active: AtomicBool::new(false),
            autolearn_enabled: AtomicBool::new(false),
            learned_host_count: AtomicU64::new(0),
            penalized_host_count: AtomicU64::new(0),
            blocked_host_count: AtomicU64::new(0),
            last_autolearn_group: AtomicI64::new(-1),
            slot_exhaustions: AtomicU64::new(0),
            ws_tunnel_fake_sni_active: AtomicU64::new(0),
            upstream_socket_created: AtomicU64::new(0),
            upstream_opened: AtomicU64::new(0),
            upstream_open_failures: AtomicU64::new(0),
            protect_attempted: AtomicU64::new(0),
            protect_succeeded: AtomicU64::new(0),
            protect_rejected: AtomicU64::new(0),
            protect_errors: AtomicU64::new(0),
            upstream_application_bytes: AtomicU64::new(0),
            first_upstream_application_forwarded_at: AtomicU64::new(0),
            last_upstream_application_forwarded_at: AtomicU64::new(0),
            direct_path_learning_signals: Mutex::new(Vec::new()),
            strings: ArcSwap::from_pointee(TelemetryStrings {
                listener_address: None,
                upstream_address: None,
                upstream_rtt_ms: None,
                last_target: None,
                last_host: None,
                last_error: None,
                last_failure_class: None,
                last_fallback_action: None,
                adaptive_trigger_mask: None,
                adaptive_last_trigger: None,
                adaptive_override_reason: None,
                morph_hint_family: None,
                morph_rollback_reason: None,
                quic_migration_status: None,
                quic_migration_reason: None,
                last_retry_reason: None,
                last_autolearn_host: None,
                last_autolearn_action: None,
                last_block_signal: None,
                last_block_provider: None,
            }),
            tcp_connect_histogram: LatencyHistogram::new(),
            tls_handshake_histogram: LatencyHistogram::new(),
            readiness_observer: Mutex::new(None),
        }
    }

    pub fn log_scope(&self) -> &str {
        &self.log_scope
    }

    pub(super) fn update_strings<F: Fn(&mut TelemetryStrings)>(&self, f: F) {
        self.strings.rcu(|current| {
            let mut next = (**current).clone();
            f(&mut next);
            next
        });
    }

    pub(super) fn record_upstream_application_forward(&self, bytes: u64, epoch_ms: u64) {
        if bytes == 0 {
            return;
        }
        let epoch_ms = epoch_ms.max(1);
        self.record_first_upstream_application_forwarded_at(epoch_ms);
        // Ordering: AcqRel -- maintains the maximum observed timestamp across
        // concurrent writers before the byte counter publishes the observation.
        self.last_upstream_application_forwarded_at.fetch_max(epoch_ms, Ordering::AcqRel);
        // Ordering: Release -- publishes timestamp updates before byte count.
        // Snapshot uses Acquire so bytes > 0 implies timestamps are visible.
        self.upstream_application_bytes.fetch_add(bytes, Ordering::Release);
    }

    fn record_first_upstream_application_forwarded_at(&self, epoch_ms: u64) {
        let mut current = self.first_upstream_application_forwarded_at.load(Ordering::Acquire);
        while current == 0 || epoch_ms < current {
            match self.first_upstream_application_forwarded_at.compare_exchange_weak(
                current,
                epoch_ms,
                // Ordering: AcqRel -- publishes the minimum timestamp before byte publication.
                Ordering::AcqRel,
                // Ordering: Acquire -- observes competing timestamp updates.
                Ordering::Acquire,
            ) {
                Ok(_) => break,
                Err(observed) => current = observed,
            }
        }
    }

    /// Install a readiness observer fired exactly once from
    /// [`ProxyTelemetryState::mark_running`], at the same point the
    /// `runtime_ready` event is emitted. Replaces any previously installed
    /// observer. The adapter layer wires this to a native readiness push so
    /// the Kotlin wrapper no longer polls telemetry (see ADR 0003).
    ///
    /// Cancel-safety: synchronous lock; no `.await` inside.
    pub fn set_readiness_observer(&self, observer: Arc<dyn Fn() + Send + Sync>) {
        if let Ok(mut guard) = self.readiness_observer.lock() {
            *guard = Some(observer);
        }
    }

    /// Fire the readiness observer, if installed. Clone the `Arc` inside the
    /// lock, release the lock, then invoke — reentrancy-safe.
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

#[cfg(test)]
mod tests {
    use std::sync::Arc;
    use std::sync::atomic::{AtomicU64, Ordering};

    use super::ProxyTelemetryState;

    /// Smoke: readiness observer is invoked when `notify_ready` is called.
    #[test]
    fn readiness_observer_fires_on_notify() {
        let state = ProxyTelemetryState::new(None);
        let count = Arc::new(AtomicU64::new(0));
        let count_clone = Arc::clone(&count);
        state.set_readiness_observer(Arc::new(move || {
            count_clone.fetch_add(1, Ordering::Relaxed);
        }));
        state.notify_ready();
        assert_eq!(count.load(Ordering::Relaxed), 1);
    }

    /// `mark_running` fires the readiness observer exactly once.
    #[test]
    fn mark_running_fires_readiness_observer_once() {
        let state = ProxyTelemetryState::new(None);
        let count = Arc::new(AtomicU64::new(0));
        let count_clone = Arc::clone(&count);
        state.set_readiness_observer(Arc::new(move || {
            count_clone.fetch_add(1, Ordering::Relaxed);
        }));
        state.mark_running("127.0.0.1:1080".to_string(), 64, 1);
        assert_eq!(count.load(Ordering::Relaxed), 1);
    }

    /// No readiness observer installed: `mark_running`/`notify_ready` is a
    /// no-op (no panic).
    #[test]
    fn no_readiness_observer_is_noop() {
        let state = ProxyTelemetryState::new(None);
        state.notify_ready();
        // Must not panic without an observer installed.
        state.mark_running("127.0.0.1:1080".to_string(), 64, 1);
    }

    /// `set_readiness_observer` replaces the previous observer.
    #[test]
    fn set_readiness_observer_replaces_previous() {
        let state = ProxyTelemetryState::new(None);
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
}
