use super::*;

const TOKEN_V1: &[u8] = include_bytes!("../../tests/fixtures/quic-initial/token-v1.bin");
const TOKEN_V2: &[u8] = include_bytes!("../../tests/fixtures/quic-initial/token-v2.bin");

#[test]
fn nonempty_token_rebuild_matches_independent_wire_vectors() {
    for packet in [TOKEN_V1, TOKEN_V2] {
        let seed = parse_quic_initial_seed(packet).expect("independent token Initial");
        assert_eq!(seed.token, b"retry-token");
        assert_eq!(parse_quic_initial(packet).expect("ClientHello").host(), b"example.com");
        let rebuilt = packetize_quic_initial(&seed, &QuicInitialPacketLayout::contiguous(1200)).expect("packetize");
        assert_eq!(rebuilt, packet, "preserve the complete independent wire image");
    }
}

#[test]
fn token_length_boundaries_preserve_bytes_and_datagram_length() {
    for packet in [TOKEN_V1, TOKEN_V2] {
        let mut seed = parse_quic_initial_seed(packet).expect("independent token Initial");
        for token_len in [0usize, 1, 63, 64, 256] {
            seed.token = vec![0xa5; token_len];
            let rebuilt = packetize_quic_initial(&seed, &QuicInitialPacketLayout::contiguous(1200)).expect("packetize");
            let token_start = if token_len < 64 { 20 } else { 21 };
            assert_eq!(&rebuilt[token_start..token_start + token_len], seed.token);
            assert_eq!(rebuilt.len(), 1200);
            let reparsed = parse_quic_initial_seed(&rebuilt).expect("rebuilt Initial");
            assert_eq!(reparsed.token, seed.token);
            assert_eq!(reparsed.client_hello, seed.client_hello);
        }
    }
}
