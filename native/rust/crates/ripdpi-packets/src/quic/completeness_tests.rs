use super::super::build::build_quic_initial_raw;
use super::*;

fn assert_incomplete_packet(packet: &[u8]) {
    assert!(parse_quic_initial(packet).is_none(), "full parse rejects a prefix");
    assert!(parse_quic_initial_layout(packet).is_none(), "layout rejects a prefix");
    assert!(parse_quic_initial_seed(packet).is_none(), "seed rejects a prefix");
    assert!(tamper_quic_initial_split_crypto(packet, 40).is_none(), "tamper rejects a prefix");
}

#[test]
fn client_hello_prefix_with_sni_is_not_complete() {
    let packets: [&[u8]; 2] = [
        include_bytes!("../../tests/fixtures/quic-initial/partial-v1.bin"),
        include_bytes!("../../tests/fixtures/quic-initial/partial-v2.bin"),
    ];
    for packet in packets {
        assert_incomplete_packet(packet);
    }
}

#[test]
fn complete_quic_results_require_nested_vector_bounds() {
    for packet in [
        include_bytes!("../../tests/fixtures/quic-initial/token-v1.bin").as_slice(),
        include_bytes!("../../tests/fixtures/quic-initial/token-v2.bin").as_slice(),
    ] {
        let seed = parse_quic_initial_seed(packet).expect("complete independent Initial");
        let hello = &seed.client_hello[TLS_RECORD_HEADER_LEN..];
        // The RFC message has no SID, two suites and one compression byte.
        assert_eq!(hello[38], 0);
        assert_eq!(&hello[39..41], &[0, 4]);
        assert_eq!(&hello[45..49], &[1, 0, 0, 192]);
        for (offset, value) in
            [(3, 238), (3, 236), (48, 193), (52, 193), (54, 15), (54, 13), (55, 1), (57, 12), (57, 10)]
        {
            let mut broken = hello.to_vec();
            broken[offset] = value;
            if matches!(offset, 3 | 48) {
                assert!(crate::tls::tls_client_hello_marker_info_in_handshake(&broken).is_some());
            }
            let payload = make_crypto_frame(0, &broken);
            let packet = build_quic_initial_raw(seed.version, &seed.dcid, &seed.scid, &seed.token, payload, 1200, 0)
                .expect("encrypt malformed handshake");
            assert_incomplete_packet(&packet);
        }
        assert_eq!(parse_quic_initial(packet).expect("complete Initial").client_hello, hello);
    }
}
