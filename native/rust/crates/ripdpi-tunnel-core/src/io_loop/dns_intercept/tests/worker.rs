use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::Arc;

use ripdpi_dns_resolver::EncryptedDnsExchangeSuccess;

use crate::{Stats, TunDevice};

use super::super::{drain_dns_responses, route_dns_packet, DnsRequest, DnsResponse};
use super::support::{build_query, build_response, test_dns_cache, test_mapdns};

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
        &mut dns_req_tx,
        &mut dns_resp_rx,
        src,
        &query,
        Some("no-resolver.test".to_string()),
    );

    assert!(!device.tx_queue.is_empty(), "SERVFAIL should be sent when no resolver");
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
        })
        .expect("send response");

    drain_dns_responses(&mut device, &stats, mapdns, &mut cache, &mut dns_resp_rx, &mut dns_req_tx);

    assert_eq!(device.tx_queue.len(), 1, "response should have been processed and queued");
    assert!(dns_req_tx.is_some(), "channels should remain open");
}
