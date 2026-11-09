use ripdpi_protocol_detect::classify_l7;
use ripdpi_strategy_trait::{L7Protocol, QuicDissect};

#[test]
fn detects_tls_client_hello_signature() {
    let payload = [0x16, 0x03, 0x03, 0x00, 0x2A, 0x01, 0, 0, 0x26];
    assert!(matches!(classify_l7(&payload, 12345, 443, false), L7Protocol::Tls(_)));
}

#[test]
fn rejects_truncated_or_invalid_tls_record_prefixes() {
    assert!(matches!(classify_l7(&[0x16, 0x03], 49152, 8443, false), L7Protocol::Unknown));
    assert!(matches!(classify_l7(&[0x16, 0x02, 0x03, 0, 0, 0x01], 49152, 8443, false), L7Protocol::Unknown));
    assert!(matches!(classify_l7(&[0x16, 0x03, 0x05, 0, 0, 0x01], 49152, 8443, false), L7Protocol::Unknown));
}

/// Client Initial datagram padded to the RFC 9000 section 14.1 minimum.
fn padded(header: [u8; 5]) -> Vec<u8> {
    let mut datagram = header.to_vec();
    datagram.resize(1200, 0);
    datagram
}

#[test]
fn detects_quic_v1_initial_and_reports_version() {
    let payload = padded([0xC3, 0x00, 0x00, 0x00, 0x01]);
    let L7Protocol::Quic(QuicDissect { version }) = classify_l7(&payload, 12345, 443, true) else {
        panic!("expected QUIC");
    };
    assert_eq!(version, Some(1));
}

#[test]
fn detects_quic_v2_initial_without_misclassifying_handshake() {
    let initial = padded([0xD3, 0x6b, 0x33, 0x43, 0xcf]);
    let L7Protocol::Quic(QuicDissect { version }) = classify_l7(&initial, 12345, 443, true) else {
        panic!("expected QUIC v2");
    };
    assert_eq!(version, Some(0x6b33_43cf));
    let mut handshake = initial;
    handshake[0] = 0xE3;
    assert!(matches!(classify_l7(&handshake, 12345, 443, true), L7Protocol::Unknown));
}

#[test]
fn rejects_quic_initial_shorter_than_1200_bytes() {
    let mut payload = padded([0xC3, 0x00, 0x00, 0x00, 0x01]);
    payload.truncate(1199);
    assert!(matches!(classify_l7(&payload, 12345, 443, true), L7Protocol::Unknown));
    let short = [0xC3, 0x00, 0x00, 0x00, 0x01, 8, 1, 2, 3, 4, 5, 6, 7, 8];
    assert!(matches!(classify_l7(&short, 12345, 443, true), L7Protocol::Unknown));
}
