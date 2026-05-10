use ripdpi_protocol_detect::classify_l7;
use ripdpi_strategy_trait::L7Protocol;

#[test]
fn detects_dtls_handshake_records() {
    assert!(matches!(classify_l7(&[0x16, 0xFE, 0xFF, 0, 0, 0], 0, 443, true), L7Protocol::Dtls(_)));
    assert!(matches!(classify_l7(&[0x16, 0xFE, 0xFD, 0, 0, 0], 0, 443, true), L7Protocol::Dtls(_)));
}

#[test]
fn rejects_too_short_dtls_record_prefix() {
    assert!(matches!(classify_l7(&[0x16, 0xFE], 0, 4444, true), L7Protocol::Unknown));
}
