use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::Arc;

use ripdpi_dns_resolver::{EncryptedDnsErrorKind, EncryptedDnsExchangeSuccess};

use crate::{Stats, TunDevice};

use super::super::{DnsResponse, handle_dns_result};
use super::support::{build_query, build_response, test_dns_cache, test_mapdns};

#[test]
fn handle_dns_result_queues_response_for_later_tun_flush() {
    let mapdns = test_mapdns();
    let mut cache = test_dns_cache();
    let mut device = TunDevice::new(1500);
    let stats = Arc::new(Stats::default());
    let query = build_query("fixture.test");
    let upstream = build_response("fixture.test", Ipv4Addr::new(203, 0, 113, 10));

    handle_dns_result(
        &mut device,
        &stats,
        mapdns,
        &mut cache,
        DnsResponse {
            src: SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53000),
            query,
            host: Some("fixture.test".to_string()),
            upstream: Ok(EncryptedDnsExchangeSuccess {
                response_bytes: upstream,
                endpoint_label: "fixture".to_string(),
                latency_ms: 12,
            }),
            resolver_error_kind: None,
            resolver_endpoint_label: Some("fixture".to_string()),
            direct_generation: None,
            direct_fallback: false,
            tcp_reply: None,
        },
    );

    assert_eq!(device.tx_queue.len(), 1);
    assert!(!device.tx_queue.front().expect("queued packet").is_empty());
}

#[test]
fn handle_dns_result_records_endpoint_for_upstream_failure() {
    let mapdns = test_mapdns();
    let mut cache = test_dns_cache();
    let mut device = TunDevice::new(1500);
    let stats = Arc::new(Stats::default());
    let query = build_query("fixture.test");

    handle_dns_result(
        &mut device,
        &stats,
        mapdns,
        &mut cache,
        DnsResponse {
            src: SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53000),
            query,
            host: Some("fixture.test".to_string()),
            upstream: Err("TLS handshake failed unexpected EOF".to_string()),
            resolver_error_kind: Some(EncryptedDnsErrorKind::Tls),
            resolver_endpoint_label: Some("fixture.test:853".to_string()),
            direct_generation: None,
            direct_fallback: false,
            tcp_reply: None,
        },
    );

    let snapshot = stats.dns_snapshot();
    assert_eq!(snapshot.last_dns_host.as_deref(), Some("fixture.test"));
    assert_eq!(snapshot.last_dns_error.as_deref(), Some("Tls: TLS handshake failed unexpected EOF"),);
    assert_eq!(snapshot.resolver_endpoint.as_deref(), Some("fixture.test:853"));
    assert_eq!(device.tx_queue.len(), 1, "SERVFAIL should be sent after upstream failure");
}

#[test]
fn handle_dns_result_returns_rewritten_payload_to_tcp_session() {
    let mapdns = test_mapdns();
    let mut cache = test_dns_cache();
    let mut device = TunDevice::new(1500);
    let stats = Arc::new(Stats::default());
    let query = build_query("tcp.fixture.test");
    let upstream = build_response("tcp.fixture.test", Ipv4Addr::new(203, 0, 113, 10));
    let (reply_tx, mut reply_rx) = tokio::sync::oneshot::channel();

    handle_dns_result(
        &mut device,
        &stats,
        mapdns,
        &mut cache,
        DnsResponse {
            src: SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53000),
            query,
            host: Some("tcp.fixture.test".to_string()),
            upstream: Ok(EncryptedDnsExchangeSuccess {
                response_bytes: upstream,
                endpoint_label: "fixture".to_string(),
                latency_ms: 12,
            }),
            resolver_error_kind: None,
            resolver_endpoint_label: Some("fixture".to_string()),
            direct_generation: None,
            direct_fallback: false,
            tcp_reply: Some(reply_tx),
        },
    );

    let reply = reply_rx.try_recv().expect("rewritten TCP response");
    assert!(!reply.is_empty());
    assert!(device.tx_queue.is_empty(), "TCP response must not be wrapped as UDP");
}
