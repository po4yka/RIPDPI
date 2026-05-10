use ripdpi_protocol_detect::classify_l7;
use ripdpi_strategy_trait::L7Protocol;

#[test]
fn detects_mtproto_only_as_port_heuristic_after_stronger_signatures() {
    let payload = [0xA5; 64];
    assert!(matches!(classify_l7(&payload, 50000, 443, false), L7Protocol::Mtproto(_)));
    assert!(matches!(classify_l7(&payload, 50000, 1234, false), L7Protocol::Unknown));
}
