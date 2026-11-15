use super::*;

#[test]
fn native_validator_classifies_independent_rfc_initial_and_retry_vectors() {
    for (request, initial, padded_initial, retry) in [
        (
            include_bytes!("../../../ripdpi-packets/tests/fixtures/quic/rfc9001-client-initial.bin").as_slice(),
            include_bytes!("../../../ripdpi-packets/tests/fixtures/quic/rfc9001-server-initial.bin").as_slice(),
            include_bytes!("../../../ripdpi-packets/tests/fixtures/quic/rfc9001-server-initial-3000.bin").as_slice(),
            include_bytes!("../../../ripdpi-packets/tests/fixtures/quic/rfc9001-retry.bin").as_slice(),
        ),
        (
            include_bytes!("../../../ripdpi-packets/tests/fixtures/quic/rfc9369-client-initial.bin").as_slice(),
            include_bytes!("../../../ripdpi-packets/tests/fixtures/quic/rfc9369-server-initial.bin").as_slice(),
            include_bytes!("../../../ripdpi-packets/tests/fixtures/quic/rfc9369-server-initial-3000.bin").as_slice(),
            include_bytes!("../../../ripdpi-packets/tests/fixtures/quic/rfc9369-retry.bin").as_slice(),
        ),
    ] {
        assert_eq!(validate_response(request, initial, false), ProbeReplyStatus::Uncorrelated);
        for (response, expected) in
            [(padded_initial, ProbeReplyStatus::InitialCrypto), (retry, ProbeReplyStatus::Retry)]
        {
            assert_eq!(validate_response(request, response, false), expected);
            assert_eq!(validate_response(request, response, true), ProbeReplyStatus::Uncorrelated);
            for end in 0..response.len() {
                assert_eq!(validate_response(request, &response[..end], false), ProbeReplyStatus::Uncorrelated);
            }
            let mut damaged = response.to_vec();
            let last = damaged.len() - 1;
            damaged[last] ^= 1;
            assert_eq!(validate_response(request, &damaged, false), ProbeReplyStatus::Uncorrelated);
        }
        assert_eq!(validate_response(request, request, false), ProbeReplyStatus::Uncorrelated);
        assert_eq!(validate_response(request, b"not QUIC", false), ProbeReplyStatus::Uncorrelated);
    }
}

#[test]
fn version_negotiation_cannot_count_as_an_initial_fingerprint_success() {
    let mut request = include_bytes!("../../../ripdpi-packets/tests/fixtures/quic/rfc9001-client-initial.bin").to_vec();
    let response = [0x80, 0, 0, 0, 0, 0, 8, 0x83, 0x94, 0xc8, 0xf0, 0x3e, 0x51, 0x57, 0x08, 0x6b, 0x33, 0x43, 0xcf];
    assert_eq!(validate_response(&request, &response, true), ProbeReplyStatus::VersionNegotiation);
    assert_eq!(validate_response(&request, &response, false), ProbeReplyStatus::Uncorrelated);
    request[1..5].copy_from_slice(&0x1a2a_3a4au32.to_be_bytes());
    assert_eq!(validate_response(&request, &response, true), ProbeReplyStatus::VersionNegotiation);
    assert_eq!(validate_response(&request, &response, false), ProbeReplyStatus::Uncorrelated);
}

#[test]
fn native_validator_classifies_non_ack_eliciting_initials_without_padding_to_1200() {
    // aioquic 1.3.0 server Initials with the CID context of RFC 9001 Appendix A.3.
    // PN 0 acknowledges client PN 0. PN 2 closes with NO_ERROR.
    // The third carries only PADDING. None carries CRYPTO.
    let request = include_bytes!("../../../ripdpi-packets/tests/fixtures/quic/rfc9001-client-initial.bin");
    let cases: &[(&[u8], ProbeReplyStatus)] = &[
        (
            &[
                0xc8, 0x00, 0x00, 0x00, 0x01, 0x00, 0x08, 0xf0, 0x67, 0xa5, 0x50, 0x2a, 0x42, 0x62, 0xb5, 0x00, 0x40,
                0x17, 0xc6, 0xf3, 0xaa, 0x30, 0x39, 0xb1, 0x1b, 0xad, 0xd5, 0x38, 0x73, 0x80, 0x47, 0x38, 0xd0, 0xe6,
                0x31, 0x06, 0x4d, 0x74, 0x0e, 0x01, 0x30,
            ],
            ProbeReplyStatus::InitialAck,
        ),
        (
            &[
                0xc4, 0x00, 0x00, 0x00, 0x01, 0x00, 0x08, 0xf0, 0x67, 0xa5, 0x50, 0x2a, 0x42, 0x62, 0xb5, 0x00, 0x40,
                0x16, 0x89, 0xe7, 0x23, 0xba, 0xe3, 0x04, 0x98, 0xaa, 0x58, 0x39, 0xc5, 0x78, 0x01, 0xb7, 0x8f, 0xe2,
                0xc2, 0xcd, 0x9a, 0x53, 0x2f, 0xf6,
            ],
            ProbeReplyStatus::InitialClose,
        ),
        (
            &[
                0xcb, 0x00, 0x00, 0x00, 0x01, 0x00, 0x08, 0xf0, 0x67, 0xa5, 0x50, 0x2a, 0x42, 0x62, 0xb5, 0x00, 0x40,
                0x14, 0x9c, 0x97, 0xa8, 0x30, 0x24, 0xfa, 0xc7, 0x0c, 0x89, 0x0b, 0x68, 0xa6, 0xa1, 0xcf, 0x7d, 0x38,
                0x6c, 0xa2, 0x6f, 0xb2,
            ],
            ProbeReplyStatus::InitialOther,
        ),
    ];
    for &(response, expected) in cases {
        assert_eq!(validate_response(request, response, false), expected);
    }

    let ack = cases[0].0;
    let close = cases[1].0;
    let crypto = include_bytes!("../../../ripdpi-packets/tests/fixtures/quic/rfc9001-server-initial.bin");
    let mut datagram = [ack, crypto].concat();
    // Pad the datagram for the ack-eliciting Initial. The trailing bytes are not a valid packet.
    datagram.resize(1200, 0);
    assert_eq!(validate_response(request, &datagram, false), ProbeReplyStatus::InitialCrypto);

    datagram.truncate(ack.len() + crypto.len());
    datagram.extend_from_slice(close);
    datagram.resize(1200, 0);
    assert_eq!(validate_response(request, &datagram, false), ProbeReplyStatus::InitialClose);

    assert_eq!(validate_response(request, &[ack, &[0xc0]].concat(), false), ProbeReplyStatus::InitialAck);
}
