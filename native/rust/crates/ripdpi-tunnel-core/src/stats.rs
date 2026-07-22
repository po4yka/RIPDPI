mod counters;
mod dht;
mod dns;
mod observer;
mod snapshot;
mod time;

use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, AtomicU64};
use std::sync::{Arc, Mutex};

pub use snapshot::DnsStatsSnapshot;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum SplitDnsDecisionKind {
    ProxyEncrypted,
    DirectProxyFallback,
    Block,
}

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

/// Synchronous packet-flow observer. Invoked once per packet at the
/// TUN drain (inbound from kernel -> userspace) and TUN flush
/// (outbound from userspace -> kernel) boundaries of `io_loop_task`.
///
/// MUST be synchronous (no `.await`, no I/O) -- the io_loop hot path
/// invokes this on every packet. The PCAP capture-set implementation
/// in `ripdpi-tunnel-android` uses a lock-free `ArrayQueue::push` that
/// returns immediately when the queue is full.
///
/// Cancel-safety: synchronous, no `.await` between invocations and
/// continuation. Cannot introduce cancel-safety issues into
/// `io_loop_task`.
pub trait PacketObserver: Send + Sync {
    /// Called when a packet was read from the TUN (inbound to the
    /// userspace TCP/UDP stack).
    fn on_inbound(&self, packet: &[u8]);

    /// Called when a packet is about to be written to the TUN
    /// (outbound from the userspace stack back to the kernel).
    fn on_outbound(&self, packet: &[u8]);
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
    /// ICMPv4/ICMPv6 packets classified at the TUN ingress boundary before
    /// UID-policy admission or drop.
    pub icmp_ingress_packets: AtomicU64,
    pub dns_queries_total: AtomicU64,
    pub dns_cache_hits: AtomicU64,
    pub dns_cache_misses: AtomicU64,
    pub dns_failures_total: AtomicU64,
    pub split_dns_proxy_decisions: AtomicU64,
    pub split_dns_direct_fallback_decisions: AtomicU64,
    pub split_dns_block_decisions: AtomicU64,
    pub last_split_dns_coverage_reason: Mutex<Option<String>>,
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
    /// Optional synchronous packet observer. Invoked on every packet
    /// at the TUN drain (inbound) and TUN flush (outbound) boundaries
    /// of `io_loop_task`. Wired from the JNI layer in
    /// `ripdpi-tunnel-android` to feed `PcapCaptureSet`.
    ///
    /// The per-packet hot path (`notify_inbound_packet` /
    /// `notify_outbound_packet`) gates this `Mutex` behind
    /// [`Self::packet_observer_present`] so the common (no PCAP capture
    /// active) case takes NO lock per packet -- only a `Relaxed` atomic
    /// load. The lock is acquired only when an observer is actually
    /// installed.
    pub packet_observer: Mutex<Option<Arc<dyn PacketObserver>>>,
    /// Fast-path presence flag for [`Self::packet_observer`]. Set to
    /// `true` while holding the `packet_observer` lock whenever an
    /// observer is installed, cleared when removed. The per-packet
    /// notify helpers check this atomic FIRST and skip the `Mutex`
    /// lock entirely when it reads `false` -- the overwhelmingly
    /// common case on the io_loop hot path.
    ///
    /// `Relaxed` ordering is sufficient: the flag is a hint, never a
    /// gate for data published through other memory. When it transitions
    /// `false -> true` the `set_packet_observer` writer also publishes the
    /// `Arc` under the `Mutex`, whose `lock()` provides the acquire/release
    /// happens-before edge for the observer pointer itself. A momentarily
    /// stale `false` read simply drops one packet from the capture (which
    /// `PcapCaptureSet` already tolerates via its lossy `ArrayQueue`); a
    /// stale `true` read just takes the lock and re-checks the `Option`.
    pub packet_observer_present: AtomicBool,
    /// Optional callback invoked every `LOSS_EMIT_INTERVAL` loop iterations
    /// with the current TCP-retransmit-derived loss percentage (0.0..=100.0).
    /// Kept in an `Arc<dyn Fn>` for the same reason as other observers:
    /// ripdpi-tunnel-core stays observer-pattern-agnostic.
    pub loss_observer: Mutex<Option<Arc<dyn Fn(f32) + Send + Sync>>>,
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
            icmp_ingress_packets: AtomicU64::new(0),
            dns_queries_total: AtomicU64::new(0),
            dns_cache_hits: AtomicU64::new(0),
            dns_cache_misses: AtomicU64::new(0),
            dns_failures_total: AtomicU64::new(0),
            split_dns_proxy_decisions: AtomicU64::new(0),
            split_dns_direct_fallback_decisions: AtomicU64::new(0),
            split_dns_block_decisions: AtomicU64::new(0),
            last_split_dns_coverage_reason: Mutex::new(None),
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
            packet_observer: Mutex::new(None),
            packet_observer_present: AtomicBool::new(false),
            loss_observer: Mutex::new(None),
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

