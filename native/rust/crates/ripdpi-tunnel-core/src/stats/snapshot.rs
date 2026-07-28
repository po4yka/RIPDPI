use std::sync::atomic::Ordering;

use super::Stats;
use super::time;

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct DnsStatsSnapshot {
    pub dns_queries_total: u64,
    pub dns_cache_hits: u64,
    pub dns_cache_misses: u64,
    pub dns_failures_total: u64,
    pub split_dns_proxy_decisions: u64,
    pub split_dns_direct_fallback_decisions: u64,
    pub split_dns_block_decisions: u64,
    pub direct_dns_successes: u64,
    pub direct_dns_stale_responses: u64,
    pub last_split_dns_coverage_reason: Option<String>,
    pub last_dns_host: Option<String>,
    pub last_dns_error: Option<String>,
    pub last_host: Option<String>,
    pub resolver_endpoint: Option<String>,
    pub resolver_latency_ms: Option<u64>,
    pub resolver_latency_avg_ms: Option<u64>,
    pub resolver_fallback_active: bool,
    pub resolver_fallback_reason: Option<String>,
    pub dht_trigger_observations: u64,
    pub last_dht_trigger_endpoint: Option<String>,
    pub last_dht_trigger_at_ms: Option<u64>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct TunForwardingEvidenceSnapshot {
    pub tun_read_packets: u64,
    pub tun_read_bytes: u64,
    pub tun_write_packets: u64,
    pub tun_write_bytes: u64,
    pub tun_read_errors: u64,
    pub tun_write_errors: u64,
    pub tun_parse_failures: u64,
    pub tun_policy_drops: u64,
    pub tun_interceptor_drops: u64,
    pub tun_queue_drops: u64,
    pub first_tun_write_at_epoch_ms: Option<u64>,
    pub last_tun_write_at_epoch_ms: Option<u64>,
}

pub(crate) fn tun_forwarding_evidence_snapshot(stats: &Stats) -> TunForwardingEvidenceSnapshot {
    // Ordering: most fields are independent best-effort telemetry values.
    // `tun_write_packets` is loaded with Acquire because
    // `record_tun_write_success` publishes first/last TUN-write timestamps
    // before incrementing it with Release.
    let tun_write_packets = stats.rx_packets.load(Ordering::Acquire);
    let first_tun_write_at_epoch_ms = time::non_zero_u64(stats.first_tun_write_at_epoch_ms.load(Ordering::Acquire));
    let last_tun_write_at_epoch_ms = time::non_zero_u64(stats.last_tun_write_at_epoch_ms.load(Ordering::Acquire));
    TunForwardingEvidenceSnapshot {
        tun_read_packets: stats.tx_packets.load(Ordering::Relaxed),
        tun_read_bytes: stats.tx_bytes.load(Ordering::Relaxed),
        tun_write_packets,
        tun_write_bytes: stats.rx_bytes.load(Ordering::Relaxed),
        tun_read_errors: stats.tun_read_errors.load(Ordering::Relaxed),
        tun_write_errors: stats.tun_write_errors.load(Ordering::Relaxed),
        tun_parse_failures: stats.tun_parse_failures.load(Ordering::Relaxed),
        tun_policy_drops: stats.tun_policy_drops.load(Ordering::Relaxed),
        tun_interceptor_drops: stats.tun_interceptor_drops.load(Ordering::Relaxed),
        tun_queue_drops: stats.tun_queue_drops.load(Ordering::Relaxed),
        first_tun_write_at_epoch_ms,
        last_tun_write_at_epoch_ms,
    }
}

pub(crate) fn dns_snapshot(stats: &Stats) -> DnsStatsSnapshot {
    DnsStatsSnapshot {
        dns_queries_total: stats.dns_queries_total.load(Ordering::Relaxed),
        dns_cache_hits: stats.dns_cache_hits.load(Ordering::Relaxed),
        dns_cache_misses: stats.dns_cache_misses.load(Ordering::Relaxed),
        dns_failures_total: stats.dns_failures_total.load(Ordering::Relaxed),
        split_dns_proxy_decisions: stats.split_dns_proxy_decisions.load(Ordering::Relaxed),
        split_dns_direct_fallback_decisions: stats.split_dns_direct_fallback_decisions.load(Ordering::Relaxed),
        split_dns_block_decisions: stats.split_dns_block_decisions.load(Ordering::Relaxed),
        direct_dns_successes: stats.direct_dns_successes.load(Ordering::Relaxed),
        direct_dns_stale_responses: stats.direct_dns_stale_responses.load(Ordering::Relaxed),
        last_split_dns_coverage_reason: cloned_mutex_value(&stats.last_split_dns_coverage_reason),
        last_dns_host: cloned_mutex_value(&stats.last_dns_host),
        last_dns_error: cloned_mutex_value(&stats.last_dns_error),
        last_host: cloned_mutex_value(&stats.last_host),
        resolver_endpoint: cloned_mutex_value(&stats.resolver_endpoint),
        resolver_latency_ms: copied_mutex_value(&stats.resolver_latency_ms),
        resolver_latency_avg_ms: resolver_latency_average(stats),
        resolver_fallback_active: stats.resolver_fallback_active.load(Ordering::Relaxed) != 0,
        resolver_fallback_reason: cloned_mutex_value(&stats.resolver_fallback_reason),
        dht_trigger_observations: stats.dht_trigger_observations.load(Ordering::Relaxed),
        last_dht_trigger_endpoint: cloned_mutex_value(&stats.last_dht_trigger_endpoint),
        last_dht_trigger_at_ms: time::non_zero_u64(stats.last_dht_trigger_at_ms.load(Ordering::Relaxed)),
    }
}

fn resolver_latency_average(stats: &Stats) -> Option<u64> {
    stats
        .resolver_latency_window
        .lock()
        .ok()
        .and_then(|guard| if guard.is_empty() { None } else { Some(guard.iter().sum::<u64>() / guard.len() as u64) })
}

fn cloned_mutex_value(target: &std::sync::Mutex<Option<String>>) -> Option<String> {
    target.lock().ok().and_then(|guard| guard.clone())
}

fn copied_mutex_value(target: &std::sync::Mutex<Option<u64>>) -> Option<u64> {
    target.lock().ok().and_then(|guard| *guard)
}
