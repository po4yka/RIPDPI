//! Auth-mode validation and proxy trust-anchor tests.

use super::*;

/// Every certificate in the PEM bundle is trusted, not only the first.
///
/// # Cancel safety:
/// cancel-safe: cancellation during connect or partial I/O drops the test-owned streams;
/// fixture Drop shuts down and joins the server threads, so no partial connection escapes the test.
#[tokio::test]
async fn connect_over_h2_with_pinned_root_certificate_trusts_second_pem_bundle_root() {
    let fixture = MasqueH2ConnectUdpFixture::start().await.expect("start MASQUE fixture");
    let unrelated_pem = localhost_certificate().2;
    let unrelated = boring::x509::X509::from_pem(unrelated_pem.as_bytes()).expect("unrelated certificate");
    let trusted = boring::x509::X509::from_pem(fixture.certificate_pem().as_bytes()).expect("trusted certificate");
    assert_ne!(
        unrelated.subject_name().to_der().expect("unrelated subject"),
        trusted.subject_name().to_der().expect("trusted subject"),
        "independent bundle roots need distinct subjects",
    );
    let config = MasqueConfig {
        socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
        url: fixture.masque_url(),
        proxy_socket_addr: None,
        tcp_protocol: MasqueTcpProtocol::Http2,
        use_http2_fallback: true,
        auth_mode: None,
        auth_token: None,
        client_certificate_chain_pem: None,
        client_private_key_pem: None,
        cloudflare_geohash_header: None,
        privacy_pass_provider_url: None,
        privacy_pass_provider_auth_token: None,
        tls_fingerprint_profile: "native_default".to_string(),
        root_certificate_pem: Some(format!("{}{}", unrelated_pem, fixture.certificate_pem())),
        quic_bind_low_port: false,
        quic_migrate_after_handshake: false,
        ech_config: None,
    };
    let proxy_origin = parse_proxy_origin(&config).expect("proxy origin");
    let transport =
        tokio::net::TcpStream::connect(resolve_proxy_socket_addr(&config, &proxy_origin).await.expect("proxy addr"))
            .await
            .expect("connect proxy transport");

    let mut stream = MasqueClient::connect_over(&config, transport, &fixture.tcp_echo_target())
        .await
        .expect("second PEM root in the bundle must be trusted");

    stream.write_all(b"pinned-ping").await.expect("write pinned stream");
    let mut reply = [0u8; 11];
    stream.read_exact(&mut reply).await.expect("read pinned stream");
    assert_eq!(&reply, b"pinned-ping");
}

#[test]
fn a_malformed_client_certificate_fails_before_connect_in_any_auth_mode() {
    let mut config = privacy_pass_test_config("https://provider.example/token".to_string(), None);
    config.auth_mode = Some("bearer".to_string());
    config.auth_token = Some("secret".to_string());
    config.privacy_pass_provider_url = None;
    config.client_certificate_chain_pem = Some("not a certificate".to_string());
    config.client_private_key_pem = Some("not a key".to_string());
    let error = MasqueClient::new(config).err().expect("H2 presents the certificate, so it must parse");
    assert_eq!(io::ErrorKind::InvalidInput, error.kind());
}

#[test]
fn explicit_none_auth_mode_sends_no_auth_header() {
    let mut config = privacy_pass_test_config("https://provider.example/token".to_string(), None);
    config.auth_mode = Some("none".to_string());
    config.auth_token = Some("stale".to_string());
    config.privacy_pass_provider_url = None;
    assert_eq!(config.effective_auth_mode(), crate::config::MasqueAuthMode::None);
    MasqueClient::new(config.clone()).expect("explicit none is a supported auth mode");
    assert!(crate::auth::build_static_auth_header(&config).expect("header").is_none());
}
