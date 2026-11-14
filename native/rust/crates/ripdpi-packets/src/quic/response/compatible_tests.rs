use super::*;
use crate::validate_quic_response;

fn parameter(original: u32, available: &[u32]) -> Vec<u8> {
    let mut bytes = vec![0x11, (4 + 4 * available.len()) as u8];
    bytes.extend_from_slice(&original.to_be_bytes());
    for version in available {
        bytes.extend_from_slice(&version.to_be_bytes());
    }
    bytes
}

#[test]
fn dpi24_compatible_offer_requires_complete_unambiguous_version_information() {
    assert_eq!(version_information_offers(&parameter(1, &[1, QUIC_V2_VERSION]), 1, QUIC_V2_VERSION), Some(true));
    assert_eq!(version_information_offers(&parameter(1, &[1, 1, QUIC_V2_VERSION]), 1, QUIC_V2_VERSION), Some(true));
    for (chosen, versions) in [
        (1, vec![1]),
        (1, vec![]),
        (1, vec![QUIC_V2_VERSION]),
        (1, vec![1, 0, QUIC_V2_VERSION]),
        (0, vec![1, QUIC_V2_VERSION]),
        (QUIC_V2_VERSION, vec![1, QUIC_V2_VERSION]),
    ] {
        assert_eq!(version_information_offers(&parameter(chosen, &versions), 1, QUIC_V2_VERSION), Some(false));
    }
    assert_eq!(version_information_offers(&[0x11, 3, 0, 0, 1], 1, QUIC_V2_VERSION), None);
    let valid = parameter(1, &[1, QUIC_V2_VERSION]);
    for end in 1..valid.len() {
        assert_ne!(version_information_offers(&valid[..end], 1, QUIC_V2_VERSION), Some(true));
    }
    assert_eq!(version_information_offers(&[valid.clone(), valid].concat(), 1, QUIC_V2_VERSION), None);
    assert_eq!(version_information_offers(&[0x40], 1, QUIC_V2_VERSION), None);
}

const V1_CLIENT: &[u8] = include_bytes!("../../../tests/fixtures/quic/compatible/v1-v2-client.bin");
const V1_PARTIAL: &[u8] = include_bytes!("../../../tests/fixtures/quic/compatible/v1-v2-partial-client.bin");
const V2_SERVER: &[u8] = include_bytes!("../../../tests/fixtures/quic/compatible/v1-v2-server.bin");
const V2_CLIENT: &[u8] = include_bytes!("../../../tests/fixtures/quic/compatible/v2-v1-client.bin");
const V2_PARTIAL: &[u8] = include_bytes!("../../../tests/fixtures/quic/compatible/v2-v1-partial-client.bin");
const V1_SERVER: &[u8] = include_bytes!("../../../tests/fixtures/quic/compatible/v2-v1-server.bin");

