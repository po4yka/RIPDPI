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
            tcp_reply: Some(reply_tx),
        },
    );

    let reply = reply_rx.try_recv().expect("rewritten TCP response");
    assert!(!reply.is_empty());
    assert!(device.tx_queue.is_empty(), "TCP response must not be wrapped as UDP");
}

/// A TXT query for `name` (EDNS payload size `edns`, if any) and an upstream answer of `answer_bytes`
/// TXT data.
fn txt_exchange(name: &str, edns: Option<u16>, answer_bytes: usize) -> (Vec<u8>, Vec<u8>) {
    use hickory_proto::op::{Edns, Message, MessageType, OpCode, Query};
    use hickory_proto::rr::rdata::TXT;
    use hickory_proto::rr::{Name, RData, Record, RecordType};

    let question = Query::new(Name::from_ascii(name).expect("name"), RecordType::TXT);
    let mut query = Message::new(0x4321, MessageType::Query, OpCode::Query);
    query.metadata.recursion_desired = true;
    query.add_query(question.clone());
    if let Some(size) = edns {
        let mut opt = Edns::new();
        opt.set_max_payload(size);
        query.edns = Some(opt);
    }
    let mut response = Message::response(0x4321, OpCode::Query);
    response.add_query(question);
    for _ in 0..answer_bytes.div_ceil(200) {
        let txt = TXT::new(vec!["x".repeat(200)]);
        response.add_answer(Record::from_rdata(Name::from_ascii(name).expect("name"), 60, RData::TXT(txt)));
    }
    (query.to_vec().expect("query"), response.to_vec().expect("response"))
}

/// Runs one successful exchange through handle_dns_result and returns the UDP DNS payload it queued.
fn deliver_udp_answer(query: Vec<u8>, upstream: Vec<u8>, mtu: usize) -> hickory_proto::op::Message {
    let mut device = TunDevice::new(mtu);
    handle_dns_result(
        &mut device,
        &Arc::new(Stats::default()),
        test_mapdns(),
        &mut test_dns_cache(),
        DnsResponse {
            src: SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53000),
            query,
            host: Some("big.test".to_string()),
            upstream: Ok(EncryptedDnsExchangeSuccess {
                response_bytes: upstream,
                endpoint_label: "fixture".to_string(),
                latency_ms: 1,
            }),
            resolver_error_kind: None,
            resolver_endpoint_label: None,
            direct_generation: None,
            tcp_reply: None,
        },
    );
    assert_eq!(device.tx_queue.len(), 1, "one UDP reply is queued");
    let packet = device.tx_queue.front().expect("queued packet");
    assert!(packet.len() <= mtu, "the reply packet fits the TUN MTU");
    hickory_proto::op::Message::from_vec(&packet[28..]).expect("DNS payload after IPv4 and UDP headers")
}

#[test]
fn udp_answer_larger_than_512_bytes_without_edns_is_truncated_with_tc() {
    let (query, upstream) = txt_exchange("big.test", None, 2_000);

    let reply = deliver_udp_answer(query, upstream, 1500);

    assert!(reply.metadata.truncation, "TC tells the stub to retry over TCP");
    assert_eq!(reply.metadata.id, 0x4321);
    assert_eq!(reply.queries.len(), 1);
    assert!(reply.answers.is_empty());
}

#[test]
fn udp_answer_within_the_edns_size_and_mtu_is_delivered_whole() {
    let (query, upstream) = txt_exchange("big.test", Some(1232), 1_000);

    let reply = deliver_udp_answer(query, upstream, 1500);

    assert!(!reply.metadata.truncation);
    assert_eq!(reply.answers.len(), 5);
}

#[test]
fn udp_answer_within_the_edns_size_but_above_the_mtu_is_truncated() {
    let (query, upstream) = txt_exchange("big.test", Some(4096), 2_000);

    let reply = deliver_udp_answer(query, upstream, 1500);

    assert!(reply.metadata.truncation);
    assert!(reply.answers.is_empty());
}

/// `a.example` A IN, ID 0x1234, RD, with an OPT (CLASS 1232) whose DO bit is `dnssec_ok`.
fn query_with_opt(dnssec_ok: bool) -> Vec<u8> {
    let mut query = vec![
        0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x01, b'a', 0x07, b'e', b'x', b'a',
        b'm', b'p', b'l', b'e', 0x00, 0x00, 0x01, 0x00, 0x01,
    ];
    query.extend_from_slice(&[0x00, 0x00, 0x29, 0x04, 0xd0, 0x00, 0x00, if dnssec_ok { 0x80 } else { 0x00 }, 0x00]);
    query.extend_from_slice(&[0x00, 0x00]);
    query
}

#[test]
fn synthesized_servfail_answers_an_opt_request_with_opt_and_copies_do() {
    let mut device = TunDevice::new(1500);
    handle_dns_result(
        &mut device,
        &Arc::new(Stats::default()),
        test_mapdns(),
        &mut test_dns_cache(),
        DnsResponse {
            src: SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53000),
            query: query_with_opt(true),
            host: Some("a.example".to_string()),
            upstream: Err("upstream down".to_string()),
            resolver_error_kind: None,
            resolver_endpoint_label: None,
            direct_generation: None,
            tcp_reply: None,
        },
    );

    let packet = device.tx_queue.front().expect("SERVFAIL queued");
    let payload = &packet[28..];
    assert_eq!(payload[3] & 0x0f, 2, "RCODE SERVFAIL");
    assert_eq!(&payload[10..12], &[0x00, 0x01], "ARCOUNT");
    assert_eq!(&payload[payload.len() - 11..], &[0x00, 0x00, 0x29, 0x04, 0xd0, 0x00, 0x00, 0x80, 0x00, 0x00, 0x00]);
}

#[test]
fn synthesized_servfail_for_a_request_without_opt_has_no_additional_records() {
    let query = &query_with_opt(false)[..27];
    let mut request = query.to_vec();
    request[11] = 0;
    let response = crate::dns_cache::servfail_response(&request).expect("servfail builds");
    assert_eq!(&response[10..12], &[0x00, 0x00]);
    assert_eq!(response.len(), 27);
}

#[test]
fn refused_response_answers_an_opt_request_with_opt() {
    let parsed = super::super::parse_dns_query(&query_with_opt(true)).expect("query parses");
    let refused = hickory_proto::op::Message::from_vec(&parsed.refused_response().expect("encodes")).expect("parses");
    assert_eq!(refused.metadata.response_code, hickory_proto::op::ResponseCode::Refused);
    let edns = refused.edns.expect("OPT present");
    assert!(edns.flags().dnssec_ok);
    assert_eq!(edns.max_payload(), 1232);
}
