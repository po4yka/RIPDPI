use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;

use android_support::clear_tunnel_events;
use arc_swap::ArcSwapOption;
use ripdpi_quality::{QualityWindow, TransportKind};
use ripdpi_telemetry::LatencyHistogram;

use crate::config::TunnelLogContext;

use super::TunnelTelemetryState;

static NEXT_TUNNEL_SESSION_ID: AtomicU64 = AtomicU64::new(1);

impl TunnelTelemetryState {
    pub(crate) fn new(log_context: Option<TunnelLogContext>) -> Self {
        let session_id = format!("tunnel-{}", NEXT_TUNNEL_SESSION_ID.fetch_add(1, Ordering::Relaxed));
        clear_tunnel_events();
        Self {
            log_scope: format!("tunnel:{session_id}"),
            session_id,
            log_context,
            running: AtomicBool::default(),
            total_sessions: AtomicU64::default(),
            total_errors: AtomicU64::default(),
            upstream_address: ArcSwapOption::empty(),
            last_error: ArcSwapOption::empty(),
            relay_dns_route: ArcSwapOption::empty(),
            relay_dns_fail_closed: AtomicBool::default(),
            dns_histogram: LatencyHistogram::new(),
            quality_window: Arc::new(QualityWindow::new(TransportKind::TcpTunnel)),
        }
    }
}