#[test]
fn dpi24_runtime_accepts_only_advertised_compatible_initials() {
    use QuicInitialResponseDisposition::{Accepted, Rejected, UnverifiableClientOffer};
    for (request, partial, response, original, cross_retry) in [
        (
            V1_CLIENT,
            V1_PARTIAL,
            V2_SERVER,
            include_bytes!("../../../tests/fixtures/quic/rfc9001-client-initial.bin").as_slice(),
            include_bytes!("../../../tests/fixtures/quic/rfc9369-retry.bin").as_slice(),
        ),
        (
            V2_CLIENT,
            V2_PARTIAL,
            V1_SERVER,
            include_bytes!("../../../tests/fixtures/quic/rfc9369-client-initial.bin").as_slice(),
            include_bytes!("../../../tests/fixtures/quic/rfc9001-retry.bin").as_slice(),
        ),
    ] {
        assert_eq!(validate_quic_initial_response_for_runtime(request, response), Accepted);
        assert_eq!(validate_quic_response(request, response), None, "single-version probes stay strict");
        assert_eq!(validate_quic_initial_response_for_runtime(original, response), Rejected);
        assert_eq!(validate_quic_initial_response_for_runtime(partial, response), UnverifiableClientOffer);
        assert_eq!(validate_quic_initial_response_for_runtime(request, cross_retry), Rejected);
        assert_eq!(validate_quic_initial_response_for_runtime(partial, cross_retry), Rejected);
        let mut corrupt = response.to_vec();
        *corrupt.last_mut().unwrap() ^= 1;
        assert_eq!(validate_quic_initial_response_for_runtime(request, &corrupt), Rejected);
        assert_eq!(validate_quic_initial_response_for_runtime(partial, &corrupt), Rejected);
        let mut corrupt_request = partial.to_vec();
        *corrupt_request.last_mut().unwrap() ^= 1;
        assert_eq!(validate_quic_initial_response_for_runtime(&corrupt_request, response), Rejected);
    }
    let no_sni = include_bytes!("../../../tests/fixtures/quic/compatible/v1-v2-no-sni-client.bin");
    assert!(crate::quic::parse_quic_initial(no_sni).is_none());
    assert_eq!(validate_quic_initial_response_for_runtime(no_sni, V2_SERVER), Accepted);
    let unrelated = include_bytes!("../../../tests/fixtures/quic/compatible/v1-v2-unrelated-server.bin");
    assert_eq!(validate_quic_initial_response_for_runtime(V1_PARTIAL, unrelated), Rejected);
    let retry_request = include_bytes!("../../../tests/fixtures/quic/compatible/v1-v2-post-retry-client.bin");
    let retry_reply = include_bytes!("../../../tests/fixtures/quic/compatible/v1-v2-post-retry-server.bin");
    assert_eq!(validate_quic_initial_response_for_runtime(retry_request, retry_reply), Accepted);
    assert_eq!(
        validate_quic_initial_response_for_runtime(V1_CLIENT, retry_reply),
        Rejected,
        "old DCID cannot open post-Retry keys"
    );
}

#[test]
fn dpi24_initial_identity_uses_both_cids_and_allows_compatible_versions() {
    assert!(
        parse_quic_initial_response_context(V1_CLIENT)
            .unwrap()
            .matches_connection(&parse_quic_initial_response_context(V1_PARTIAL).unwrap())
    );
    assert!(
        parse_quic_initial_response_context(V1_CLIENT)
            .unwrap()
            .matches_connection(&parse_quic_initial_response_context(V2_CLIENT).unwrap())
    );
    let next = include_bytes!("../../../tests/fixtures/quic/compatible/v1-v2-post-retry-client.bin");
    assert!(
        !parse_quic_initial_response_context(V1_CLIENT)
            .unwrap()
            .matches_connection(&parse_quic_initial_response_context(next).unwrap())
    );
    let mut other_scid = V1_CLIENT.to_vec();
    // The public input has an empty SCID; insert one valid CID byte.
    let offset = 6 + usize::from(other_scid[5]);
    other_scid[offset] = 1;
    other_scid.insert(offset + 1, 0xaa);
    assert!(parse_quic_initial_response_context(&other_scid).is_none(), "CID mutation invalidates request integrity");
    assert!(parse_quic_initial_response_context(b"truncated").is_none());
    assert!(std::mem::size_of::<QuicInitialResponseContext>() <= 64, "contexts retain bounded metadata only");
}

