use super::*;
use crate::quic::build::build_quic_initial_raw;

fn hex(value: &str) -> Vec<u8> {
    let compact: String = value.split_whitespace().collect();
    compact
        .as_bytes()
        .as_chunks::<2>()
        .0
        .iter()
        .map(|pair| u8::from_str_radix(std::str::from_utf8(pair).unwrap(), 16).unwrap())
        .collect()
}

fn rfc_request(version: u32) -> Vec<u8> {
    build_quic_initial_raw(version, &hex("8394c8f03e515708"), &[], &[], vec![0; 128], 128, 0).unwrap()
}

#[test]
fn validates_rfc_server_initial_and_retry_integrity() {
    // Independent wire vectors: RFC 9001 A.3/A.4 and RFC 9369 A.3/A.4.
    // https://www.rfc-editor.org/rfc/rfc9001.html#appendix-A.3
    // https://www.rfc-editor.org/rfc/rfc9369.html#appendix-A.3
    for (version, initial, retry) in [
        (
            QUIC_V1_VERSION,
            "cf000000010008f067a5502a4262b5004075c0d95a482cd0991cd25b0aac406a
          5816b6394100f37a1c69797554780bb38cc5a99f5ede4cf73c3ec2493a1839b3
          dbcba3f6ea46c5b7684df3548e7ddeb9c3bf9c73cc3f3bded74b562bfb19fb84
          022f8ef4cdd93795d77d06edbb7aaf2f58891850abbdca3d20398c276456cbc4
          2158407dd074ee",
            "ff000000010008f067a5502a4262b5746f6b656e04a265ba2eff4d829058fb3f0f2496ba",
        ),
        (
            QUIC_V2_VERSION,
            "dc6b3343cf0008f067a5502a4262b5004075d92faaf16f05d8a4398c47089698
          baeea26b91eb761d9b89237bbf87263017915358230035f7fd3945d88965cf17
          f9af6e16886c61bfc703106fbaf3cb4cfa52382dd16a393e42757507698075b2
          c984c707f0a0812d8cd5a6881eaf21ceda98f4bd23f6fe1a3e2c43edd9ce7ca8
          4bed8521e2e140",
            "cf6b3343cf0008f067a5502a4262b5746f6b656ec8646ce8bfe33952d955543665dcc7b6",
        ),
    ] {
        let request = rfc_request(version);
        for (wire, kind) in [(initial, QuicResponseKind::Initial), (retry, QuicResponseKind::Retry)] {
            let mut response = hex(wire);
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
