use ripdpi_protocol_detect::classify_l7;
use ripdpi_strategy_trait::L7Protocol;

#[test]
fn quic_initial_does_not_match_wireguard() {
    let mut payload = vec![0xC3, 0x00, 0x00, 0x00, 0x01, 8, 1, 2, 3, 4, 5, 6, 7, 8];
    payload.resize(1200, 0);
    assert!(matches!(classify_l7(&payload, 0, 51820, true), L7Protocol::Quic(_)));
}
