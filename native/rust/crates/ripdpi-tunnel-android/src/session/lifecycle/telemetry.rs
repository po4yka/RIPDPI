use std::sync::Arc;

use ripdpi_quality::QualitySample;
use ripdpi_tunnel_core::{Stats, TcpConnectObservation};

use crate::telemetry::TunnelTelemetryState;

pub(crate) fn wire_session_telemetry(stats: &Arc<Stats>, telemetry: &Arc<TunnelTelemetryState>) {
    let dns_histogram = telemetry.dns_histogram.clone();
    stats.set_dns_latency_observer(Arc::new(move |ms| dns_histogram.record(ms)));

    let quality_window = telemetry.quality_window.clone();
    stats.set_quality_observer(Arc::new(move |obs: TcpConnectObservation| {
        quality_window.record(QualitySample { rtt_ms: obs.rtt_ms, succeeded: obs.succeeded });
    }));
}

pub(crate) fn mark_session_started(telemetry: &TunnelTelemetryState, config: &ripdpi_tunnel_config::Config) {
    telemetry.mark_started(format!("{}:{}", config.socks5.address, config.socks5.port));
}
