use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::os::fd::RawFd;
use std::sync::{Arc, PoisonError};

use ripdpi_dns_resolver::EncryptedDnsExchangeSuccess;
use ripdpi_tunnel_config::{
    SplitDnsAction, SplitDnsDomainMatcher, SplitDnsMatcherKind, SplitDnsNetwork, SplitDnsPolicyConfig, SplitDnsRule,
};

use crate::split_dns::SplitDnsPolicy;
use crate::{Stats, TunDevice};

use super::super::{DnsRequest, DnsResponse, drain_dns_responses, route_dns_packet};
use super::support::{build_query, build_response, test_dns_cache, test_mapdns};

struct FixedBinder(u64);

impl crate::tunnel_api::direct_dns_binding::DirectDnsSocketBinder for FixedBinder {
    fn current_generation(&self) -> Option<u64> {
        Some(self.0)
    }

    fn is_current(&self, generation: u64) -> bool {
        generation == self.0
    }

    fn bind_socket(&self, _fd: RawFd, generation: u64) -> io::Result<()> {
        if self.is_current(generation) { Ok(()) } else { Err(io::ErrorKind::NotConnected.into()) }
    }
}

#[test]
fn route_dns_sends_to_resolver() {
    let (tx, mut rx) = tokio::sync::mpsc::channel::<DnsRequest>(8);
    let (_resp_tx, resp_rx) = tokio::sync::mpsc::channel::<DnsResponse>(8);
    let mut dns_req_tx = Some(tx);
    let mut dns_resp_rx = Some(resp_rx);
    let cache = test_dns_cache();
    let mut device = TunDevice::new(1500);
    let stats = Arc::new(Stats::default());
    let query = build_query("example.test");
    let src = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53000);

    route_dns_packet(
        &mut device,
        &stats,
        Some(test_mapdns()),
        Some(&cache),
        None,
        &mut dns_req_tx,
        &mut dns_resp_rx,
        src,
        &query,
        Some("example.test".to_string()),
    );

    let request = rx.try_recv().expect("request should be enqueued");
    assert_eq!(request.src, src);
    assert_eq!(request.host.as_deref(), Some("example.test"));
    assert!(device.tx_queue.is_empty(), "no response packet should be queued");
    assert!(dns_req_tx.is_some(), "channels should remain open");
}

#[test]
fn route_dns_full_queue_sends_servfail() {
    let (tx, _rx) = tokio::sync::mpsc::channel::<DnsRequest>(1);
    let (_resp_tx, resp_rx) = tokio::sync::mpsc::channel::<DnsResponse>(8);
    let mut dns_req_tx = Some(tx);
    let mut dns_resp_rx = Some(resp_rx);
    let cache = test_dns_cache();
    let mut device = TunDevice::new(1500);
    let stats = Arc::new(Stats::default());
    let query = build_query("first.test");
    let src = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53000);

    route_dns_packet(
        &mut device,
        &stats,
        Some(test_mapdns()),
        Some(&cache),
        None,
        &mut dns_req_tx,
        &mut dns_resp_rx,
        src,
        &query,
        Some("first.test".to_string()),
    );
    assert!(device.tx_queue.is_empty());

    let query2 = build_query("second.test");
    route_dns_packet(
        &mut device,
        &stats,
        Some(test_mapdns()),
        Some(&cache),
        None,
        &mut dns_req_tx,
        &mut dns_resp_rx,
        src,
        &query2,
        Some("second.test".to_string()),
    );

    assert!(!device.tx_queue.is_empty(), "SERVFAIL response should be enqueued");
    assert!(dns_req_tx.is_some(), "channels should remain open after full queue");
}

#[test]
fn route_dns_closed_channel_nulls_tx_rx() {
    let (tx, rx) = tokio::sync::mpsc::channel::<DnsRequest>(8);
    let (_resp_tx, resp_rx) = tokio::sync::mpsc::channel::<DnsResponse>(8);
    let mut dns_req_tx = Some(tx);
    let mut dns_resp_rx = Some(resp_rx);
    let cache = test_dns_cache();
    let mut device = TunDevice::new(1500);
    let stats = Arc::new(Stats::default());
    let query = build_query("closed.test");
    let src = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53000);

    drop(rx);

    route_dns_packet(
        &mut device,
        &stats,
        Some(test_mapdns()),
        Some(&cache),
        None,
        &mut dns_req_tx,
        &mut dns_resp_rx,
        src,
        &query,
        Some("closed.test".to_string()),
    );

    assert!(dns_req_tx.is_none(), "dns_req_tx should be set to None after closed channel");
    assert!(dns_resp_rx.is_none(), "dns_resp_rx should be set to None after closed channel");
    assert!(!device.tx_queue.is_empty(), "SERVFAIL response should be enqueued");
}

