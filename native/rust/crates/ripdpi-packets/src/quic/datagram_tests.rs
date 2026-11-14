use super::super::build::build_quic_initial_raw;
use super::*;

const HELLO: &[u8] = include_bytes!("../../tests/fixtures/quic-initial/rfc-client-hello.bin");
const COALESCED: [&[u8]; 2] = [
    include_bytes!("../../tests/fixtures/quic-initial/coalesced-v1.bin"),
    include_bytes!("../../tests/fixtures/quic-initial/coalesced-v2.bin"),
];
const SHORT_TAIL: [&[u8]; 2] = [
    include_bytes!("../../tests/fixtures/quic-initial/short-tail-v1.bin"),
    include_bytes!("../../tests/fixtures/quic-initial/short-tail-v2.bin"),
];

#[test]
fn coalesced_initials_reassemble_independent_vectors_and_wire_positions() {
    for packet in COALESCED {
        let parsed = parse_quic_initial(packet).expect("coalesced Initial ClientHello");
        assert_eq!(parsed.host(), b"example.com");
        assert_eq!(parsed.client_hello, HELLO);
        let layout = parse_quic_initial_layout(packet).expect("coalesced layout");
        assert_eq!(layout.ciphertext_payload_offset, 26);
        assert_eq!(layout.crypto_frames.len(), 2);
        assert_eq!((layout.crypto_frames[0].crypto_offset, layout.crypto_frames[0].data_offset), (0, 3));
        assert_eq!((layout.crypto_frames[1].crypto_offset, layout.crypto_frames[1].data_offset), (50, 604));
        assert_eq!((layout.info.tls_info.host_start, layout.info.tls_info.host_end), (58, 69));
        let seed = parse_quic_initial_seed(packet).expect("full coalesced seed");
        assert_eq!(&seed.client_hello[TLS_RECORD_HEADER_LEN..], HELLO);
        let tampered = tamper_quic_initial_split_crypto(packet, 40).expect("split coalesced CRYPTO");
        assert_eq!(parse_quic_initial(&tampered).expect("tampered Initial").client_hello, HELLO);
        assert!(parse_quic_initial(&packet[..600]).is_none());
        assert!(parse_quic_initial(&packet[600..]).is_none());
    }
}

#[test]
fn final_initial_packet_can_be_shorter_than_128_bytes() {
    for packet in SHORT_TAIL {
        assert_eq!(packet.len(), 1200);
        assert_eq!(packet[1105..].len(), 95);
        let layout = parse_quic_initial_layout(packet).expect("95-byte final Initial");
        assert_eq!(layout.info.client_hello, HELLO);
        assert_eq!(layout.crypto_frames[1].crypto_offset, 200);
        assert_eq!(layout.crypto_frames[1].data_offset, 1109);
        assert_eq!(layout.crypto_frames[1].data_len, 41);
    }
}

#[test]
fn opaque_handshake_and_zero_rtt_packets_do_not_hide_later_initials() {
    for packet in COALESCED {
        let version = u32::from_be_bytes(packet[1..5].try_into().unwrap());
        for packet_type in if version == QUIC_V1_VERSION { [0xd3, 0xe3] } else { [0xe3, 0xf3] } {
            let mut other = vec![packet_type];
            other.extend_from_slice(&packet[1..19]);
            other.push(20); // Length covers four PN bytes and an opaque 16-byte tag.
            other.extend_from_slice(&[0; 20]);
            let joined = [&packet[..600], &other, &packet[600..]].concat();
            let layout = parse_quic_initial_layout(&joined).expect("skip another encryption level");
            assert_eq!(layout.info.client_hello, HELLO);
            assert_eq!(layout.crypto_frames[1].data_offset, 644);
        }
    }
}

#[test]
fn malformed_coalesced_initial_cannot_complete_a_client_hello() {
    for packet in COALESCED {
        for index in [packet.len() - 1, 621] {
            let mut broken = packet.to_vec();
            broken[index] ^= 0xff;
            assert!(parse_quic_initial(&broken).is_none());
            assert!(parse_quic_initial_seed(&broken).is_none());
        }
    }
}

