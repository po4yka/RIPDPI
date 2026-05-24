use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;

use android_support::clear_tunnel_events;
use arc_swap::ArcSwapOption;
use ripdpi_quality::{QualityWindow, TransportKind};
use ripdpi_telemetry::LatencyHistogram;

use crate::config::TunnelLogContext;

static NEXT_TUNNEL_SESSION_ID: AtomicU64 = AtomicU64::new(1);

pub(crate) struct TunnelTelemetryState {
    pub(crate) session_id: String,
    pub(crate) log_scope: String,
    pub(crate) log_context: Option<TunnelLogContext>,
    pub(crate) running: AtomicBool,
    pub(crate) total_sessions: AtomicU64,
    pub(crate) total_errors: AtomicU64,
    pub(crate) upstream_address: ArcSwapOption<String>,
    pub(crate) last_error: ArcSwapOption<String>,
    pub(crate) dns_histogram: LatencyHistogram,
    pub(crate) quality_window: Arc<QualityWindow>,
}

impl TunnelTelemetryState {
    pub(crate) fn new(log_context: Option<TunnelLogContext>) -> Self {
        let ordinal = NEXT_TUNNEL_SESSION_ID.fetch_add(1, Ordering::Relaxed);
        let session_id = format!("tunnel-{ordinal}");
        clear_tunnel_events();
        Self {
            log_scope: format!("tunnel:{session_id}"),
            session_id,
            log_context,
            running: AtomicBool::new(false),
            total_sessions: AtomicU64::new(0),
            total_errors: AtomicU64::new(0),
            upstream_address: ArcSwapOption::empty(),
            last_error: ArcSwapOption::empty(),
            dns_histogram: LatencyHistogram::new(),
            quality_window: Arc::new(QualityWindow::new(TransportKind::TcpTunnel)),
        }
    }

    pub(crate) fn log_scope(&self) -> &str {
        &self.log_scope
    }

    pub(crate) fn mark_started(&self, upstream: String) {
        self.running.store(true, Ordering::Relaxed);
        self.total_sessions.fetch_add(1, Ordering::Relaxed);
        self.upstream_address.store(Some(Arc::new(upstream.clone())));
        self.push_event("tunnel", "info", format!("tunnel started upstream={upstream}"));
    }

    pub(crate) fn mark_stop_requested(&self) {
        self.push_event("tunnel", "info", "tunnel stop requested".to_string());
    }

    pub(crate) fn mark_stopped(&self) {
        self.running.store(false, Ordering::Relaxed);
        self.push_event("tunnel", "info", "tunnel stopped".to_string());
    }

    pub(crate) fn record_error(&self, error: String) {
        self.total_errors.fetch_add(1, Ordering::Relaxed);
        self.last_error.store(Some(Arc::new(error.clone())));
        self.push_event("tunnel", "warn", format!("tunnel error: {error}"));
    }
}
