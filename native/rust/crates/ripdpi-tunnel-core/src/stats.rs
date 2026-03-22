use std::collections::VecDeque;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

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
}

/// Per-tunnel traffic and DNS counters.
///
/// Atomic counters use `Relaxed` ordering because the values are read only for
/// reporting; string fields are protected by `Mutex` for best-effort snapshots.
pub struct Stats {
    pub tx_packets: AtomicU64,
    pub tx_bytes: AtomicU64,
    pub rx_packets: AtomicU64,
    pub rx_bytes: AtomicU64,
    pub dns_queries_total: AtomicU64,
    pub dns_cache_hits: AtomicU64,
    pub dns_cache_misses: AtomicU64,
    pub dns_failures_total: AtomicU64,
    pub last_dns_host: Mutex<Option<String>>,
    pub last_dns_error: Mutex<Option<String>>,
    pub last_host: Mutex<Option<String>>,
    pub resolver_endpoint: Mutex<Option<String>>,
    pub resolver_latency_ms: Mutex<Option<u64>>,
    pub resolver_latency_window: Mutex<VecDeque<u64>>,
    pub resolver_fallback_active: AtomicU64,
    pub resolver_fallback_reason: Mutex<Option<String>>,
    /// Optional callback invoked with the latency (ms) on each successful DNS
    /// resolution. Kept in an `Arc<dyn Fn>` so callers can cheaply clone a
    /// handle and share it with external histogram state without requiring
    /// ripdpi-tunnel-core to depend on any telemetry crate.
    pub dns_latency_observer: Mutex<Option<Arc<dyn Fn(u64) + Send + Sync>>>,
}

impl Default for Stats {
    fn default() -> Self {
        Self::new()
    }
}

impl Stats {
    pub fn new() -> Self {
        Self {
            tx_packets: AtomicU64::new(0),
            tx_bytes: AtomicU64::new(0),
            rx_packets: AtomicU64::new(0),
            rx_bytes: AtomicU64::new(0),
            dns_queries_total: AtomicU64::new(0),
            dns_cache_hits: AtomicU64::new(0),
            dns_cache_misses: AtomicU64::new(0),
            dns_failures_total: AtomicU64::new(0),
            last_dns_host: Mutex::new(None),
            last_dns_error: Mutex::new(None),
            last_host: Mutex::new(None),
            resolver_endpoint: Mutex::new(None),
            resolver_latency_ms: Mutex::new(None),
            resolver_latency_window: Mutex::new(VecDeque::with_capacity(32)),
            resolver_fallback_active: AtomicU64::new(0),
            resolver_fallback_reason: Mutex::new(None),
            dns_latency_observer: Mutex::new(None),
        }
    }

    /// Installs a callback that is invoked with the resolver latency (ms) on
    /// every successful DNS resolution. Call before the tunnel starts running.
    pub fn set_dns_latency_observer(&self, observer: Arc<dyn Fn(u64) + Send + Sync>) {
        if let Ok(mut guard) = self.dns_latency_observer.lock() {
            *guard = Some(observer);
        }
    }

    pub fn snapshot(&self) -> (u64, u64, u64, u64) {
        (
            self.tx_packets.load(Ordering::Relaxed),
            self.tx_bytes.load(Ordering::Relaxed),
            self.rx_packets.load(Ordering::Relaxed),
            self.rx_bytes.load(Ordering::Relaxed),
        )
    }

    pub fn dns_snapshot(&self) -> DnsStatsSnapshot {
        DnsStatsSnapshot {
            dns_queries_total: self.dns_queries_total.load(Ordering::Relaxed),
            dns_cache_hits: self.dns_cache_hits.load(Ordering::Relaxed),
            dns_cache_misses: self.dns_cache_misses.load(Ordering::Relaxed),
            dns_failures_total: self.dns_failures_total.load(Ordering::Relaxed),
            last_dns_host: self.last_dns_host.lock().ok().and_then(|guard| guard.clone()),
            last_dns_error: self.last_dns_error.lock().ok().and_then(|guard| guard.clone()),
            last_host: self.last_host.lock().ok().and_then(|guard| guard.clone()),
            resolver_endpoint: self.resolver_endpoint.lock().ok().and_then(|guard| guard.clone()),
            resolver_latency_ms: self.resolver_latency_ms.lock().ok().and_then(|guard| *guard),
            resolver_latency_avg_ms: self.resolver_latency_window.lock().ok().and_then(|guard| {
                if guard.is_empty() {
                    None
                } else {
                    Some(guard.iter().sum::<u64>() / guard.len() as u64)
                }
            }),
            resolver_fallback_active: self.resolver_fallback_active.load(Ordering::Relaxed) != 0,
            resolver_fallback_reason: self.resolver_fallback_reason.lock().ok().and_then(|guard| guard.clone()),
        }
    }

