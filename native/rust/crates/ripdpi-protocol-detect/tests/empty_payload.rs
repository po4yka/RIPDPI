use ripdpi_protocol_detect::classify_l7;
use ripdpi_strategy_trait::L7Protocol;

#[test]
fn short_payloads_are_unknown_without_panics() {
    assert!(matches!(classify_l7(&[], 0, 443, false), L7Protocol::Unknown));
    assert!(matches!(classify_l7(&[0x16], 0, 443, false), L7Protocol::Unknown));
}
