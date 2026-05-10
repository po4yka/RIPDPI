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

#[test]
fn detects_wireguard_cookie_and_data_messages() {
    let mut cookie = [0_u8; 64];
    cookie[0] = 0x03;
    assert!(matches!(classify_l7(&cookie, 0, 51820, true), L7Protocol::WireGuard(_)));

    let mut data = [0_u8; 32];
    data[0] = 0x04;
    assert!(matches!(classify_l7(&data, 51820, 0, true), L7Protocol::WireGuard(_)));
}

#[test]
fn rejects_truncated_or_wrong_length_wireguard_messages() {
    assert!(matches!(classify_l7(&[0x01, 0, 0], 0, 51820, true), L7Protocol::Unknown));

    let mut short_initiation = [0_u8; 147];
    short_initiation[0] = 0x01;
    assert!(matches!(classify_l7(&short_initiation, 0, 51820, true), L7Protocol::Unknown));

    let mut short_response = [0_u8; 91];
    short_response[0] = 0x02;
    assert!(matches!(classify_l7(&short_response, 51820, 0, true), L7Protocol::Unknown));

    let mut short_cookie = [0_u8; 63];
    short_cookie[0] = 0x03;
    assert!(matches!(classify_l7(&short_cookie, 0, 51820, true), L7Protocol::Unknown));

    let mut short_data = [0_u8; 31];
    short_data[0] = 0x04;
    assert!(matches!(classify_l7(&short_data, 51820, 0, true), L7Protocol::Unknown));
}
