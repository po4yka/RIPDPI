use ripdpi_protocol_detect::classify_l7;
use ripdpi_strategy_trait::L7Protocol;

#[test]
fn detects_tls_client_hello_signature() {
    let payload = [0x16, 0x03, 0x03, 0x00, 0x2A, 0x01, 0, 0, 0x26];
    assert!(matches!(classify_l7(&payload, 12345, 443, false), L7Protocol::Tls(_)));
}

#[test]
fn detects_quic_initial_long_header_signature() {
    let payload = [0xC3, 0x00, 0x00, 0x00, 0x01, 8, 1, 2, 3, 4, 5, 6, 7, 8];
    assert!(matches!(classify_l7(&payload, 12345, 443, true), L7Protocol::Quic(_)));
}
