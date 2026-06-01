use std::sync::atomic::Ordering;

use super::Stats;
use super::time;

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct DnsStatsSnapshot {
    pub dns_queries_total: u64,
    pub dns_cache_hits: u64,
    pub dns_cache_misses: u64,
    pub dns_failures_total: u64,
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

pub(crate) fn dns_snapshot(stats: &Stats) -> DnsStatsSnapshot {
    DnsStatsSnapshot {
        dns_queries_total: stats.dns_queries_total.load(Ordering::Relaxed),
        dns_cache_hits: stats.dns_cache_hits.load(Ordering::Relaxed),
        dns_cache_misses: stats.dns_cache_misses.load(Ordering::Relaxed),
        dns_failures_total: stats.dns_failures_total.load(Ordering::Relaxed),
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
