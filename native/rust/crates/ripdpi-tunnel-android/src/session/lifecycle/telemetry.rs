use std::sync::Arc;

use ripdpi_tunnel_core::Stats;

use crate::telemetry::TunnelTelemetryState;

pub(crate) fn wire_session_telemetry(stats: &Arc<Stats>, telemetry: &Arc<TunnelTelemetryState>) {
    let dns_histogram = telemetry.dns_histogram.clone();
    stats.set_dns_latency_observer(Arc::new(move |ms| dns_histogram.record(ms)));
}

pub(crate) fn mark_session_started(telemetry: &TunnelTelemetryState, config: &ripdpi_tunnel_config::Config) {
    telemetry.mark_started(format!("{}:{}", config.socks5.address, config.socks5.port));
}
