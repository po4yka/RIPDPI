use crate::{normalize_quic_initial, normalize_tls_client_hello};
use ripdpi_packets::{build_quic_initial_from_tls, tls_marker_info, QUIC_V1_VERSION};

use super::rust_packet_seeds;

fn first_cipher_suite_offset(packet: &[u8]) -> usize {
    let mut cursor = 5usize + 4 + 2 + 32;
    let session_id_len = usize::from(packet[cursor]);
    cursor += 1 + session_id_len;
    cursor + 2
}

const GOLDEN_TLS_FIXTURES: &[(&[u8], &[u8])] = &[
    (
        include_bytes!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../ripdpi-packets/tests/fixtures/curl_tls12_example_com.bin"
        )),
        b"example.com",
    ),
    (
        include_bytes!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../ripdpi-packets/tests/fixtures/curl_tls13_example_com.bin"
        )),
        b"example.com",
    ),
    (
        include_bytes!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../ripdpi-packets/tests/fixtures/curl_auto_cloudflare_com.bin"
        )),
        b"cloudflare.com",
    ),
    (
        include_bytes!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../ripdpi-packets/tests/fixtures/curl_tls13_github_com.bin"
        )),
        b"github.com",
    ),
];

#[test]
fn normalize_tls_client_hello_extracts_authority_alpn_and_record_boundary() {
    let packet = rust_packet_seeds::tls_client_hello();
    let ir = normalize_tls_client_hello(&packet).expect("normalize tls client hello");

    assert_eq!(ir.authority, b"www.wikipedia.org");
    assert_eq!(ir.authority_span, 153..170);
    assert_eq!(ir.alpn_protocols, vec![b"h2".to_vec(), b"http/1.1".to_vec()]);
    assert!(!ir.record_boundaries.is_empty());
    assert_eq!(ir.record_boundaries[0].header, 0..5);
    assert_eq!(ir.desired.tcp_segment_boundaries, vec![517]);
}

#[test]
fn normalize_tls_client_hello_preserves_legacy_marker_offsets() {
    let packet = rust_packet_seeds::tls_client_hello();
    let ir = normalize_tls_client_hello(&packet).expect("normalize tls client hello");
    let markers = tls_marker_info(&packet).expect("legacy tls markers");
    let sni_ext = ir.extensions.iter().find(|extension| extension.ext_type == 0x0000).expect("sni extension");

    assert_eq!(ir.extensions_len_offset, markers.ext_len_start);
    assert_eq!(sni_ext.type_range.start + 4, markers.sni_ext_start);
    assert_eq!(ir.authority_span, markers.host_start..markers.host_end);
}

#[test]
fn normalize_tls_client_hello_tracks_ech_presence() {
    let packet = rust_packet_seeds::tls_client_hello_ech();
    let ir = normalize_tls_client_hello(&packet).expect("normalize ech tls client hello");

    assert!(ir.has_ech);
    assert_eq!(ir.authority, b"www.wikipedia.org");
}

#[test]
fn normalize_tls_client_hello_collects_grease_cipher_suites() {
    let mut packet = rust_packet_seeds::tls_client_hello();
    let offset = first_cipher_suite_offset(&packet);
    packet[offset..offset + 2].copy_from_slice(&0x0a0au16.to_be_bytes());
    let ir = normalize_tls_client_hello(&packet).expect("normalize tls client hello with grease");

    assert_eq!(ir.grease.cipher_suites, vec![0x0a0a]);
}

#[test]
fn normalize_tls_client_hello_matches_golden_fixture_authority_and_boundaries() {
    for (packet, expected_authority) in GOLDEN_TLS_FIXTURES {
        let ir = normalize_tls_client_hello(packet).expect("normalize golden tls client hello");

        assert_eq!(ir.authority.as_slice(), *expected_authority);
        assert!(!ir.record_boundaries.is_empty());
        assert_eq!(ir.record_boundaries[0].header.start, 0);
        assert_eq!(ir.record_boundaries.last().expect("record boundary").payload.end, packet.len());
        assert_eq!(ir.desired.tcp_segment_boundaries.last().copied(), Some(packet.len()));
    }
}

#[test]
fn normalize_quic_initial_preserves_tls_authority_and_crypto_boundaries() {
    let tls_client_hello = rust_packet_seeds::tls_client_hello();
    let packet = build_quic_initial_from_tls(QUIC_V1_VERSION, &tls_client_hello, 0).expect("build quic initial");
    let ir = normalize_quic_initial(&packet).expect("normalize quic initial");

    assert_eq!(ir.version, QUIC_V1_VERSION);
    assert_eq!(ir.tls_client_hello.authority, b"www.wikipedia.org");
    assert_eq!(ir.desired.udp_datagram_boundaries, vec![packet.len()]);
    assert!(ir.crypto_frames.len() >= 2);
    assert_eq!(
        ir.desired.crypto_frame_boundaries,
        ir.crypto_frames.iter().map(|frame| frame.crypto_range.end).collect::<Vec<_>>()
    );
}