#[test]
fn coalesced_initials_cannot_mix_connection_or_version_contexts() {
    for packet in COALESCED {
        let version = u32::from_be_bytes(packet[1..5].try_into().unwrap());
        let other_version = if version == QUIC_V1_VERSION { QUIC_V2_VERSION } else { QUIC_V1_VERSION };
        for (version, dcid, scid, token) in [
            (version, [0x22; 8], QUIC_FAKE_SCID, Vec::new()),
            (version, QUIC_FAKE_DCID, [0x33; 4], Vec::new()),
            (version, QUIC_FAKE_DCID, QUIC_FAKE_SCID, b"different".to_vec()),
            (other_version, QUIC_FAKE_DCID, QUIC_FAKE_SCID, Vec::new()),
        ] {
            let foreign =
                build_quic_initial_raw(version, &dcid, &scid, &token, make_crypto_frame(50, &HELLO[50..]), 600, 1)
                    .expect("independently protected foreign context");
            assert!(parse_quic_initial(&[&packet[..600], &foreign].concat()).is_none());
        }
    }
}

#[test]
fn conflicting_crypto_overlaps_cannot_produce_an_initial_seed() {
    let conflicting_byte = [HELLO[10] ^ 1];
    let original = make_crypto_frame(0, HELLO);
    let conflicting = make_crypto_frame(10, &conflicting_byte);
    for version in [QUIC_V1_VERSION, QUIC_V2_VERSION] {
        for plaintext in [
            [original.as_slice(), conflicting.as_slice()].concat(),
            [conflicting.as_slice(), original.as_slice()].concat(),
        ] {
            let packet = build_quic_initial_raw(version, &QUIC_FAKE_DCID, &QUIC_FAKE_SCID, &[], plaintext, 1200, 0)
                .expect("authenticated overlapping CRYPTO");
            assert!(parse_quic_initial(&packet).is_none());
            assert!(parse_quic_initial_layout(&packet).is_none());
            assert!(parse_quic_initial_seed(&packet).is_none());
        }
    }
}

#[test]
fn coalesced_crypto_retransmissions_preserve_the_complete_client_hello() {
    let conflicting_byte = [HELLO[130] ^ 1];
    for version in [QUIC_V1_VERSION, QUIC_V2_VERSION] {
        let first = build_quic_initial_raw(
            version,
            &QUIC_FAKE_DCID,
            &QUIC_FAKE_SCID,
            &[],
            make_crypto_frame(0, &HELLO[..160]),
            600,
            0,
        )
        .expect("first Initial");
        let retransmission = make_crypto_frame(120, &HELLO[120..]);
        for conflict in [false, true] {
            let mut plaintext = retransmission.clone();
            if conflict {
                plaintext.extend(make_crypto_frame(130, &conflicting_byte));
            }
            let second = build_quic_initial_raw(version, &QUIC_FAKE_DCID, &QUIC_FAKE_SCID, &[], plaintext, 600, 1)
                .expect("retransmitted CRYPTO Initial");
            for datagram in
                [[first.as_slice(), second.as_slice()].concat(), [second.as_slice(), first.as_slice()].concat()]
            {
                if conflict {
                    assert!(parse_quic_initial(&datagram).is_none());
                    assert!(parse_quic_initial_seed(&datagram).is_none());
                } else {
                    let parsed = parse_quic_initial(&datagram).expect("matching CRYPTO overlap");
                    assert_eq!(parsed.client_hello, HELLO);
                    let seed = parse_quic_initial_seed(&datagram).expect("complete seed");
                    assert_eq!(&seed.client_hello[TLS_RECORD_HEADER_LEN..], HELLO);
                }
            }
        }
    }
}

#[test]
fn ack_only_initial_and_short_header_tail_keep_the_same_client_hello() {
    for packet in COALESCED {
        let version = u32::from_be_bytes(packet[1..5].try_into().unwrap());
        let ack = build_quic_initial_raw(version, &QUIC_FAKE_DCID, &QUIC_FAKE_SCID, &[], vec![2, 0, 0, 0, 0], 128, 2)
            .expect("ACK-only Initial");
        let joined = [ack.as_slice(), packet, &[0x40; 32]].concat();
        assert_eq!(parse_quic_initial(&joined).expect("ACK and short header").client_hello, HELLO);
    }
}
