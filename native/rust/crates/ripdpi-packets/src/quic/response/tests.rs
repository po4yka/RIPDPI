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
fn probes_carry_fresh_client_hello_random_session_id_and_key_share() {
    let hellos: Vec<Vec<u8>> = (0..2)
        .map(|_| {
            let probe = build_probe_quic_initial(QUIC_V1_VERSION, Some("example.test")).unwrap();
            assert_eq!(crate::quic::parse_quic_initial(&probe).expect("probe parses").host(), b"example.test");
            crate::quic::parse_quic_initial_seed(&probe).expect("probe seed").client_hello
        })
        .collect();
    let key_share = |hello: &[u8]| {
        let parsed = crate::tls_nom::parse_client_hello_record(hello).expect("client hello");
        crate::tls_nom::find_extension(&parsed, 0x0033).expect("key share").data.to_vec()
    };
    let sid_len = usize::from(hellos[0][43]);

    assert_ne!(hellos[0][11..43], hellos[1][11..43], "random");
    assert_ne!(hellos[0][44..44 + sid_len], hellos[1][44..44 + sid_len], "session id");
    assert_ne!(key_share(&hellos[0]), key_share(&hellos[1]), "key share");
}

/// True when `bytes` is a run of `OracleRng::next_u8` output from some seed.
/// Each output byte is bits 16..24 of the new LCG state, so try every state
/// whose bits 16..24 equal the first byte and replay the rest.
fn follows_oracle_rng(bytes: &[u8]) -> bool {
    let (first, rest) = bytes.split_first().expect("non-empty");
    (0..1u32 << 24).any(|free| {
        let mut state = (free & 0xffff) | (u32::from(*first) << 16) | ((free >> 16) << 24);
        rest.iter().all(|byte| {
            state = state.wrapping_mul(1_103_515_245).wrapping_add(12_345);
            ((state >> 16) & 0xff) as u8 == *byte
        })
    })
}

#[test]
fn probe_client_hello_uses_unpredictable_random_and_valid_key_shares() {
    let mut lcg = crate::types::OracleRng::seeded(0x1234_5678);
    let lcg_bytes: Vec<u8> = (0..32).map(|_| lcg.next_u8()).collect();
    assert!(follows_oracle_rng(&lcg_bytes), "positive control");

    let probe = build_probe_quic_initial(QUIC_V1_VERSION, Some("example.test")).unwrap();
    let hello = crate::quic::parse_quic_initial_seed(&probe).expect("probe seed").client_hello;
    assert!(!follows_oracle_rng(&hello[11..43]), "ClientHello random follows the LCG");

    let parsed = crate::tls_nom::parse_client_hello_record(&hello).expect("client hello");
    let shares = crate::tls_nom::find_extension(&parsed, 0x0033).expect("key share").data;
    let mut cursor = &shares[2..];
    let mut checked = Vec::new();
    while let [g0, g1, l0, l1, tail @ ..] = cursor {
        let (group, len) = (u16::from_be_bytes([*g0, *g1]), usize::from(u16::from_be_bytes([*l0, *l1])));
        let (key, next) = tail.split_at(len);
        cursor = next;
        let algorithm = match group {
            0x001d => &ring::agreement::X25519,
            0x0017 => {
                assert_eq!(key[0], 0x04, "P-256 share must be an uncompressed point");
                &ring::agreement::ECDH_P256
            }
            _ => continue,
        };
        // Key agreement fails for a share that is not a valid public key.
        let ours = ring::agreement::EphemeralPrivateKey::generate(algorithm, &SystemRandom::new()).unwrap();
        let peer = ring::agreement::UnparsedPublicKey::new(algorithm, key);
        assert!(ring::agreement::agree_ephemeral(ours, &peer, |_| ()).is_ok(), "group {group:#06x} share invalid");
        checked.push(group);
    }
    assert_eq!(checked, [0x001d, 0x0017]);
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