#[test]
fn route_dns_no_resolver_sends_servfail() {
    let mut dns_req_tx: Option<tokio::sync::mpsc::Sender<DnsRequest>> = None;
    let mut dns_resp_rx: Option<tokio::sync::mpsc::Receiver<DnsResponse>> = None;
    let cache = test_dns_cache();
    let mut device = TunDevice::new(1500);
    let stats = Arc::new(Stats::default());
    let query = build_query("no-resolver.test");
    let src = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53000);

    route_dns_packet(
        &mut device,
        &stats,
        Some(test_mapdns()),
        Some(&cache),
        None,
        &mut dns_req_tx,
        &mut dns_resp_rx,
        src,
        &query,
        Some("no-resolver.test".to_string()),
    );

    assert!(!device.tx_queue.is_empty(), "SERVFAIL should be sent when no resolver");
}

#[test]
fn route_dns_block_returns_refused_without_upstream() {
    let (tx, mut rx) = tokio::sync::mpsc::channel::<DnsRequest>(8);
    let (_resp_tx, resp_rx) = tokio::sync::mpsc::channel::<DnsResponse>(8);
    let mut dns_req_tx = Some(tx);
    let mut dns_resp_rx = Some(resp_rx);
    let cache = test_dns_cache();
    let mut device = TunDevice::new(1500);
    let stats = Arc::new(Stats::default());
    let policy = SplitDnsPolicy::compile(&SplitDnsPolicyConfig {
        canonical_digest: "a".repeat(64),
        destination_routing_digest: "b".repeat(64),
        default_action: SplitDnsAction::Tunneled,
        rules: vec![SplitDnsRule {
            action: SplitDnsAction::Block,
            network: SplitDnsNetwork::Both,
            domains: vec![SplitDnsDomainMatcher {
                kind: SplitDnsMatcherKind::Suffix,
                value: "blocked.example".to_string(),
            }],
            has_ip_ranges: false,
            has_ports: false,
        }],
        direct_resolver_candidates: Vec::new(),
        bootstrap_pins: Vec::new(),
        coverage_reason: None,
    })
    .expect("policy");
    let query = build_query("api.blocked.example");
    let src = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53000);

    route_dns_packet(
        &mut device,
        &stats,
        Some(test_mapdns()),
        Some(&cache),
        Some(&policy),
        &mut dns_req_tx,
        &mut dns_resp_rx,
        src,
        &query,
        Some("api.blocked.example".to_string()),
    );

    assert!(rx.try_recv().is_err(), "blocked DNS must never reach upstream");
    assert_eq!(device.tx_queue.len(), 1, "REFUSED response should be enqueued");
    let snapshot = stats.dns_snapshot();
    assert_eq!(snapshot.dns_queries_total, 1);
    assert_eq!(snapshot.split_dns_block_decisions, 1);
}

