use ripdpi_protocol_detect::classify_l7;
use ripdpi_strategy_trait::L7Protocol;

#[test]
fn detects_udp_dns_on_port_53() {
    let query = [0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0];
    assert!(matches!(classify_l7(&query, 53000, 53, true), L7Protocol::Dns(_)));
}

#[test]
fn detects_mdns_query_on_port_5353() {
    let query = [0x00, 0x00, 0x00, 0x00, 0, 1, 0, 0, 0, 0, 0, 0];
    assert!(matches!(classify_l7(&query, 5353, 5353, true), L7Protocol::Dns(_)));
}

#[test]
fn rejects_dns_port_payload_with_implausible_header() {
    let random = [0xde, 0xad, 0xbe, 0xef, 0x99, 0x88, 0x77, 0x66, 0x55, 0x44, 0x33, 0x22];
    assert!(matches!(classify_l7(&random, 53000, 53, true), L7Protocol::Unknown));

    // Query (QR=0) with QDCOUNT 0.
    let no_question = [0x12, 0x34, 0x01, 0x00, 0, 0, 0, 0, 0, 0, 0, 0];
    assert!(matches!(classify_l7(&no_question, 53000, 53, true), L7Protocol::Unknown));

    // Opcode 3 is unassigned (RFC 6895).
    let opcode_3 = [0x12, 0x34, 0x18, 0x00, 0, 1, 0, 0, 0, 0, 0, 0];
    assert!(matches!(classify_l7(&opcode_3, 53000, 53, true), L7Protocol::Unknown));

    // A DSO request (opcode 6) must have QDCOUNT 0 (RFC 8490 §5.4).
    let dso_with_question = [0x12, 0x34, 0x30, 0x00, 0, 1, 0, 0, 0, 0, 0, 0];
    assert!(matches!(classify_l7(&dso_with_question, 53000, 53, true), L7Protocol::Unknown));
}

#[test]
fn detects_dso_keepalive_request_over_tcp() {
    // RFC 8490: opcode 6 (flags byte 0x30), all counts zero, then a Keepalive TLV.
    let mut message = vec![0x12, 0x34, 0x30, 0x00, 0, 0, 0, 0, 0, 0, 0, 0];
    message.extend_from_slice(&[0x00, 0x01, 0x00, 0x08, 0, 0, 0x3a, 0x98, 0, 0, 0x27, 0x10]);
    let mut framed = (message.len() as u16).to_be_bytes().to_vec();
    framed.extend_from_slice(&message);
    assert!(matches!(classify_l7(&framed, 53000, 53, false), L7Protocol::Dns(_)));
}

#[test]
fn detects_tcp_dns_length_prefixed_message() {
    let query = [0x00, 0x0C, 0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0];
    assert!(matches!(classify_l7(&query, 53000, 53, false), L7Protocol::Dns(_)));
}

#[test]
fn tcp_dns_response_uses_inner_message_flags() {
    let response = [0x00, 0x0C, 0x12, 0x34, 0x81, 0x80, 0, 1, 0, 1, 0, 0, 0, 0];
    let L7Protocol::Dns(dissect) = classify_l7(&response, 53, 53000, false) else {
        panic!("expected TCP DNS response to classify as DNS");
    };

    assert!(!dissect.is_query);
}

#[test]
fn detects_pipelined_tcp_dns_queries() {
    let mut payload = Vec::new();
    for id in [0x1111_u16, 0x2222] {
        payload.extend_from_slice(&[0x00, 0x1D]);
        payload.extend_from_slice(&id.to_be_bytes());
        payload.extend_from_slice(&[0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0]);
        payload.extend_from_slice(&[7, b'e', b'x', b'a', b'm', b'p', b'l', b'e', 3, b'c', b'o', b'm', 0, 0, 1, 0, 1]);
    }
    assert!(matches!(classify_l7(&payload, 53000, 53, false), L7Protocol::Dns(_)));
}

#[test]
fn tcp_dns_accepts_message_continuing_in_next_segment() {
    // Length 0x10 announces more bytes than this segment holds.
    let first_segment = [0x00, 0x10, 0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0];
    assert!(matches!(classify_l7(&first_segment, 53000, 53, false), L7Protocol::Dns(_)));
}

#[test]
fn rejects_tcp_dns_with_short_length_or_truncated_header() {
    let short_length = [0x00, 0x0B, 0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0];
    assert!(matches!(classify_l7(&short_length, 53000, 53, false), L7Protocol::Unknown));

    let length_prefix_only = [0x00, 0x0C];
    assert!(matches!(classify_l7(&length_prefix_only, 53000, 53, false), L7Protocol::Unknown));

    let truncated_header = [0x00, 0x0C, 0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0];
    assert!(matches!(classify_l7(&truncated_header, 53000, 53, false), L7Protocol::Unknown));
}
