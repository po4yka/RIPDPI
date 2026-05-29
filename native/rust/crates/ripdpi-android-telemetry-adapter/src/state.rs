use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering};
use std::sync::Mutex;

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
    pub(super) strings: ArcSwap<TelemetryStrings>,
    pub(super) direct_path_learning_signals: Mutex<Vec<DirectPathLearningSignal>>,
    pub(super) tcp_connect_histogram: LatencyHistogram,
    pub(super) tls_handshake_histogram: LatencyHistogram,
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
}
