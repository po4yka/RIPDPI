use std::sync::Arc;

use ripdpi_packets::{
    QUIC_V1_VERSION, QUIC_V2_VERSION, QuicProbeProfile, build_profiled_probe_quic_initial, parse_quic_initial_seed,
};

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
            let packet = build_profiled_probe_quic_initial(wire_version, Some("example.test"), profile).expect("probe");
            let seed = parse_quic_initial_seed(&packet).expect("Initial seed");
            let mut connection =
                rustls::quic::ServerConnection::new(config.clone(), tls_version, Vec::new()).expect("server");

            connection.read_hs(&seed.client_hello[5..]).unwrap_or_else(|error| {
                panic!("{profile:?} QUIC {wire_version:08x}: {error}");
            });

            let expected_parameters = [vec![0x0f, seed.scid.len() as u8], seed.scid].concat();
            assert_eq!(connection.quic_transport_parameters(), Some(expected_parameters.as_slice()));
            assert_eq!(connection.server_name(), Some("example.test"));
            assert_eq!(connection.alpn_protocol(), Some(b"h3".as_slice()));
        }
    }
}