#[test]
fn route_dns_direct_falls_back_to_encrypted_proxy_queue() {
    let _guard = crate::tunnel_api::direct_dns_binding::TEST_BINDER_LOCK.lock().unwrap_or_else(PoisonError::into_inner);
    let (tx, mut rx) = tokio::sync::mpsc::channel::<DnsRequest>(8);
    let (_resp_tx, resp_rx) = tokio::sync::mpsc::channel::<DnsResponse>(8);
    let mut dns_req_tx = Some(tx);
    let mut dns_resp_rx = Some(resp_rx);
    let cache = test_dns_cache();
    let mut device = TunDevice::new(1500);
    let stats = Arc::new(Stats::default());
    let policy = SplitDnsPolicy::compile(&SplitDnsPolicyConfig {
        canonical_digest: "a".repeat(64),
        destination_routing_digest: "b".repeat(64),
        default_action: SplitDnsAction::Tunneled,
        rules: vec![SplitDnsRule {
            action: SplitDnsAction::Direct,
            network: SplitDnsNetwork::Both,
            domains: vec![SplitDnsDomainMatcher {
                kind: SplitDnsMatcherKind::Exact,
                value: "direct.example".to_string(),
            }],
            has_ip_ranges: false,
            has_ports: false,
        }],
        direct_resolver_candidates: vec![IpAddr::V4(Ipv4Addr::new(192, 0, 2, 53))],
        bootstrap_pins: Vec::new(),
        coverage_reason: None,
    })
    .expect("policy");
    let query = build_query("direct.example");
    let src = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53000);

    route_dns_packet(
        &mut device,
        &stats,
        Some(test_mapdns()),
        Some(&cache),
        Some(&policy),
        &mut dns_req_tx,
        &mut dns_resp_rx,
        src,
        &query,
        Some("direct.example".to_string()),
    );

    assert!(rx.try_recv().is_ok(), "direct policy must use the encrypted proxy worker fallback");
    assert!(device.tx_queue.is_empty(), "direct fallback must not synthesize a local response");
    let snapshot = stats.dns_snapshot();
    assert_eq!(snapshot.split_dns_direct_fallback_decisions, 1);
    assert_eq!(snapshot.split_dns_proxy_decisions, 0);
    assert_eq!(snapshot.last_split_dns_coverage_reason.as_deref(), Some("direct_underlay_unavailable"));
}

#[test]
fn drain_dns_responses_processes_pending() {
    let mapdns = test_mapdns();
    let mut cache = test_dns_cache();
    let mut device = TunDevice::new(1500);
    let stats = Arc::new(Stats::default());
    let (resp_tx, resp_rx) = tokio::sync::mpsc::channel::<DnsResponse>(8);
    let (req_tx, _req_rx) = tokio::sync::mpsc::channel::<DnsRequest>(8);
    let mut dns_resp_rx = Some(resp_rx);
    let mut dns_req_tx = Some(req_tx);
    let query = build_query("drain.test");
    let upstream = build_response("drain.test", Ipv4Addr::new(1, 2, 3, 4));

    resp_tx
        .try_send(DnsResponse {
            src: SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53000),
            query,
            host: Some("drain.test".to_string()),
            upstream: Ok(EncryptedDnsExchangeSuccess {
                response_bytes: upstream,
                endpoint_label: "test".to_string(),
                latency_ms: 5,
            }),
            resolver_error_kind: None,
            resolver_endpoint_label: Some("test".to_string()),
            direct_generation: None,
            direct_fallback: false,
        })
        .expect("send response");

    drain_dns_responses(&mut device, &stats, mapdns, &mut cache, &mut dns_resp_rx, &mut dns_req_tx, &mut None);

    assert_eq!(device.tx_queue.len(), 1, "response should have been processed and queued");
    assert!(dns_req_tx.is_some(), "channels should remain open");
}