    /// Installs a synchronous packet observer that is invoked on every
    /// packet at the TUN drain (inbound) and TUN flush (outbound)
    /// boundaries of `io_loop_task`. Replaces any previously-installed
    /// observer atomically (with respect to subsequent calls).
    ///
    /// The observer MUST be synchronous and bounded -- the io_loop hot
    /// path invokes it once per packet on the calling tokio worker. See
    /// [`PacketObserver`] for the contract.
    pub fn set_packet_observer(&self, observer: Arc<dyn PacketObserver>) {
        observer::set_packet_observer(self, observer);
    }

    /// Removes the packet observer before its backing capture resources are retired.
    pub fn clear_packet_observer(&self) {
        observer::clear_packet_observer(self);
    }

    /// Installs a callback that is invoked every `LOSS_EMIT_INTERVAL` loop
    /// iterations with the current TCP-retransmit-derived loss percentage
    /// (0.0..=100.0). Call before the tunnel starts running.
    pub fn set_loss_observer(&self, observer: Arc<dyn Fn(f32) + Send + Sync>) {
        observer::set_loss_observer(self, observer);
    }

    /// Emit a TCP-retransmit loss percentage observation. Kept `pub(crate)`
    /// so only `io_loop` internals can fabricate observations.
    pub(crate) fn emit_loss_pct(&self, loss_pct: f32) {
        observer::notify_loss(self, loss_pct);
    }

    /// Hot-path helper: invoke the installed packet observer's
    /// `on_inbound` callback, if any. Called from `phases::drain_tun`
    /// once per packet read from the TUN fd. When no observer is
    /// installed this is a single mutex lock + Option check (no
    /// allocation, no callback).
    pub(crate) fn on_inbound_packet(&self, packet: &[u8]) {
        observer::notify_inbound_packet(self, packet);
    }

