use super::*;
use crate::types::QUIC_V2_VERSION;

#[test]
fn active_probe_uses_a_quic_client_hello() {
    for (version, profile) in [QUIC_V1_VERSION, QUIC_V2_VERSION].into_iter().flat_map(|version| {
        [QuicProbeProfile::Chrome, QuicProbeProfile::Firefox, QuicProbeProfile::Generic]
            .into_iter()
            .map(move |profile| (version, profile))
    }) {
        let packet = build_profiled_probe_quic_initial(version, Some("example.test"), profile).expect("probe");
        assert_eq!(packet.len(), 1200);
        assert_eq!(crate::quic::parse_quic_initial(&packet).expect("parsed probe").host(), b"example.test");
        let seed = crate::quic::parse_quic_initial_seed(&packet).expect("probe seed");
        let hello = &seed.client_hello;
        assert_eq!(hello[43], 0, "QUIC does not use TLS compatibility session IDs");
        let parsed = crate::tls_nom::parse_client_hello_record(hello).expect("ClientHello");
        let parameters = crate::tls_nom::find_extension(&parsed, 0x0039).expect("QUIC transport parameters");
        assert_eq!(parameters.data, [vec![0x0f, seed.scid.len() as u8], seed.scid].concat());
        assert_eq!(crate::tls_nom::find_extension(&parsed, 0x0010).expect("ALPN").data, b"\x00\x03\x02h3");
        assert_eq!(crate::tls_nom::find_extension(&parsed, 0x002b).expect("supported versions").data, b"\x02\x03\x04");
        let mut types: Vec<_> = parsed.extensions.iter().map(|extension| extension.ext_type).collect();
        types.sort_unstable();
        assert!(types.windows(2).all(|pair| pair[0] != pair[1]), "duplicate TLS extensions");
    }
}

#[test]
fn probes_carry_fresh_client_hello_random_and_key_share() {
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

    assert_ne!(hellos[0][11..43], hellos[1][11..43], "random");
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
