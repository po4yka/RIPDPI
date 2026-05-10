use ripdpi_protocol_detect::classify_l7;
use ripdpi_strategy_trait::L7Protocol;

#[test]
fn detects_stun_magic_cookie() {
    let packet = [0x00, 0x01, 0x00, 0x00, 0x21, 0x12, 0xA4, 0x42];
    assert!(matches!(classify_l7(&packet, 0, 3478, true), L7Protocol::Stun(_)));
}

#[test]
fn udp_on_stun_port_without_magic_cookie_is_unknown() {
    let packet = [0x00, 0x01, 0x00, 0x00, 0x20, 0x12, 0xA4, 0x42];

    assert!(matches!(classify_l7(&packet, 0, 3478, true), L7Protocol::Unknown));
}