#[test]
fn dpi24_runtime_checks_fixed_bit_grease_offer_and_raw_integrity() {
    use QuicInitialResponseDisposition::{Accepted, Rejected, UnverifiableClientOffer};
    macro_rules! fixture {
        ($name:literal) => {
            include_bytes!(concat!("../../../tests/fixtures/quic/compatible/", $name, ".bin")).as_slice()
        };
    }
    for (request, partial, initial, retry, cross_initial, cross_retry, unrelated_initial, unrelated_retry, no_offer) in [
        (
            fixture!("v1-grease-client"),
            fixture!("v1-grease-partial-client"),
            fixture!("v1-grease-initial"),
            fixture!("v1-grease-retry"),
            fixture!("v1-v2-grease-initial"),
            fixture!("v1-v2-grease-retry"),
            fixture!("v1-grease-unrelated-initial"),
            fixture!("v1-grease-unrelated-retry"),
            V1_CLIENT,
        ),
        (
            fixture!("v2-grease-client"),
            fixture!("v2-grease-partial-client"),
            fixture!("v2-grease-initial"),
            fixture!("v2-grease-retry"),
            fixture!("v2-v1-grease-initial"),
            fixture!("v2-v1-grease-retry"),
            fixture!("v2-grease-unrelated-initial"),
            fixture!("v2-grease-unrelated-retry"),
            V2_CLIENT,
        ),
    ] {
        for response in [initial, retry, cross_initial] {
            assert_eq!(response[0] & 0x40, 0);
            assert_eq!(validate_quic_response(request, response), None, "strict probes do not offer grease");
            assert_eq!(validate_quic_initial_response_for_runtime(request, response), Accepted);
            assert_eq!(validate_quic_initial_response_for_runtime(no_offer, response), Rejected);
            assert_eq!(validate_quic_initial_response_for_runtime(partial, response), UnverifiableClientOffer);
            let mut bad_tag = response.to_vec();
            *bad_tag.last_mut().unwrap() ^= 1;
            assert_eq!(validate_quic_initial_response_for_runtime(request, &bad_tag), Rejected);
            assert_eq!(validate_quic_initial_response_for_runtime(partial, &bad_tag), Rejected);
            let mut changed_aad = response.to_vec();
            changed_aad[0] ^= 0x40;
            assert_eq!(
                validate_quic_initial_response_for_runtime(request, &changed_aad),
                Rejected,
                "Fixed Bit is authenticated"
            );
        }
        for response in [cross_retry, unrelated_initial, unrelated_retry] {
            assert_eq!(validate_quic_initial_response_for_runtime(request, response), Rejected);
            assert_eq!(validate_quic_initial_response_for_runtime(partial, response), Rejected);
        }
    }
    assert_eq!(transport_parameter(&[0x6a, 0xb2, 0], 0x2ab2), Some(Some(&[][..])));
    assert_eq!(transport_parameter(&[0x6a, 0xb2, 0, 0x6a, 0xb2, 0], 0x2ab2), None);
    assert_eq!(transport_parameter(&[0x6a, 0xb2, 1, 0], 0x2ab2), Some(Some(&[0][..])));
}

proptest::proptest! {
    #[test]
    fn dpi24_raw_offer_and_runtime_header_parsers_never_panic(
        bytes in proptest::collection::vec(proptest::prelude::any::<u8>(), 0..4096),
        original in proptest::prelude::any::<u32>(),
        negotiated in proptest::prelude::any::<u32>(),
    ) {
        let _ = version_information_offers(&bytes, original, negotiated);
        let _ = transport_parameter(&bytes, 0x2ab2);
        let _ = parse_quic_initial_response_header_for_runtime(&bytes);
    }
}

#[test]
fn dpi24_request_detection_requires_integrity_without_complete_client_hello() {
    for request in [
        V1_CLIENT,
        V1_PARTIAL,
        V2_CLIENT,
        V2_PARTIAL,
        include_bytes!("../../../tests/fixtures/quic/compatible/v1-v2-no-sni-client.bin").as_slice(),
    ] {
        assert!(parse_quic_initial_response_context(request).is_some());
        let mut bad_tag = request.to_vec();
        *bad_tag.last_mut().unwrap() ^= 1;
        assert!(crate::is_quic_initial(&bad_tag), "header-only recognition remains unchanged");
        assert!(parse_quic_initial_response_context(&bad_tag).is_none());
    }
    assert!(parse_quic_initial_response_context(b"generic UDP").is_none());
}
