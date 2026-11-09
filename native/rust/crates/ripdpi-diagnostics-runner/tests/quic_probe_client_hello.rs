use std::sync::Arc;

use ripdpi_packets::{QUIC_V1_VERSION, QUIC_V2_VERSION, QuicProbeProfile, build_profiled_probe_quic_initial};

#[test]
fn active_quic_client_hellos_are_accepted_by_rustls() {
    let certificate = rcgen::generate_simple_self_signed(vec!["example.test".into()]).expect("certificate");
    let key = rustls::pki_types::PrivatePkcs8KeyDer::from(certificate.signing_key.serialize_der());
    let mut config = rustls::ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
        .with_protocol_versions(&[&rustls::version::TLS13])
        .expect("TLS 1.3")
        .with_no_client_auth()
        .with_single_cert(vec![certificate.cert.der().clone()], key.into())
        .expect("server config");
    config.alpn_protocols = vec![b"h3".to_vec()];
    let config = Arc::new(config);

    for (wire_version, tls_version) in
        [(QUIC_V1_VERSION, rustls::quic::Version::V1), (QUIC_V2_VERSION, rustls::quic::Version::V2)]
    {
        for profile in [QuicProbeProfile::Chrome, QuicProbeProfile::Firefox, QuicProbeProfile::Generic] {
            for (host, expected_name) in [("example.test", Some("example.test")), ("127.0.0.1", None), ("::1", None)] {
                let packet = build_profiled_probe_quic_initial(wire_version, Some(host), profile).expect("probe");
                assert_eq!(packet[1..5], wire_version.to_be_bytes());
                assert_eq!(packet[0] & 0xf0, if wire_version == QUIC_V1_VERSION { 0xc0 } else { 0xd0 });
                let (hello, scid) = decrypt_generated_initial(&packet, tls_version);
                assert_eq!(has_server_name(&hello), expected_name.is_some(), "{profile:?} host {host}");
                let mut connection =
                    rustls::quic::ServerConnection::new(config.clone(), tls_version, Vec::new()).expect("server");

                connection.read_hs(&hello).unwrap_or_else(|error| {
                    panic!("{profile:?} QUIC {wire_version:08x} host {host}: {error}");
                });

                let expected_parameters = [vec![0x0f, scid.len() as u8], scid].concat();
                assert_eq!(connection.quic_transport_parameters(), Some(expected_parameters.as_slice()));
                assert_eq!(connection.server_name(), expected_name);
                assert_eq!(connection.alpn_protocol(), Some(b"h3".as_slice()));
            }
        }
    }
}

// Only framing is read here. Rustls independently removes header protection and checks AEAD.
fn decrypt_generated_initial(wire: &[u8], version: rustls::quic::Version) -> (Vec<u8>, Vec<u8>) {
    let dcid_len = usize::from(wire[5]);
    let dcid = &wire[6..6 + dcid_len];
    let mut cursor = 6 + dcid_len;
    let scid_len = usize::from(wire[cursor]);
    cursor += 1;
    let scid = wire[cursor..cursor + scid_len].to_vec();
    cursor += scid_len;
    let token_len = read_varint(wire, &mut cursor);
    cursor += token_len;
    let packet_len = read_varint(wire, &mut cursor);
    let pn_offset = cursor;
    assert_eq!(pn_offset + packet_len, wire.len(), "one generated Initial");

    let keys = rustls::crypto::ring::cipher_suite::TLS13_AES_128_GCM_SHA256
        .tls13()
        .unwrap()
        .quic_suite()
        .unwrap()
        .keys(dcid, rustls::Side::Server, version)
        .remote;
    let sample = &wire[pn_offset + 4..pn_offset + 4 + keys.header.sample_len()];
    let mut first = wire[0];
    let mut pn = wire[pn_offset..pn_offset + 4].to_vec();
    keys.header.decrypt_in_place(sample, &mut first, &mut pn).expect("rustls header protection");
    assert_eq!(first & 0x0c, 0, "Initial reserved bits");
    let pn_len = usize::from(first & 3) + 1;
    let number = pn[..pn_len].iter().fold(0u64, |value, byte| (value << 8) | u64::from(*byte));
    assert_eq!(number, 0, "generated probes start at packet number zero");
    let mut packet = wire.to_vec();
    packet[0] = first;
    packet[pn_offset..pn_offset + pn_len].copy_from_slice(&pn[..pn_len]);
    let (header, payload) = packet.split_at_mut(pn_offset + pn_len);
    let plaintext = keys.packet.decrypt_in_place(number, header, payload).expect("rustls Initial AEAD");
    let mut cursor = 0;
    assert_eq!(read_varint(plaintext, &mut cursor), 6, "CRYPTO frame");
    assert_eq!(read_varint(plaintext, &mut cursor), 0, "CRYPTO offset zero");
    let hello_len = read_varint(plaintext, &mut cursor);
    let hello = plaintext[cursor..cursor + hello_len].to_vec();
    assert!(plaintext[cursor + hello_len..].iter().all(|byte| *byte == 0), "only trailing PADDING");
    (hello, scid)
}

fn has_server_name(hello: &[u8]) -> bool {
    assert_eq!(hello[0], 1, "ClientHello");
    assert_eq!(hello[38], 0, "empty compatibility session ID");
    let mut cursor = 39;
    let cipher_suites_len = read_u16(hello, &mut cursor);
    cursor += cipher_suites_len;
    cursor += 1 + usize::from(hello[cursor]);
    let extensions_len = read_u16(hello, &mut cursor);
    let end = cursor + extensions_len;
    assert_eq!(end, hello.len(), "complete extensions vector");
    while cursor < end {
        let kind = read_u16(hello, &mut cursor);
        let len = read_u16(hello, &mut cursor);
        assert!(cursor + len <= end);
        if kind == 0 {
            return true;
        }
        cursor += len;
    }
    false
}

fn read_u16(bytes: &[u8], cursor: &mut usize) -> usize {
    let value = u16::from_be_bytes(bytes[*cursor..*cursor + 2].try_into().unwrap());
    *cursor += 2;
    usize::from(value)
}

fn read_varint(bytes: &[u8], cursor: &mut usize) -> usize {
    let first = bytes[*cursor];
    let width = 1usize << (first >> 6);
    let encoded = &bytes[*cursor..*cursor + width];
    *cursor += width;
    let value = encoded[1..].iter().fold(u64::from(first & 0x3f), |value, byte| (value << 8) | u64::from(*byte));
    usize::try_from(value).expect("generated packet length")
}
