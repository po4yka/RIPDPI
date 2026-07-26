use ripdpi_telemetry::LatencyDistributions;

use super::state::TunnelTelemetryState;
use super::types::TunnelStatsSnapshot;

pub(super) fn snapshot_latency(state: &TunnelTelemetryState) -> Option<LatencyDistributions> {
    LatencyDistributions { dns_resolution: state.dns_histogram.snapshot(), ..Default::default() }.into_option()
}

pub(super) fn snapshot_tunnel_stats(traffic_stats: (u64, u64, u64, u64)) -> TunnelStatsSnapshot {
    TunnelStatsSnapshot {
        tx_packets: traffic_stats.0,
        tx_bytes: traffic_stats.1,
        rx_packets: traffic_stats.2,
        rx_bytes: traffic_stats.3,
    }
}
