use super::*;

#[test]
fn crypto_with_ack_matches_independent_initial_vectors() {
    let packets: [&[u8]; 2] = [
        include_bytes!("../../tests/fixtures/quic-initial/ack-v1.bin"),
        include_bytes!("../../tests/fixtures/quic-initial/ack-v2.bin"),
    ];
    for packet in packets {
        let parsed = parse_quic_initial(packet).expect("ACK and complete CRYPTO");
        assert_eq!(parsed.host(), b"example.com");
        assert!(parsed.is_crypto_complete);
        assert_eq!(parsed.client_hello.len(), 241);
    }
}

#[test]
fn ack_and_ecn_ranges_can_precede_or_follow_crypto() {
    // ACKs cover packet numbers 9..=10 and 4..=6.
    for ack in [vec![0x02, 10, 0, 1, 1, 1, 2], vec![0x03, 10, 0, 1, 1, 1, 2, 3, 4, 5]] {
        let crypto = make_crypto_frame(0, b"hello");
        for payload in [[ack.clone(), crypto.clone()].concat(), [crypto.clone(), ack.clone()].concat()] {
            let (data, complete) = defrag_quic_crypto_frames(&payload).expect("valid ACK ranges");
            assert_eq!(data, b"hello");
            assert!(complete);
        }
    }
}

#[test]
fn malformed_ack_ranges_and_ecn_counts_are_rejected() {
    let malformed = [
        vec![0x02],
        vec![0x02, 0, 0, 0, 1],             // First range exceeds the largest acknowledged number.
        vec![0x02, 1, 0, 1, 1, 0, 0],       // Gap underflow.
        vec![0x02, 10, 0, 1, 0, 0, 9],      // Later range underflow.
        vec![0x02, 10, 0, 1, 0, 0],         // Missing later range.
        vec![0x03, 0, 0, 0, 0, 0, 0],       // Missing the third ECN count.
        vec![0x03, 0, 0, 0, 0, 0, 0, 0xc0], // Truncated ECN varint.
        vec![0x02, 10, 0, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0],
    ];
    for ack in malformed {
        let payload = [make_crypto_frame(0, b"hello"), ack].concat();
        assert!(defrag_quic_crypto_frames(&payload).is_none());
    }
}
