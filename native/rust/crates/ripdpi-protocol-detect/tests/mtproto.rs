use ripdpi_protocol_detect::classify_l7;
use ripdpi_strategy_trait::L7Protocol;

#[test]
fn detects_mtproto_only_as_port_heuristic_after_stronger_signatures() {
    let payload = [0xA5; 64];
    assert!(matches!(classify_l7(&payload, 50000, 443, false), L7Protocol::Mtproto(_)));
    assert!(matches!(classify_l7(&payload, 50000, 1234, false), L7Protocol::Unknown));
}

#[test]
fn ordinary_web_traffic_on_mtproto_ports_is_not_mtproto() {
    let mut http = b"GET /index.html HTTP/1.1\r\nUser-Agent: test\r\nAccept: */*\r\n".to_vec();
    http.resize(96, b'x');
    assert!(!matches!(classify_l7(&http, 50000, 80, false), L7Protocol::Mtproto(_)));

    let mut tls_app_data = vec![0x17, 0x03, 0x03, 0x04, 0x00];
    tls_app_data.resize(1029, 0xA5);
    assert!(!matches!(classify_l7(&tls_app_data, 50000, 443, false), L7Protocol::Mtproto(_)));

    let mut quic_short_header = vec![0x45];
    quic_short_header.resize(1200, 0xA5);
    assert!(!matches!(classify_l7(&quic_short_header, 50000, 443, true), L7Protocol::Mtproto(_)));
}