    /// Hot-path helper: invoke the installed packet observer's
    /// `on_outbound` callback, if any. Called from
    /// `bridge::flush_device_tx_queue` once per packet written to the
    /// TUN fd. Same no-observer cost as `on_inbound_packet`.
    pub(crate) fn on_outbound_packet(&self, packet: &[u8]) {
        observer::notify_outbound_packet(self, packet);
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

    pub fn icmp_ingress_packets(&self) -> u64 {
        self.icmp_ingress_packets.load(std::sync::atomic::Ordering::Relaxed)
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

    pub(crate) fn record_split_dns_decision(&self, kind: SplitDnsDecisionKind, reason: Option<&str>) {
        let counter = match kind {
            SplitDnsDecisionKind::ProxyEncrypted => &self.split_dns_proxy_decisions,
            SplitDnsDecisionKind::DirectProxyFallback => &self.split_dns_direct_fallback_decisions,
            SplitDnsDecisionKind::Block => {
                self.dns_queries_total.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                &self.split_dns_block_decisions
            }
        };
        counter.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        if let (Some(reason), Ok(mut target)) = (reason, self.last_split_dns_coverage_reason.lock()) {
            *target = Some(reason.to_string());
        }
    }

    pub(crate) fn record_dns_response_failure(&self, error: &str) {
        self.dns_failures_total.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        if let Ok(mut target) = self.last_dns_error.lock() {
            *target = Some(error.to_string());
        }
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
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::{Arc, Mutex};

    use super::{DnsStatsSnapshot, PacketObserver, Stats};

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
    fn split_dns_decisions_have_redacted_bounded_counters() {
        let stats = Stats::new();
        stats.record_split_dns_decision(super::SplitDnsDecisionKind::ProxyEncrypted, None);
        stats.record_split_dns_decision(super::SplitDnsDecisionKind::DirectProxyFallback, Some("direct_plane_unbound"));
        stats.record_split_dns_decision(super::SplitDnsDecisionKind::Block, None);

        let snapshot = stats.dns_snapshot();
        assert_eq!(snapshot.split_dns_proxy_decisions, 1);
        assert_eq!(snapshot.split_dns_direct_fallback_decisions, 1);
        assert_eq!(snapshot.split_dns_block_decisions, 1);
        assert_eq!(snapshot.dns_queries_total, 1, "only local BLOCK counts immediately");
        assert_eq!(snapshot.last_split_dns_coverage_reason.as_deref(), Some("direct_plane_unbound"));
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

    // PacketObserver: synchronous packet-flow observer wired into io_loop
    // drain/flush phases. Tests verify (a) no observer is a safe no-op,
    // (b) installed observer receives every packet, and (c) replacing the
    // observer routes subsequent invocations to the new one.

    struct RecordingObserver(Arc<Mutex<Vec<Vec<u8>>>>);
    impl PacketObserver for RecordingObserver {
        fn on_inbound(&self, packet: &[u8]) {
            self.0.lock().expect("recorder lock").push(packet.to_vec());
        }
        fn on_outbound(&self, _packet: &[u8]) {}
    }

    struct CountingObserver(Arc<AtomicUsize>);
    impl PacketObserver for CountingObserver {
        fn on_inbound(&self, _packet: &[u8]) {
            self.0.fetch_add(1, Ordering::Relaxed);
        }
        fn on_outbound(&self, _packet: &[u8]) {}
    }

    #[test]
    fn no_packet_observer_is_noop() {
        let stats = Stats::default();
        stats.on_inbound_packet(b"hello");
        stats.on_outbound_packet(b"world");
        // No panic, no observer set — fine.
    }

    #[test]
    fn set_packet_observer_then_inbound_is_called() {
        let stats = Stats::default();
        let captured: Arc<Mutex<Vec<Vec<u8>>>> = Arc::new(Mutex::new(Vec::new()));
        stats.set_packet_observer(Arc::new(RecordingObserver(Arc::clone(&captured))));

        stats.on_inbound_packet(b"hello");
        stats.on_inbound_packet(b"world");

        let recorded = captured.lock().expect("recorder lock");
        assert_eq!(recorded.len(), 2);
        assert_eq!(&recorded[0], b"hello");
        assert_eq!(&recorded[1], b"world");
    }

    #[test]
    fn replacing_observer_replaces_callbacks() {
        let stats = Stats::default();
        let count_a = Arc::new(AtomicUsize::new(0));
        let count_b = Arc::new(AtomicUsize::new(0));

        stats.set_packet_observer(Arc::new(CountingObserver(Arc::clone(&count_a))));
        stats.on_inbound_packet(b"a");
        stats.set_packet_observer(Arc::new(CountingObserver(Arc::clone(&count_b))));
        stats.on_inbound_packet(b"b");

        assert_eq!(count_a.load(Ordering::Relaxed), 1);
        assert_eq!(count_b.load(Ordering::Relaxed), 1);
    }

    #[test]
    fn clearing_packet_observer_stops_callbacks() {
        let stats = Stats::default();
        let count = Arc::new(AtomicUsize::new(0));
        stats.set_packet_observer(Arc::new(CountingObserver(Arc::clone(&count))));
        stats.on_inbound_packet(b"before");

        stats.clear_packet_observer();
        stats.on_inbound_packet(b"after");

        assert_eq!(count.load(Ordering::Relaxed), 1);
        assert!(!stats.packet_observer_present.load(Ordering::Relaxed));
    }
}
