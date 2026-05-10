use ripdpi_protocol_detect::classify_l7;
use ripdpi_strategy_trait::L7Protocol;

#[test]
fn detects_bencoded_dht_payloads() {
    assert!(matches!(classify_l7(b"d1:ad2:id20:abcdefghijklmnopqrstu", 0, 6881, true), L7Protocol::Dht(_)));
    assert!(matches!(classify_l7(b"d8:announce1:y1:q", 0, 6881, true), L7Protocol::Dht(_)));
}
