use std::sync::atomic::Ordering;

use super::observer;
use super::Stats;

pub(crate) const RESOLVER_LATENCY_WINDOW_CAPACITY: usize = 32;

pub(crate) fn record_success(
    stats: &Stats,
    host: &str,
    cache_hits: u64,
    cache_misses: u64,
    resolver_endpoint: Option<&str>,
    resolver_latency_ms: Option<u64>,
) {
    stats.dns_queries_total.fetch_add(1, Ordering::Relaxed);
    stats.dns_cache_hits.fetch_add(cache_hits, Ordering::Relaxed);
    stats.dns_cache_misses.fetch_add(cache_misses, Ordering::Relaxed);
    set_optional_string(&stats.last_dns_host, Some(host));
    set_optional_string(&stats.last_dns_error, None);
    set_optional_string(&stats.resolver_endpoint, resolver_endpoint);
    set_optional_u64(&stats.resolver_latency_ms, resolver_latency_ms);

    if let Some(latency_ms) = resolver_latency_ms {
        record_resolver_latency(stats, latency_ms);
        observer::notify_dns_latency(stats, latency_ms);
    }
}

pub(crate) fn record_failure(stats: &Stats, host: Option<&str>, error: &str, resolver_endpoint: Option<&str>) {
    stats.dns_queries_total.fetch_add(1, Ordering::Relaxed);
    stats.dns_failures_total.fetch_add(1, Ordering::Relaxed);
    if host.is_some() {
        set_optional_string(&stats.last_dns_host, host);
    }
    set_optional_string(&stats.last_dns_error, Some(error));
    set_optional_string(&stats.resolver_endpoint, resolver_endpoint);
}

pub(crate) fn record_last_host(stats: &Stats, host: Option<&str>) {
    set_optional_string(&stats.last_host, host);
}

pub(crate) fn configure_resolver_fallback(stats: &Stats, active: bool, reason: Option<&str>) {
    stats.resolver_fallback_active.store(u64::from(active), Ordering::Relaxed);
    set_optional_string(&stats.resolver_fallback_reason, reason);
}

fn record_resolver_latency(stats: &Stats, latency_ms: u64) {
    if let Ok(mut guard) = stats.resolver_latency_window.lock() {
        if guard.len() >= RESOLVER_LATENCY_WINDOW_CAPACITY {
            guard.pop_front();
        }
        guard.push_back(latency_ms);
    }
}

fn set_optional_string(target: &std::sync::Mutex<Option<String>>, value: Option<&str>) {
    if let Ok(mut guard) = target.lock() {
        *guard = value.map(ToString::to_string);
    }
}

fn set_optional_u64(target: &std::sync::Mutex<Option<u64>>, value: Option<u64>) {
    if let Ok(mut guard) = target.lock() {
        *guard = value;
    }
}