    pub fn record_dns_success(
        &self,
        host: &str,
        cache_hits: u64,
        cache_misses: u64,
        resolver_endpoint: Option<&str>,
        resolver_latency_ms: Option<u64>,
    ) {
        self.dns_queries_total.fetch_add(1, Ordering::Relaxed);
        self.dns_cache_hits.fetch_add(cache_hits, Ordering::Relaxed);
        self.dns_cache_misses.fetch_add(cache_misses, Ordering::Relaxed);
        if let Ok(mut guard) = self.last_dns_host.lock() {
            *guard = Some(host.to_string());
        }
        if let Ok(mut guard) = self.last_dns_error.lock() {
            *guard = None;
        }
        if let Ok(mut guard) = self.resolver_endpoint.lock() {
            *guard = resolver_endpoint.map(ToString::to_string);
        }
        if let Ok(mut guard) = self.resolver_latency_ms.lock() {
            *guard = resolver_latency_ms;
        }
        if let Some(latency_ms) = resolver_latency_ms {
            if let Ok(mut guard) = self.resolver_latency_window.lock() {
                if guard.len() >= 32 {
                    guard.pop_front();
                }
                guard.push_back(latency_ms);
            }
            if let Ok(guard) = self.dns_latency_observer.lock() {
                if let Some(observer) = guard.as_ref() {
                    observer(latency_ms);
                }
            }
        }
    }

    pub fn record_dns_failure(&self, host: Option<&str>, error: &str, resolver_endpoint: Option<&str>) {
        self.dns_queries_total.fetch_add(1, Ordering::Relaxed);
        self.dns_failures_total.fetch_add(1, Ordering::Relaxed);
        if let Some(host) = host {
            if let Ok(mut guard) = self.last_dns_host.lock() {
                *guard = Some(host.to_string());
            }
        }
        if let Ok(mut guard) = self.last_dns_error.lock() {
            *guard = Some(error.to_string());
        }
        if let Ok(mut guard) = self.resolver_endpoint.lock() {
            *guard = resolver_endpoint.map(ToString::to_string);
        }
    }

    pub fn record_last_host(&self, host: Option<&str>) {
        if let Ok(mut guard) = self.last_host.lock() {
            *guard = host.map(ToString::to_string);
        }
    }

    pub fn configure_resolver_fallback(&self, active: bool, reason: Option<&str>) {
        self.resolver_fallback_active.store(u64::from(active), Ordering::Relaxed);
        if let Ok(mut guard) = self.resolver_fallback_reason.lock() {
            *guard = reason.map(ToString::to_string);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn u08_stats_counters_increment() {
        let stats = Stats::new();

        stats.tx_packets.fetch_add(1, Ordering::Relaxed);
        stats.tx_bytes.fetch_add(100, Ordering::Relaxed);
        stats.tx_packets.fetch_add(1, Ordering::Relaxed);
        stats.tx_bytes.fetch_add(200, Ordering::Relaxed);
        stats.rx_packets.fetch_add(1, Ordering::Relaxed);
        stats.rx_bytes.fetch_add(150, Ordering::Relaxed);

        let (tx_pkts, tx_bytes, rx_pkts, rx_bytes) = stats.snapshot();
        assert_eq!(tx_pkts, 2);
        assert_eq!(tx_bytes, 300);
        assert_eq!(rx_pkts, 1);
        assert_eq!(rx_bytes, 150);
    }

    #[test]
    fn stats_start_at_zero() {
        let stats = Stats::new();
        assert_eq!(stats.snapshot(), (0, 0, 0, 0));
        assert_eq!(stats.dns_snapshot(), DnsStatsSnapshot::default());
    }

    #[test]
    fn dns_stats_record_success_and_failure() {
        let stats = Stats::new();
        stats.record_dns_success("fixture.test", 1, 2, Some("https://dns.example/dns-query"), Some(42));
        stats.record_last_host(Some("fixture.test"));
        stats.record_dns_failure(Some("fixture.test"), "boom", Some("https://dns.example/dns-query"));
        stats.configure_resolver_fallback(true, Some("temporary override"));

        let snapshot = stats.dns_snapshot();
        assert_eq!(snapshot.dns_queries_total, 2);
        assert_eq!(snapshot.dns_cache_hits, 1);
        assert_eq!(snapshot.dns_cache_misses, 2);
        assert_eq!(snapshot.dns_failures_total, 1);
        assert_eq!(snapshot.last_dns_host.as_deref(), Some("fixture.test"));
        assert_eq!(snapshot.last_dns_error.as_deref(), Some("boom"));
        assert_eq!(snapshot.last_host.as_deref(), Some("fixture.test"));
        assert_eq!(snapshot.resolver_endpoint.as_deref(), Some("https://dns.example/dns-query"));
        assert_eq!(snapshot.resolver_latency_ms, Some(42));
        assert_eq!(snapshot.resolver_latency_avg_ms, Some(42));
        assert!(snapshot.resolver_fallback_active);
        assert_eq!(snapshot.resolver_fallback_reason.as_deref(), Some("temporary override"));
    }
}
