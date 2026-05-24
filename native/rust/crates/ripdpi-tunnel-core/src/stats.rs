mod counters;
mod dht;
mod dns;
mod observer;
mod snapshot;
mod time;

use std::collections::VecDeque;
use std::sync::atomic::AtomicU64;
use std::sync::{Arc, Mutex};

pub use snapshot::DnsStatsSnapshot;

/// Observation emitted on every SOCKS5-connect attempt.
///
/// `rtt_ms` is wall-clock time from the start of `socks5::connect` to either
/// its successful return or the error arm. Cancel-safety: callers must
/// measure with `Instant::now()` BEFORE any `.await` and emit synchronously
/// after the await resolves; never put an `.await` between the `Instant`
/// capture and the observer invocation.
#[derive(Debug, Clone, Copy)]
pub struct TcpConnectObservation {
    pub rtt_ms: u64,
    pub succeeded: bool,
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
    pub dht_trigger_observations: AtomicU64,
    pub last_dht_trigger_endpoint: Mutex<Option<String>>,
    pub last_dht_trigger_at_ms: AtomicU64,
    /// Optional callback invoked with the latency (ms) on each successful DNS
    /// resolution. Kept in an `Arc<dyn Fn>` so callers can cheaply clone a
    /// handle and share it with external histogram state without requiring
    /// ripdpi-tunnel-core to depend on any telemetry crate.
    pub dns_latency_observer: Mutex<Option<Arc<dyn Fn(u64) + Send + Sync>>>,
    /// Optional callback invoked on every SOCKS5-connect attempt (both
    /// success and failure). Kept in an `Arc<dyn Fn>` for the same reason
    /// as `dns_latency_observer`: ripdpi-tunnel-core stays observer-pattern-
    /// agnostic and does not depend on any telemetry/quality crate.
    pub quality_observer: Mutex<Option<Arc<dyn Fn(TcpConnectObservation) + Send + Sync>>>,
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
            resolver_latency_window: Mutex::new(VecDeque::with_capacity(dns::RESOLVER_LATENCY_WINDOW_CAPACITY)),
            resolver_fallback_active: AtomicU64::new(0),
            resolver_fallback_reason: Mutex::new(None),
            dht_trigger_observations: AtomicU64::new(0),
            last_dht_trigger_endpoint: Mutex::new(None),
            last_dht_trigger_at_ms: AtomicU64::new(0),
            dns_latency_observer: Mutex::new(None),
            quality_observer: Mutex::new(None),
        }
    }

    /// Installs a callback that is invoked with the resolver latency (ms) on
    /// every successful DNS resolution. Call before the tunnel starts running.
    pub fn set_dns_latency_observer(&self, observer: Arc<dyn Fn(u64) + Send + Sync>) {
        observer::set_dns_latency_observer(self, observer);
    }

    /// Installs a callback that is invoked on every SOCKS5-connect attempt
    /// (both success and failure). Call before the tunnel starts running.
    /// The observer receives the round-trip time of the connect plus a
    /// success flag — see `TcpConnectObservation`.
    pub fn set_quality_observer(&self, observer: Arc<dyn Fn(TcpConnectObservation) + Send + Sync>) {
        observer::set_quality_observer(self, observer);
    }

    /// Emit a SOCKS5-connect observation. Intended for call sites inside
    /// `ripdpi-tunnel-core` (notably `TcpSession::run_with_proxy`) — kept
    /// `pub(crate)` so external callers cannot fabricate observations.
    pub(crate) fn emit_tcp_connect_observation(&self, obs: TcpConnectObservation) {
        observer::notify_quality(self, obs);
    }

    pub fn snapshot(&self) -> (u64, u64, u64, u64) {
        counters::snapshot(self)
    }

    pub fn dns_snapshot(&self) -> DnsStatsSnapshot {
        snapshot::dns_snapshot(self)
    }

    pub fn record_dht_trigger_destination(&self, endpoint: std::net::SocketAddr) {
        dht::record_trigger_destination(self, endpoint);
    }

    pub fn record_dns_success(
        &self,
        host: &str,
        cache_hits: u64,
        cache_misses: u64,
        resolver_endpoint: Option<&str>,
        resolver_latency_ms: Option<u64>,
    ) {
        dns::record_success(self, host, cache_hits, cache_misses, resolver_endpoint, resolver_latency_ms);
    }

    pub fn record_dns_failure(&self, host: Option<&str>, error: &str, resolver_endpoint: Option<&str>) {
        dns::record_failure(self, host, error, resolver_endpoint);
    }

    pub fn record_last_host(&self, host: Option<&str>) {
        dns::record_last_host(self, host);
    }

    pub fn configure_resolver_fallback(&self, active: bool, reason: Option<&str>) {
        dns::configure_resolver_fallback(self, active, reason);
    }
}

#[cfg(test)]
mod tests {
    use std::net::{IpAddr, Ipv4Addr, SocketAddr};
    use std::sync::atomic::Ordering;

    use super::{DnsStatsSnapshot, Stats};

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

    #[test]
    fn dht_trigger_stats_record_matching_cidr_destinations() {
        let stats = Stats::new();

        stats.record_dht_trigger_destination(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(134, 195, 198, 23)), 6881));
        stats.record_dht_trigger_destination(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(62, 210, 12, 77)), 49000));

        let snapshot = stats.dns_snapshot();
        assert_eq!(snapshot.dht_trigger_observations, 2);
        assert_eq!(snapshot.last_dht_trigger_endpoint.as_deref(), Some("62.210.12.77:49000"));
        assert!(snapshot.last_dht_trigger_at_ms.is_some());
    }

    #[test]
    fn dht_trigger_stats_ignore_non_matching_destinations() {
        let stats = Stats::new();

        stats.record_dht_trigger_destination(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(8, 8, 8, 8)), 6881));
        stats.record_dht_trigger_destination(SocketAddr::new(IpAddr::V6(std::net::Ipv6Addr::LOCALHOST), 6881));

        let snapshot = stats.dns_snapshot();
        assert_eq!(snapshot.dht_trigger_observations, 0);
        assert!(snapshot.last_dht_trigger_endpoint.is_none());
        assert!(snapshot.last_dht_trigger_at_ms.is_none());
    }
}
