use ripdpi_protocol_detect::classify_l7;
use ripdpi_strategy_trait::L7Protocol;

#[test]
fn detects_wireguard_handshake_messages() {
    let mut initiation = [0_u8; 148];
    initiation[0] = 0x01;
    assert!(matches!(classify_l7(&initiation, 0, 51820, true), L7Protocol::WireGuard(_)));

    let mut response = [0_u8; 92];
    response[0] = 0x02;
    assert!(matches!(classify_l7(&response, 51820, 0, true), L7Protocol::WireGuard(_)));
}
