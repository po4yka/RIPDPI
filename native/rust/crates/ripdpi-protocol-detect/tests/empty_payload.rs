use ripdpi_protocol_detect::classify_l7;
use ripdpi_strategy_trait::L7Protocol;

#[test]
fn short_payloads_are_unknown_without_panics() {
    assert!(matches!(classify_l7(&[], 0, 443, false), L7Protocol::Unknown));
    assert!(matches!(classify_l7(&[0x16], 0, 443, false), L7Protocol::Unknown));
}

#[test]
fn unrecognized_payload_returns_unknown() {
    let payload = [0x42, 0x24, 0x11, 0x7F, 0x05, 0xAA, 0x08, 0x19, 0x33];

    assert!(matches!(classify_l7(&payload, 12345, 23456, true), L7Protocol::Unknown));
}
