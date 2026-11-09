use ripdpi_protocol_detect::classify_l7;
use ripdpi_strategy_trait::L7Protocol;

fn is_dht(payload: &[u8], src_port: u16, dst_port: u16) -> bool {
    matches!(classify_l7(payload, src_port, dst_port, true), L7Protocol::Dht(_))
}

#[test]
fn detects_bep5_ping_response_and_error() {
    let ping = b"d1:ad2:id20:abcdefghij0123456789e1:q4:ping1:t2:aa1:y1:qe";
    let response = b"d1:rd2:id20:mnopqrstuvwxyz123456e1:t2:aa1:y1:re";
    let error = b"d1:eli201e23:A Generic Error Ocurrede1:t2:aa1:y1:ee";
    // BEP-42: a response carries the requester's compact address under `ip`, sorted before `r`.
    let bep42_response = b"d2:ip6:\x0a\x00\x00\x02\x1a\xe11:rd2:id20:mnopqrstuvwxyz123456e1:t2:aa1:y1:re";
    for message in [&ping[..], &response[..], &error[..], &bep42_response[..]] {
        assert!(is_dht(message, 6881, 6881));
    }
}

#[test]
fn rejects_bencode_without_krpc_type_key() {
    assert!(!is_dht(b"d1:ad2:id20:abcdefghijklmnopqrstu", 0, 6881));
    assert!(!is_dht(b"d8:announce1:y1:q", 0, 6881));
    assert!(!is_dht(b"d1:ad2:id20:abcdefghij0123456789e1:y1:xe", 0, 6881));
}

#[test]
fn dns_query_for_label_1_y_is_dns() {
    // Header, then QNAME 03 "1:y" 00, QTYPE A, QCLASS IN.
    let mut query = vec![0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0];
    query.extend_from_slice(&[3, b'1', b':', b'y', 0, 0, 1, 0, 1]);
    assert!(matches!(classify_l7(&query, 53000, 53, true), L7Protocol::Dns(_)));
}

#[test]
fn rtp_payload_containing_1_y_is_not_dht() {
    let mut rtp = vec![0x80, 0x60, 0, 1, 0, 0, 0, 160, 0x11, 0x22, 0x33, 0x44];
    rtp.extend_from_slice(b"voice 1:y1:q data");
    assert!(!is_dht(&rtp, 40000, 40002));
}