#[test]
fn stale_direct_and_encrypted_fallback_responses_are_suppressed_after_generation_replacement() {
    use crate::tunnel_api::direct_dns_binding::{
        TEST_BINDER_LOCK, register_direct_dns_socket_binder, unregister_direct_dns_socket_binder_if,
    };

    let _guard = TEST_BINDER_LOCK.lock().unwrap_or_else(PoisonError::into_inner);
    let registration_a = register_direct_dns_socket_binder(Arc::new(FixedBinder(11))).expect("A registration");
    let mapdns = test_mapdns();
    let mut cache = test_dns_cache();
    let old_query = build_query("old.test");
    cache
        .rewrite_response(&old_query, &build_response("old.test", Ipv4Addr::new(203, 0, 113, 1)))
        .expect("prepopulate unleased mapping");
    assert!(cache.lookup(u32::from(Ipv4Addr::new(198, 18, 0, 0))).is_some());

    let mut device = TunDevice::new(1500);
    let stats = Arc::new(Stats::default());
    let (resp_tx, resp_rx) = tokio::sync::mpsc::channel::<DnsResponse>(8);
    let (req_tx, _req_rx) = tokio::sync::mpsc::channel::<DnsRequest>(8);
    let mut dns_resp_rx = Some(resp_rx);
    let mut dns_req_tx = Some(req_tx);
    let src = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53000);

    for (host, direct_fallback) in [("direct-late.test", false), ("fallback-late.test", true)] {
        resp_tx
            .try_send(DnsResponse {
                src,
                query: build_query(host),
                host: Some(host.to_string()),
                upstream: Ok(EncryptedDnsExchangeSuccess {
                    response_bytes: build_response(host, Ipv4Addr::new(203, 0, 113, 2)),
                    endpoint_label: "test".to_string(),
                    latency_ms: 5,
                }),
                resolver_error_kind: None,
                resolver_endpoint_label: Some("test".to_string()),
                direct_generation: Some(11),
                direct_fallback,
            })
            .expect("queue stale response");
    }

    let registration_b = register_direct_dns_socket_binder(Arc::new(FixedBinder(12))).expect("B registration");
    let mut active_generation = None;
    drain_dns_responses(
        &mut device,
        &stats,
        mapdns,
        &mut cache,
        &mut dns_resp_rx,
        &mut dns_req_tx,
        &mut active_generation,
    );

    assert_eq!(device.tx_queue.len(), 2, "both stale results must become SERVFAIL responses");
    assert_eq!(active_generation, Some(12), "cache generation must advance to the current B binding");
    assert_eq!(stats.direct_dns_outcomes(), (0, 2));
    for offset in 0..8 {
        assert!(
            cache.lookup(u32::from(Ipv4Addr::new(198, 18, 0, offset))).is_none(),
            "old and late unleased mappings must be absent",
        );
    }
    resp_tx
        .try_send(DnsResponse {
            src,
            query: build_query("current.test"),
            host: Some("current.test".to_string()),
            upstream: Ok(EncryptedDnsExchangeSuccess {
                response_bytes: build_response("current.test", Ipv4Addr::new(203, 0, 113, 3)),
                endpoint_label: "test".to_string(),
                latency_ms: 5,
            }),
            resolver_error_kind: None,
            resolver_endpoint_label: Some("test".to_string()),
            direct_generation: Some(12),
            direct_fallback: false,
        })
        .expect("queue current response");
    drain_dns_responses(
        &mut device,
        &stats,
        mapdns,
        &mut cache,
        &mut dns_resp_rx,
        &mut dns_req_tx,
        &mut active_generation,
    );
    assert_eq!(device.tx_queue.len(), 3);
    assert_eq!(active_generation, Some(12));
    assert_eq!(stats.direct_dns_outcomes(), (1, 2));
    assert!(!unregister_direct_dns_socket_binder_if(registration_a), "stale unregister cannot clear B");
    assert!(unregister_direct_dns_socket_binder_if(registration_b));
}

#[test]
fn drain_dns_responses_yields_after_one_phase_budget() {
    use crate::io_loop::IO_PHASE_WORK_BUDGET;

    let mapdns = test_mapdns();
    let mut cache = test_dns_cache();
    let mut device = TunDevice::new(1500);
    let stats = Arc::new(Stats::default());
    let (resp_tx, resp_rx) = tokio::sync::mpsc::channel::<DnsResponse>(IO_PHASE_WORK_BUDGET + 1);
    let (req_tx, _req_rx) = tokio::sync::mpsc::channel::<DnsRequest>(8);
    let mut dns_resp_rx = Some(resp_rx);
    let mut dns_req_tx = Some(req_tx);

    for index in 0..=IO_PHASE_WORK_BUDGET {
        let host = format!("drain-{index}.test");
        resp_tx
            .try_send(DnsResponse {
                src: SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53000),
                query: build_query(&host),
                host: Some(host.clone()),
                upstream: Ok(EncryptedDnsExchangeSuccess {
                    response_bytes: build_response(&host, Ipv4Addr::new(1, 2, 3, 4)),
                    endpoint_label: "test".to_string(),
                    latency_ms: 5,
                }),
                resolver_error_kind: None,
                resolver_endpoint_label: Some("test".to_string()),
                direct_generation: None,
                direct_fallback: false,
            })
            .expect("queue response");
    }

    drain_dns_responses(&mut device, &stats, mapdns, &mut cache, &mut dns_resp_rx, &mut dns_req_tx, &mut None);

    assert_eq!(device.tx_queue.len(), IO_PHASE_WORK_BUDGET, "one io-loop phase must process only its bounded batch");
    assert!(
        dns_resp_rx.as_mut().expect("response receiver").try_recv().is_ok(),
        "the next response must remain queued for the following loop tick",
    );
}
