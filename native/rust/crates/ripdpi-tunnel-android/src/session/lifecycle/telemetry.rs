use std::sync::Arc;

use ripdpi_quality::QualitySample;
use ripdpi_tunnel_core::{Stats, TcpConnectObservation};

use crate::telemetry::TunnelTelemetryState;

pub(crate) fn wire_session_telemetry(stats: &Arc<Stats>, telemetry: &Arc<TunnelTelemetryState>) {
    let dns_histogram = telemetry.dns_histogram.clone();
    stats.set_dns_latency_observer(Arc::new(move |ms| dns_histogram.record(ms)));

    let quality_window = telemetry.quality_window.clone();
    stats.set_quality_observer(Arc::new(move |obs: TcpConnectObservation| {
        quality_window.record(QualitySample { rtt_ms: obs.rtt_ms, succeeded: obs.succeeded, loss_pct: 0.0 });
    }));

    let quality_window_for_loss = telemetry.quality_window.clone();
    stats.set_loss_observer(Arc::new(move |loss_pct| {
        quality_window_for_loss.record_loss(loss_pct);
    }));
}

pub(crate) fn upstream_label(config: &ripdpi_tunnel_config::Config) -> String {
    if config.socks5.unix_path.is_some() {
        "unix".to_string()
    } else {
        format!("{}:{}", config.socks5.address, config.socks5.port)
    }
}

pub(crate) fn mark_session_started(telemetry: &TunnelTelemetryState, config: &ripdpi_tunnel_config::Config) {
    telemetry.mark_started(upstream_label(config));
    if config.mapdns.as_ref().is_some_and(|mapdns| mapdns.route_dns_through_socks5) {
        telemetry.mark_relay_dns_route("socks5", true);
    }
}

#[cfg(test)]
mod tests {
    use std::str::FromStr;

    use super::upstream_label;

    #[test]
    fn upstream_label_names_the_unix_form_without_its_path() {
        let tcp =
            ripdpi_tunnel_config::Config::from_str("schemaVersion: 2\nsocks5:\n  port: 1080\n  address: 127.0.0.1\n")
                .expect("tcp config");
        let unix = ripdpi_tunnel_config::Config::from_str("schemaVersion: 2\nsocks5:\n  unix-path: /data/px-0123\n")
            .expect("unix config");

        assert_eq!(upstream_label(&tcp), "127.0.0.1:1080");
        assert_eq!(upstream_label(&unix), "unix");
    }
}
