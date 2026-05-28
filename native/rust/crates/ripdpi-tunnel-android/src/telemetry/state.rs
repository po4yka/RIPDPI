use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;

use arc_swap::ArcSwapOption;
use ripdpi_quality::QualityWindow;
use ripdpi_telemetry::LatencyHistogram;

use crate::config::TunnelLogContext;

mod init;

pub(crate) struct TunnelTelemetryState {
    pub(crate) session_id: String,
    pub(crate) log_scope: String,
    pub(crate) log_context: Option<TunnelLogContext>,
    pub(crate) running: AtomicBool,
    pub(crate) total_sessions: AtomicU64,
    pub(crate) total_errors: AtomicU64,
    pub(crate) upstream_address: ArcSwapOption<String>,
    pub(crate) last_error: ArcSwapOption<String>,
    pub(crate) relay_dns_route: ArcSwapOption<String>,
    pub(crate) relay_dns_fail_closed: AtomicBool,
    pub(crate) dns_histogram: LatencyHistogram,
    pub(crate) quality_window: Arc<QualityWindow>,
}

impl TunnelTelemetryState {
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

    pub(crate) fn mark_relay_dns_route(&self, route: &str, fail_closed: bool) {
        self.relay_dns_route.store(Some(Arc::new(route.to_string())));
        self.relay_dns_fail_closed.store(fail_closed, Ordering::Relaxed);
        self.push_event_kind(
            "tunnel",
            "info",
            "relay_dns_route",
            format!("relay DNS route={route} fail_closed={fail_closed}"),
        );
    }
}
