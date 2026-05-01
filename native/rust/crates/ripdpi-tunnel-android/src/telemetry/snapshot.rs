use std::sync::atomic::Ordering;

use android_support::drain_tunnel_events;
use ripdpi_telemetry::LatencyDistributions;
use ripdpi_tunnel_core::DnsStatsSnapshot;

use super::state::TunnelTelemetryState;
use super::types::{NativeRuntimeEvent, NativeRuntimeSnapshot, TunnelStatsSnapshot};

impl TunnelTelemetryState {
    pub(crate) fn snapshot(
        &self,
        traffic_stats: (u64, u64, u64, u64),
        dns_stats: DnsStatsSnapshot,
        resolver_id: Option<String>,
        resolver_protocol: Option<String>,
    ) -> NativeRuntimeSnapshot {
        NativeRuntimeSnapshot {
            source: "tunnel".to_string(),
            state: if self.running.load(Ordering::Relaxed) { "running".to_string() } else { "idle".to_string() },
            health: if self.running.load(Ordering::Relaxed) {
                if self.total_errors.load(Ordering::Relaxed) == 0 {
                    "healthy".to_string()
                } else {
                    "degraded".to_string()
                }
            } else {
                "idle".to_string()
            },
            active_sessions: u64::from(self.running.load(Ordering::Relaxed)),
            total_sessions: self.total_sessions.load(Ordering::Relaxed),
            total_errors: self.total_errors.load(Ordering::Relaxed),
            route_changes: 0,
            last_route_group: None,
            listener_address: None,
            upstream_address: self.upstream_address.load().as_ref().map(|a| (**a).clone()),
            resolver_id,
            resolver_protocol,
            resolver_endpoint: dns_stats.resolver_endpoint,
            resolver_latency_ms: dns_stats.resolver_latency_ms,
            resolver_latency_avg_ms: dns_stats.resolver_latency_avg_ms,
            resolver_fallback_active: dns_stats.resolver_fallback_active,
            resolver_fallback_reason: dns_stats.resolver_fallback_reason,
            dht_trigger_observations: (dns_stats.dht_trigger_observations != 0)
                .then_some(dns_stats.dht_trigger_observations),
            last_dht_trigger_endpoint: dns_stats.last_dht_trigger_endpoint,
            last_dht_trigger_at: dns_stats.last_dht_trigger_at_ms,
            network_handover_class: None,
            strategy_pack_id: None,
            strategy_pack_version: None,
            tls_profile_id: None,
            tls_profile_catalog_version: None,
            morph_policy_id: None,
            quic_migration_status: None,
            quic_migration_reason: None,
            pt_runtime_kind: None,
            pt_runtime_state: None,
            last_target: None,
            last_host: dns_stats.last_host,
            last_error: self.last_error.load().as_ref().map(|a| (**a).clone()),
            dns_queries_total: dns_stats.dns_queries_total,
            dns_cache_hits: dns_stats.dns_cache_hits,
            dns_cache_misses: dns_stats.dns_cache_misses,
            dns_failures_total: dns_stats.dns_failures_total,
            last_dns_host: dns_stats.last_dns_host,
            last_dns_error: dns_stats.last_dns_error,
            tunnel_stats: TunnelStatsSnapshot {
                tx_packets: traffic_stats.0,
                tx_bytes: traffic_stats.1,
                rx_packets: traffic_stats.2,
                rx_bytes: traffic_stats.3,
            },
            native_events: drain_tunnel_events().into_iter().map(NativeRuntimeEvent::from).collect(),
            latency_distributions: LatencyDistributions {
                dns_resolution: self.dns_histogram.snapshot(),
                ..Default::default()
            }
            .into_option(),
            captured_at: now_ms(),
        }
    }
}

pub(crate) fn now_ms() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}
