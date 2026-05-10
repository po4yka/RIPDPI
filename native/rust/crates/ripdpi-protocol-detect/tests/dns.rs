use ripdpi_protocol_detect::classify_l7;
use ripdpi_strategy_trait::L7Protocol;

#[test]
fn detects_udp_dns_on_port_53() {
    let query = [0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0];
    assert!(matches!(classify_l7(&query, 53000, 53, true), L7Protocol::Dns(_)));
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
