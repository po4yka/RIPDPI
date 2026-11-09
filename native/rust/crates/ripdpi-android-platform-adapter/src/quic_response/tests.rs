use super::*;

#[test]
fn native_validator_accepts_independent_rfc_initial_and_retry_vectors() {
    for (request, initial, retry) in [
        (
            include_bytes!("../../../ripdpi-packets/tests/fixtures/quic/rfc9001-client-initial.bin").as_slice(),
            include_bytes!("../../../ripdpi-packets/tests/fixtures/quic/rfc9001-server-initial.bin").as_slice(),
            include_bytes!("../../../ripdpi-packets/tests/fixtures/quic/rfc9001-retry.bin").as_slice(),
        ),
        (
            include_bytes!("../../../ripdpi-packets/tests/fixtures/quic/rfc9369-client-initial.bin").as_slice(),
            include_bytes!("../../../ripdpi-packets/tests/fixtures/quic/rfc9369-server-initial.bin").as_slice(),
            include_bytes!("../../../ripdpi-packets/tests/fixtures/quic/rfc9369-retry.bin").as_slice(),
        ),
    ] {
        for response in [initial, retry] {
            assert!(validate_response(request, response, false));
            assert!(!validate_response(request, response, true));
            for end in 0..response.len() {
                assert!(!validate_response(request, &response[..end], false));
            }
            let mut damaged = response.to_vec();
            let last = damaged.len() - 1;
            damaged[last] ^= 1;
            assert!(!validate_response(request, &damaged, false));
        }
        assert!(!validate_response(request, request, false));
        assert!(!validate_response(request, b"not QUIC", false));
    }
}

#[test]
fn version_negotiation_cannot_count_as_an_initial_fingerprint_success() {
    let mut request = include_bytes!("../../../ripdpi-packets/tests/fixtures/quic/rfc9001-client-initial.bin").to_vec();
    let response = [0x80, 0, 0, 0, 0, 0, 8, 0x83, 0x94, 0xc8, 0xf0, 0x3e, 0x51, 0x57, 0x08, 0x6b, 0x33, 0x43, 0xcf];
    assert!(validate_response(&request, &response, true));
    assert!(!validate_response(&request, &response, false));
    request[1..5].copy_from_slice(&0x1a2a_3a4au32.to_be_bytes());
    assert!(validate_response(&request, &response, true));
    assert!(!validate_response(&request, &response, false));
}
