use super::*;
use crate::quic::build_probe_quic_initial;

fn rfc_request(version: u32) -> Vec<u8> {
    match version {
        QUIC_V1_VERSION => include_bytes!("../../../tests/fixtures/quic/rfc9001-client-initial.bin").to_vec(),
        QUIC_V2_VERSION => include_bytes!("../../../tests/fixtures/quic/rfc9369-client-initial.bin").to_vec(),
        _ => unreachable!(),
    }
}

#[test]
fn validates_independent_server_initial_and_retry_integrity() {
    // RFC packets and independently protected 3000-byte derivatives; see the fixture README.
    // https://www.rfc-editor.org/rfc/rfc9001.html#appendix-A.3
    // https://www.rfc-editor.org/rfc/rfc9369.html#appendix-A.3
    for (version, initial, retry, large) in [
        (
            QUIC_V1_VERSION,
            include_bytes!("../../../tests/fixtures/quic/rfc9001-server-initial.bin").as_slice(),
            include_bytes!("../../../tests/fixtures/quic/rfc9001-retry.bin").as_slice(),
            include_bytes!("../../../tests/fixtures/quic/rfc9001-server-initial-3000.bin").as_slice(),
        ),
        (
            QUIC_V2_VERSION,
            include_bytes!("../../../tests/fixtures/quic/rfc9369-server-initial.bin").as_slice(),
            include_bytes!("../../../tests/fixtures/quic/rfc9369-retry.bin").as_slice(),
            include_bytes!("../../../tests/fixtures/quic/rfc9369-server-initial-3000.bin").as_slice(),
        ),
    ] {
        let request = rfc_request(version);
        for (wire, kind) in
            [(initial, QuicResponseKind::Initial), (retry, QuicResponseKind::Retry), (large, QuicResponseKind::Initial)]
        {
            let mut response = wire.to_vec();
            assert_eq!(validate_quic_response(&request, &response), Some(kind));
            let last = response.len() - 1;
            response[last] ^= 1;
            assert_eq!(validate_quic_response(&request, &response), None);
            response[last] ^= 1;
            for end in 0..response.len() {
                assert_eq!(validate_quic_response(&request, &response[..end]), None);
            }
        }
    }
}

fn version_negotiation(request: &[u8]) -> Vec<u8> {
    let sent = parse_quic_initial_response_header(request).unwrap();
    let mut response = vec![0x80, 0, 0, 0, 0, sent.scid.len() as u8];
    response.extend_from_slice(sent.scid);
    response.push(sent.dcid.len() as u8);
    response.extend_from_slice(sent.dcid);
    response.extend_from_slice(&QUIC_V2_VERSION.to_be_bytes());
    response
}

#[test]
fn fresh_probe_ids_reject_replay_echo_and_unrelated_versions() {
    let first = build_probe_quic_initial(QUIC_V1_VERSION, Some("example.test")).unwrap();
    let second = build_probe_quic_initial(QUIC_V1_VERSION, Some("example.test")).unwrap();
    assert_ne!(first, second);
    let mut response = version_negotiation(&first);
    assert_eq!(validate_quic_response(&first, &response), Some(QuicResponseKind::VersionNegotiation));
    assert_eq!(validate_quic_response(&second, &response), None);
    assert_eq!(validate_quic_response(&first, &first), None);
    assert_eq!(validate_quic_response(&first, b"not QUIC"), None);
    let end = response.len();
    response[end - 4..].copy_from_slice(&QUIC_V1_VERSION.to_be_bytes());
    assert_eq!(validate_quic_response(&first, &response), None);
    response[end - 4..].copy_from_slice(&[0; 4]);
    assert_eq!(validate_quic_response(&first, &response), None);
    response.pop();
    assert_eq!(validate_quic_response(&first, &response), None);
}

#[test]
fn reserved_version_negotiation_checks_both_ids_and_complete_version_list() {
    let mut request = build_probe_quic_initial(QUIC_V1_VERSION, Some("example.test")).unwrap();
    let mut response = version_negotiation(&request);
    let offered = 0x1a2a_3a4au32;
    request[1..5].copy_from_slice(&offered.to_be_bytes());
    assert!(validate_quic_version_negotiation_response(&request, &response));
    assert_eq!(validate_quic_response(&request, &response), None);

    let versions_start = response.len() - 4;
    for list in [&[][..], &[0][..], &[0; 4][..], &offered.to_be_bytes()[..], &[0, 0, 0, 1, 0][..]] {
        let mut malformed = response[..versions_start].to_vec();
        malformed.extend_from_slice(list);
        assert!(!validate_quic_version_negotiation_response(&request, &malformed));
    }
    let (_, dcid, _, _) = long_header(&response).unwrap();
    let scid_start = 7 + dcid.len();
    for offset in [6, scid_start] {
        response[offset] ^= 1;
        assert!(!validate_quic_version_negotiation_response(&request, &response));
        response[offset] ^= 1;
    }
    for end in 0..versions_start {
        assert!(!validate_quic_version_negotiation_response(&request, &response[..end]));
    }
    assert!(!validate_quic_version_negotiation_response(&response, &response));
    assert!(!validate_quic_version_negotiation_response(&request, &request));
}
